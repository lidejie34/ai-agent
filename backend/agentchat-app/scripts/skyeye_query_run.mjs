// skyeye queryLog 包装 + 输出压缩：执行器以净化环境运行本文件（node 绝对路径，由 .sh exec）。
// 职责：
//  1) 白名单解析 argv（未声明参数直接报错，防止被 skyeye CLI 静默丢弃造成假空命中）；
//  2) 新协议 --uk：简称解析 + 首查强制单 uk；--expand=1 必须配合 --contextId，拼无向 1 跳组；
//  3) 旧协议 --appUks：完整 uk 逗号串原样透传，不做解析；
//  4) 成功 JSON 压缩为排障所需字段；错误响应归类（auth/限流/坏 uk/uk 组过大），hint 直接指导下一步。
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { loadConfig, ConfigError } from './troubleshoot-lib/config.mjs';
import { parseNamed, requireExactlyOne, UsageError } from './troubleshoot-lib/argv.mjs';
import { resolveUk, neighborGroup, UkAmbiguousError, UkNotFoundError } from './troubleshoot-lib/uk.mjs';
import { classifySkyeyeResult } from './troubleshoot-lib/skyeye-error.mjs';

const MSG_MAX_CHARS = 1500;
const SPAWN_TIMEOUT_MS = 50000;
const PRIORITY_NAME = { 0: 'FATAL', 1: 'ERROR', 2: 'WARN', 3: 'INFO', 4: 'DEBUG' };

/**
 * 纯函数：解析参数 + 组装 CLI argv + uk 解析/扩圈。
 * @returns {{appUks:string[], rawAppUks?:string, skyeyeArgs:string[], meta?:object}}
 */
export function buildQuery(argv, cfg) {
  const p = parseNamed(argv, {
    uk: {},
    appUks: {},
    env: { required: true, enum: cfg.logEnvs },
    minutes: { type: 'int', min: 1, max: 1440 },
    begin: {},
    end: {},
    indexContext: {},
    contextId: {},
    expand: { type: 'int', enum: [1] },
    priority: { enum: ['INFO', 'WARN', 'ERROR'] },
    pageSize: { type: 'int', min: 1, max: 50 },
  });

  requireExactlyOne(p, ['uk', 'appUks'], '应用参数（--uk / --appUks）');

  const hasMin = p.minutes !== undefined;
  const hasBegin = Boolean(p.begin || p.end);
  if (Boolean(p.begin) !== Boolean(p.end)) throw new UsageError('--begin 与 --end 必须成对提供');
  if (hasMin && hasBegin) throw new UsageError('时间窗二选一：--minutes 或 --begin/--end，不能同时给');
  if (!hasMin && !hasBegin) throw new UsageError('必须提供时间窗：--minutes N 或 --begin/--end');

  requireExactlyOne(p, ['indexContext', 'contextId'], '查询键（--indexContext / --contextId）');
  if (p.expand === 1 && !p.contextId) {
    throw new UsageError('--expand=1（精查扩圈）必须同时提供 --contextId；首查请先单 uk + --indexContext 派生 contextId');
  }

  let appUks;
  let meta;
  if (p.uk) {
    const r = resolveUk(p.uk, cfg.uks);
    if (p.expand === 1) {
      const g = neighborGroup(r.appUk, cfg.uks);
      appUks = [r.appUk, ...g.members];
      meta = {
        resolvedUk: r.appUk,
        resolvedFrom: r.resolvedFrom,
        neighborCount: g.neighborCount,
        hubWarning: g.hub
          ? `枢纽 uk：1 跳组共 ${g.neighborCount} 个邻居，若 SkyEye 报部分失败需逐步减半并说明实际查询范围`
          : undefined,
      };
    } else {
      appUks = [r.appUk];
      meta = { resolvedUk: r.appUk, resolvedFrom: r.resolvedFrom, neighborCount: 0 };
    }
  } else {
    appUks = String(p.appUks)
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    if (appUks.length === 0) throw new UsageError('--appUks 为空');
  }

  const args = ['queryLog', '--appUks', appUks.join(','), '--env', p.env];
  if (hasMin) args.push('--minutes', String(p.minutes));
  else args.push('--begin', p.begin, '--end', p.end);
  args.push(p.indexContext !== undefined ? '--indexContext' : '--contextId', p.indexContext ?? p.contextId);
  if (p.priority) args.push('--priority', p.priority);
  if (p.pageSize !== undefined) args.push('--pageSize', String(p.pageSize));

  return { appUks, rawAppUks: p.appUks, skyeyeArgs: args, meta };
}

