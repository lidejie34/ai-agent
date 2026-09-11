// code_lookup：外部业务仓只读代码定位（FQCN+行号 / 固定字符串 grep）。
// 仓根来自 ~/.ai-agent/troubleshoot/config.json 的 uk.code_root；所有命中路径过
// canonical 闸门，确保不逃逸出 code_root。仓不可读是非致命降级（CODE_ROOT_UNREADABLE）。
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { loadConfig, ConfigError } from './troubleshoot-lib/config.mjs';
import { parseNamed, UsageError } from './troubleshoot-lib/argv.mjs';
import { resolveUk } from './troubleshoot-lib/uk.mjs';
import { resolveWithinRoot } from './troubleshoot-lib/security.mjs';

const MAX_FQCN_MATCHES = 10;
const MAX_GREP_HITS = 20;
const MAX_DEPTH = 12;
const DEFAULT_CONTEXT = 40;
const MAX_CONTEXT = 80;
const SKIP_DIRS = new Set(['.git', 'target', 'build', 'node_modules', '.idea', 'out', 'dist', 'logs']);
const FQCN_RE = /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$/;
const METHOD_RE = /\b(public|private|protected)\b[^;={]*\(/;

/** code_include 前缀（cdm-biz/**）→ 仓内一级目录集合。 */
function includeRoots(root, includes) {
  if (!Array.isArray(includes) || includes.length === 0) return [root];
  const dirs = [
    ...new Set(
      includes
        .map((g) => String(g).split('/')[0])
        .filter(Boolean)
        .map((d) => path.join(root, d)),
    ),
  ].filter((p) => fs.existsSync(p));
  return dirs.length > 0 ? dirs : [root];
}

function* walkJava(roots, depth = 0) {
  if (depth > MAX_DEPTH) return;
  for (const root of roots) {
    let entries;
    try {
      entries = fs.readdirSync(root, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const e of entries) {
      if (e.name.startsWith('.')) continue;
      const full = path.join(root, e.name);
      if (e.isDirectory()) {
        if (SKIP_DIRS.has(e.name)) continue;
        yield* walkJava([full], depth + 1);
      } else if (e.isFile() && e.name.endsWith('.java')) {
        yield full;
      }
    }
  }
}

function readPackage(file) {
  const text = fs.readFileSync(file, 'utf8');
  const m = text.match(/^\s*package\s+([A-Za-z_][\w.]*)\s*;/m);
  return m ? m[1] : null;
}

function gitHead(root) {
  // git 自行向父目录找仓库根；非 git 树快速返回 128，省略即可。
  const r = spawnSync('/usr/bin/git', ['-C', root, 'rev-parse', '--short', 'HEAD'], { encoding: 'utf8' });
  return r.status === 0 ? r.stdout.trim() : undefined;
}

/** FQCN（可带 :line）解析为 {pkg, cls, line}；非法形态直接拒绝。 */
export function parseFqcn(raw) {
  let s = String(raw);
  let line;
  const colon = s.lastIndexOf(':');
  if (colon >= 0) {
    const tail = s.slice(colon + 1);
    if (/^\d+$/.test(tail)) {
      line = Number(tail);
      s = s.slice(0, colon);
    }
  }
  if (s.includes('..') || s.includes('/') || s.includes('\\') || s.includes('*') || !FQCN_RE.test(s)) {
    throw new UsageError(`非法 fqcn（只允许点分包名+类名，可附 :行号）: ${raw}`);
  }
  const idx = s.lastIndexOf('.');
  return { pkg: s.slice(0, idx), cls: s.slice(idx + 1), line };
}

function buildContext(file, line, contextLines) {
  const text = fs.readFileSync(file, 'utf8');
  const all = text.split('\n');
  if (!line) {
    const end = Math.min(all.length, 2 * MAX_CONTEXT + 1);
    return {
      targetLine: null,
      start: 1,
      end,
      lines: all.slice(0, end).map((t, i) => ({ n: i + 1, text: t })),
    };
  }
  const ctx = Math.min(Math.max(contextLines, 0), MAX_CONTEXT);
  const start = Math.max(1, line - ctx);
  const end = Math.min(all.length, line + ctx);
  let enclosingMethod;
  for (let i = Math.min(line, all.length) - 1; i >= 0; i--) {
    if (METHOD_RE.test(all[i])) {
      enclosingMethod = all[i].trim();
      break;
    }
  }
  return {
    targetLine: line,
    start,
    end,
    enclosingMethod,
    lines: all.slice(start - 1, end).map((t, i) => ({ n: start + i, text: t })),
  };
}

function lookupByFqcn({ rootReal, roots, fqcn, line, context }) {
  const want = parseFqcn(fqcn);
  const targetLine = line ?? want.line;
  const simpleFile = `${want.cls}.java`;
  const head = gitHead(rootReal);
  const matches = [];
  let truncated = false;
  for (const file of walkJava(roots)) {
    if (path.basename(file) !== simpleFile) continue;
    let pkg;
    try {
      pkg = readPackage(file);
    } catch {
      continue;
    }
    if (pkg !== want.pkg) continue;
    const real = resolveWithinRoot(rootReal, path.relative(rootReal, file));
    const ctx = buildContext(real, targetLine, context);
    matches.push({
      repoRelativePath: path.relative(rootReal, real),
      absolutePath: real,
      packageMatch: true,
      ...(head ? { gitHead: head } : {}),
      enclosingMethod: ctx.enclosingMethod,
      context: { targetLine: ctx.targetLine, start: ctx.start, end: ctx.end, lines: ctx.lines },
    });
    if (matches.length >= MAX_FQCN_MATCHES) {
      truncated = true;
      break;
    }
  }
  return { matches, truncated };
}

function grepFixed({ rootReal, roots, pattern }) {
  const excludeDirs = [...SKIP_DIRS].filter((d) => d !== '.idea').flatMap((d) => ['--exclude-dir', d]);
  const r = spawnSync('/usr/bin/grep', ['-rnF', '-I', '--include=*.java', ...excludeDirs, pattern, ...roots], {
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
  });
  // grep: 0=有命中, 1=无命中, 2=出错
  if (r.status !== 0 && r.status !== 1) {
    return { hits: [], truncated: false, grepError: String(r.stderr || 'grep 执行失败').slice(0, 300) };
  }
  const lines = (r.stdout || '').split('\n').filter(Boolean);
  const truncated = lines.length > MAX_GREP_HITS;
  const hits = [];
  for (const ln of lines.slice(0, MAX_GREP_HITS)) {
    const first = ln.indexOf(':');
    const second = ln.indexOf(':', first + 1);
    if (first < 0 || second < 0) continue;
    const file = ln.slice(0, first);
    const lineNo = Number(ln.slice(first + 1, second));
    const text = ln.slice(second + 1);
    let rel;
    try {
      const real = resolveWithinRoot(rootReal, path.relative(rootReal, file));
      rel = path.relative(rootReal, real);
    } catch {
      continue;
    }
    hits.push({
      file: rel,
      line: lineNo,
      text: text.length > 200 ? text.slice(0, 200) + `...[截断，原始 ${text.length} 字符]` : text,
    });
  }
  return { hits, truncated, ...(truncated ? { truncHint: '命中过多：请用 code_include 收窄 module 或加长 pattern' } : {}) };
}

/**
 * 主入口（spawn/git/fs 走真实环境；测试直接构造 tmp 仓调用）。
 * @returns 可 JSON 序列化对象，退出码挂不可枚举 _exitCode。
 */
export function runCodeLookup(argv, cfg) {
  let p;
  let resolved;
  try {
    p = parseNamed(argv, {
      uk: { required: true },
      mode: { required: true, enum: ['fqcn', 'grep'] },
      fqcn: {},
      line: { type: 'int', min: 1, max: 99999 },
      pattern: {},
      context: { type: 'int', min: 1, max: MAX_CONTEXT },
    });
    resolved = resolveUk(p.uk, cfg.uks);
  } catch (e) {
    return withExit(wrapError(e), 2);
  }

  const entry = cfg.uks[resolved.appUk];
  let rootReal;
  try {
    rootReal = fs.realpathSync(entry.code_root);
    fs.accessSync(rootReal, fs.constants.R_OK);
  } catch {
    return withExit(
      { error: 'CODE_ROOT_UNREADABLE', errorClass: 'CODE_ROOT_UNREADABLE', uk: p.uk, codeRoot: entry.code_root,
        hint: '该 uk 的代码仓未在本机检出或不可读；日志分析可继续，代码关联本轮跳过' },
      0,
    );
  }
  const roots = includeRoots(rootReal, entry.code_include);

  try {
    if (p.mode === 'fqcn') {
      if (!p.fqcn) throw new UsageError('mode=fqcn 必须提供 --fqcn（完整包名.类名，可附 :行号）');
      const ctxLines = p.context ?? DEFAULT_CONTEXT;
      const { matches, truncated } = lookupByFqcn({
        rootReal, roots, fqcn: p.fqcn, line: p.line, context: ctxLines,
      });
      if (matches.length === 0) {
        return withExit(
          { mode: 'fqcn', fqcn: p.fqcn, codeRoot: rootReal, matches: [],
            hint: '0 命中：核对包名/类名大小写，或该仓 code_include 是否覆盖目标 module' },
          0,
        );
      }
      return withExit(
        { mode: 'fqcn', uk: resolved.appUk, resolvedFrom: resolved.resolvedFrom, codeRoot: rootReal,
          fqcn: p.fqcn, requestedLine: p.line ?? parseFqcn(p.fqcn).line ?? null,
          include: entry.code_include ?? null, matches, truncated },
        0,
      );
    }

    if (!p.pattern || !String(p.pattern).trim()) throw new UsageError('mode=grep 必须提供非空 --pattern');
    if (p.pattern.length > 200) throw new UsageError('--pattern 超长（>200 字符），请用更精确的固定字符串');
    const r = grepFixed({ rootReal, roots, pattern: p.pattern });
    return withExit(
      { mode: 'grep', uk: resolved.appUk, resolvedFrom: resolved.resolvedFrom, codeRoot: rootReal,
        include: entry.code_include ?? null, pattern: p.pattern, ...r },
      0,
    );
  } catch (e) {
    return withExit(wrapError(e), 2);
  }
}

function wrapError(e) {
  if (e instanceof ConfigError) return { success: false, errorClass: e.kind, message: e.message, hint: e.hint };
  return { success: false, errorClass: 'USAGE', message: String(e?.message ?? e) };
}

function withExit(out, code) {
  Object.defineProperty(out, '_exitCode', { value: code, enumerable: false });
  return out;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  let cfg;
  try {
    cfg = loadConfig();
  } catch (e) {
    process.stdout.write(JSON.stringify(wrapError(e)));
    process.exit(2);
  }
  const out = runCodeLookup(process.argv.slice(2), cfg);
  process.stdout.write(JSON.stringify(out));
  process.exit(out._exitCode ?? 0);
}
