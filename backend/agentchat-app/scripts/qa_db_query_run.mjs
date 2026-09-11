// qa_db_query：经本机 docker 容器内 mysql 客户端，对 QA 只读库执行受限 SELECT。
// 硬边界：config.databases 只登记 QA 两库；SQL 只能由白名单标识符 + 过闸门 WHERE 拼成；
// 密码从仓库外 dbs.env 读取，只经 `docker exec -e MYSQL_PWD=...` 注入进程环境，
// 不进 argv 之外的任何输出（错误文本做 replaceAll 兜底脱敏）。
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { loadConfig, loadDbsEnv, ConfigError } from './troubleshoot-lib/config.mjs';
import { parseNamed, UsageError } from './troubleshoot-lib/argv.mjs';
import { assertIdentifier, assertIdentifierList, assertReadOnlyWhere } from './troubleshoot-lib/security.mjs';

const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 50;
const CELL_MAX_CHARS = 200;
const QUERY_TIMEOUT_MS = 25000;
const DEFAULT_CONTAINER = 'db-mysql-1';

export function buildSelect({ table, fields, where, limit }) {
  assertIdentifier(table);
  const f = fields?.trim() || '*';
  assertIdentifierList(f);
  const selectCols = f === '*' ? '*' : f.split(',').map((s) => `\`${s.trim()}\``).join(', ');
  let sql = `SELECT ${selectCols} FROM \`${table}\``;
  if (where) {
    assertReadOnlyWhere(where);
    sql += ` WHERE ${where.trim()}`;
  }
  sql += ` LIMIT ${Math.min(Math.max(limit, 1), MAX_LIMIT)}`;
  return sql;
}

function parseBatch(stdout) {
  const lines = String(stdout).split('\n').filter((l) => l.length > 0);
  if (lines.length === 0) return { columns: [], rows: [], cellTruncated: 0 };
  const columns = lines[0].split('\t');
  let cellTruncated = 0;
  const rows = lines.slice(1).map((line) => {
    const cells = line.split('\t');
    const row = {};
    columns.forEach((c, i) => {
      let v = cells[i] === undefined ? '' : cells[i];
      if (v === 'NULL') {
        v = null;
      } else if (v.length > CELL_MAX_CHARS) {
        // 预留标注长度，保证截断后总长仍 ≤ CELL_MAX_CHARS
        v = v.slice(0, CELL_MAX_CHARS - 24) + `...[单元格截断，原始 ${v.length} 字符]`;
        cellTruncated++;
      }
      row[c] = v;
    });
    return row;
  });
  return { columns, rows, cellTruncated };
}

function classifyDbError(stderr, pwd) {
  const safe = String(stderr ?? '').replaceAll(pwd, '***');
  if (/Access denied|using password|1045/i.test(safe)) return { errorClass: 'DB_AUTH_ERROR', message: safe.trim().slice(0, 500) };
  if (/Unknown table|doesn'?t exist|1146|Unknown column|1054/i.test(safe))
    return { errorClass: 'DB_BAD_TABLE', message: safe.trim().slice(0, 500) };
  if (/Can'?t connect|timed? ?out|Connection refused|Name or service not known|HY000/i.test(safe))
    return { errorClass: 'DB_UNAVAILABLE', message: safe.trim().slice(0, 500) };
  return { errorClass: 'DB_ERROR', message: safe.trim().slice(0, 500) || 'mysql 非零退出且无 stderr' };
}

/**
 * @param creds dbs.env 解析结果（测试注入）
 * @param spawnFn (bin,args,opts)=>{status,stdout,stderr,error?}（测试注入）
 */
export function runQaDb(argv, cfg, creds, spawnFn) {
  let p;
  let dbc;
  let sql;
  try {
    p = parseNamed(argv, {
      db: { required: true },
      table: { required: true },
      fields: {},
      where: {},
      limit: { type: 'int', min: 1 }, // 超 MAX_LIMIT 在 buildSelect 静默夹到硬上限
    });
    dbc = cfg.databases?.[p.db];
    if (!dbc) {
      throw new UsageError(
        `未知 --db：${p.db}（已登记 ${Object.keys(cfg.databases ?? {}).join('、')}；本工具只允许 QA 只读库）`,
      );
    }
    sql = buildSelect({ table: p.table, fields: p.fields, where: p.where, limit: p.limit ?? DEFAULT_LIMIT });
  } catch (e) {
    return withExit(wrapError(e), 2);
  }

  const pwd = creds?.[dbc.passwordEnv];
  if (!pwd) {
    return withExit(
      { success: false, errorClass: 'DB_CREDENTIAL_MISSING', message: `缺少凭据 ${dbc.passwordEnv}`,
        hint: '请在 ~/.ai-agent/troubleshoot/dbs.env 填写该只读账号密码（仓库外，勿提交）' },
      2,
    );
  }

  const dockerBin = cfg.mysql?.dockerBin || '/usr/local/bin/docker';
  const container = cfg.mysql?.container || DEFAULT_CONTAINER;
  const args = [
    'exec', '-i', '-e', `MYSQL_PWD=${pwd}`, container,
    'mysql', '-h', dbc.host, '-P', String(dbc.port), '-u', dbc.user,
    '--batch', '--raw', '--default-character-set=utf8mb4',
    dbc.database, '-e', sql,
  ];

  let r;
  try {
    r = spawnFn(dockerBin, args, {
      encoding: 'utf8',
      timeout: QUERY_TIMEOUT_MS,
      env: { PATH: '/usr/bin:/bin', LANG: process.env.LANG || 'C' },
    });
  } catch (e) {
    return withExit({ success: false, errorClass: 'DB_UNAVAILABLE', message: String(e?.message ?? e) }, 2);
  }

  if (r?.error) {
    return withExit({ success: false, errorClass: 'DB_UNAVAILABLE', message: String(r.error.message || r.error) }, 2);
  }
  if (r.status !== 0) {
    return withExit({ success: false, sql, ...classifyDbError(r.stderr, pwd) }, 2);
  }

  const { columns, rows, cellTruncated } = parseBatch(r.stdout);
  return withExit(
    {
      db: p.db,
      table: p.table,
      sql,
      columns,
      rows,
      rowCount: rows.length,
      truncated: rows.length >= Math.min(p.limit ?? DEFAULT_LIMIT, MAX_LIMIT),
      ...(cellTruncated ? { cellTruncated } : {}),
    },
    0,
  );
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
  let creds;
  try {
    cfg = loadConfig();
    creds = loadDbsEnv();
  } catch (e) {
    process.stdout.write(JSON.stringify(wrapError(e)));
    process.exit(2);
  }
  const out = runQaDb(process.argv.slice(2), cfg, creds, (bin, args, opts) =>
    spawnSync(bin, args, opts));
  process.stdout.write(JSON.stringify(out));
  process.exit(out._exitCode ?? 0);
}