/** 成功 JSON 压缩契约：顶层字段保留，result 压缩为 {count,returned,logs[]}。 */
export function compressSkyeyeJson(d) {
  const out = {};
  for (const k of Object.keys(d)) {
    if (k !== 'result') out[k] = d[k];
  }
  const res = d.result;
  if (res && typeof res === 'object' && Array.isArray(res.list)) {
    const clip = (s) => {
      const t = s == null ? '' : String(s);
      return t.length <= MSG_MAX_CHARS
        ? t
        : t.slice(0, MSG_MAX_CHARS) + `...[msg 截断，原始长度 ${t.length} 字符]`;
    };
    const logs = res.list.map((l) => ({
      time: l.logTime,
      level: PRIORITY_NAME[l.priority] ?? String(l.priority),
      appUk: l.appUk,
      contextId: l.contextId,
      msg: clip(l.msg),
    }));
    logs.sort((a, b) => (a.time < b.time ? -1 : a.time > b.time ? 1 : 0));
    out.result = { count: res.count, returned: logs.length, logs };
  } else {
    out.result = res;
  }
  return out;
}

function errorEnvelope(e) {
  if (e instanceof UkAmbiguousError) {
    return { success: false, errorClass: 'UK_AMBIGUOUS', message: e.message, candidates: e.candidates };
  }
  if (e instanceof UkNotFoundError) {
    return { success: false, errorClass: 'UK_NOT_FOUND', message: e.message, nearest: e.nearest };
  }
  if (e instanceof ConfigError) {
    return { success: false, errorClass: e.kind, message: e.message, hint: e.hint };
  }
  // UsageError 及未预期参数错误
  return { success: false, errorClass: 'USAGE', message: String(e?.message ?? e) };
}

/**
 * 组装 → 执行 → 压缩/分类。spawnFn 可注入（测试 mock）。
 * @returns {object} 始终返回可 JSON 序列化对象；退出码挂在不可枚举的 _exitCode 上。
 */
export function runQuery(argv, cfg, spawnFn) {
  let q;
  try {
    q = buildQuery(argv, cfg);
  } catch (e) {
    return withExit(errorEnvelope(e), 2);
  }

  const r = spawnFn(cfg.skyeye.nodeBin, ['--experimental-strip-types', cfg.skyeye.tsEntry, ...q.skyeyeArgs], {
    encoding: 'utf8',
  });
  if (r?.stderr) process.stderr.write(r.stderr);
  if (r?.error) {
    return withExit(
      { success: false, errorClass: 'SPAWN_FAILED', message: String(r.error.message || r.error) },
      2,
    );
  }

  const raw = r?.stdout ?? '';
  let parsed = null;
  try {
    parsed = JSON.parse(raw);
  } catch {
    parsed = null;
  }

  const cls = classifySkyeyeResult({ parsed, raw });
  if (cls) {
    if (cls.errorClass === 'EMPTY' && parsed) {
      // 空命中不是错误：保留 success=true 压缩结果，附扩圈提示。
      const out = compressSkyeyeJson(parsed);
      out.notice = { kind: 'EMPTY', hint: cls.hint };
      attachMeta(out, q);
      return withExit(out, r.status ?? 0);
    }
    return withExit({ success: false, ...cls }, r.status ?? 2);
  }

  if (parsed) {
    const out = compressSkyeyeJson(parsed);
    attachMeta(out, q);
    return withExit(out, r.status ?? 0);
  }

  // 非 JSON 且未命中任何已知错误特征：原样透传（保留旧行为，便于排查 CLI 本身问题）。
  return withExit({ success: false, errorClass: 'REMOTE_ERROR', message: raw || '(空输出)' }, r.status ?? 2);
}

function attachMeta(out, q) {
  if (q.meta) {
    for (const [k, v] of Object.entries(q.meta)) {
      if (v !== undefined) out[k] = v;
    }
  }
}

function withExit(out, code) {
  Object.defineProperty(out, '_exitCode', { value: code, enumerable: false });
  return out;
}

// ---- 脚本入口（被 import 时不执行，单测 mock spawn）----
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  let cfg;
  try {
    cfg = loadConfig();
  } catch (e) {
    process.stdout.write(JSON.stringify(errorEnvelope(e)));
    process.exit(2);
  }
  const realSpawn = (bin, args) =>
    spawnSync(bin, args, { encoding: 'utf8', env: process.env, timeout: SPAWN_TIMEOUT_MS });
  const out = runQuery(process.argv.slice(2), cfg, realSpawn);
  process.stdout.write(JSON.stringify(out));
  process.exit(out._exitCode ?? 0);
}
