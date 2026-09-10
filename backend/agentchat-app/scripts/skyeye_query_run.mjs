// skyeye queryLog 包装 + 输出压缩：执行器以净化环境运行本文件（node 绝对路径，由 .sh exec）。
// 职责：原样跑 skyeye.ts queryLog，成功 JSON 压缩为排障所需字段（去掉 20+ 噪声字段、
// 堆栈/超长 msg 截断），非 JSON 或错误响应原样透传（auth 过期/限流/部分失败等错误分类依赖原文）。
import { spawnSync } from 'node:child_process';

const SKYEYE_TS = '/Users/lidejie/.agents/skills/skyeye/scripts/skyeye.ts';
const MSG_MAX_CHARS = 1500;
const PRIORITY_NAME = { 0: 'FATAL', 1: 'ERROR', 2: 'WARN', 3: 'INFO', 4: 'DEBUG' };

const r = spawnSync(
  process.execPath,
  ['--experimental-strip-types', SKYEYE_TS, 'queryLog', ...process.argv.slice(2)],
  { encoding: 'utf8', env: process.env },
);
if (r.stderr) process.stderr.write(r.stderr);
const raw = r.stdout ?? '';

let d;
try {
  d = JSON.parse(raw);
} catch {
  process.stdout.write(raw);
  process.exit(r.status ?? 0);
}

// 保留 success/code/message 等顶层字段，仅压缩 result
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
  logs.sort((a, b) => (a.time < b.time ? -1 : a.time > b.time ? 1 : 0)); // 时间正序
  out.result = { count: res.count, returned: logs.length, logs };
} else {
  out.result = res;
}
process.stdout.write(JSON.stringify(out));
process.exit(r.status ?? 0);
