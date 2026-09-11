import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { buildQuery, runQuery, compressSkyeyeJson } from '../../skyeye_query_run.mjs';
import { UsageError } from '../argv.mjs';

// T1：uk 新协议（首查强制单 uk / expand=1 拼 1 跳组 / env 白名单）+ appUks 旧路径兼容
// + meta 注入 + 压缩契约字段名不变。spawn 全部 mock，不触达真实 SkyEye。

const cfg = {
  version: 1,
  logEnvs: ['qa', 'uat', 'product'],
  skyeye: { tsEntry: '/tmp/skyeye.ts', nodeBin: '/tmp/node' },
  uks: {
    'titc.java.drp.switch': {
      display_name: 'switch',
      related_uks: ['titc.java.dsf.drp.order', 'titc.java.dsf.drp'],
    },
    'titc.java.dsf.drp.order': { display_name: 'dsf.order', related_uks: [] },
    'titc.java.dsf.drp': { display_name: 'dsf', related_uks: [] },
    'titc.java.hub': { display_name: 'hub', related_uks: [] },
  },
};
// 给 hub 挂 10 个反向邻居，验枢纽警告
for (let i = 0; i < 10; i++) cfg.uks[`titc.java.h${i}`] = { related_uks: ['titc.java.hub'] };

const base = (extra = []) => [
  '--env', 'qa',
  '--minutes', '30',
  '--indexContext', 'SH20260911001',
  ...extra,
];

describe('buildQuery - 首查（无 expand）', () => {
  test('简称解析后只传单 uk，不拼邻居', () => {
    const q = buildQuery(base(['--uk', 'drp.switch']), cfg);
    assert.deepEqual(q.appUks, ['titc.java.drp.switch']);
    assert.equal(q.meta.resolvedUk, 'titc.java.drp.switch');
    assert.equal(q.meta.resolvedFrom, 'suffix');
    assert.equal(q.meta.neighborCount, 0);
  });

  test('白名单 argv 透传：env/minutes/indexContext，未知时间键不进 argv', () => {
    const q = buildQuery(base(['--uk', 'drp.switch', '--pageSize', '10', '--priority', 'ERROR']), cfg);
    const flat = q.skyeyeArgs;
    assert.deepEqual(flat.slice(0, 1), ['queryLog']);
    assert.ok(flat.includes('--appUks'));
    assert.equal(flat[flat.indexOf('--appUks') + 1], 'titc.java.drp.switch');
    assert.equal(flat[flat.indexOf('--env') + 1], 'qa');
    assert.equal(flat[flat.indexOf('--minutes') + 1], '30');
    assert.equal(flat[flat.indexOf('--indexContext') + 1], 'SH20260911001');
    assert.equal(flat[flat.indexOf('--pageSize') + 1], '10');
    assert.equal(flat[flat.indexOf('--priority') + 1], 'ERROR');
  });

  test('env=stage 拒绝（白名单只有 qa/uat/product）', () => {
    assert.throws(() => buildQuery(['--uk', 'drp.switch', '--env', 'stage', '--minutes', '30', '--indexContext', 'x'], cfg), /env/);
  });

  test('uk/appUks 都不给：UsageError', () => {
    assert.throws(() => buildQuery(base(), cfg), UsageError);
  });

  test('minutes 与 begin/end 同时给：拒绝', () => {
    assert.throws(
      () => buildQuery(base(['--uk', 'drp.switch', '--begin', '2026-09-11 10:00:00.000', '--end', '2026-09-11 10:10:00.000']), cfg),
      /时间|minutes|begin/,
    );
  });

  test('begin 给了 end 没给：拒绝', () => {
    assert.throws(
      () => buildQuery(['--uk', 'drp.switch', '--env', 'qa', '--begin', '2026-09-11 10:00:00.000', '--indexContext', 'x'], cfg),
      /begin|end|时间/,
    );
  });

  test('indexContext 与 contextId 同时给：拒绝', () => {
    assert.throws(
      () => buildQuery(base(['--uk', 'drp.switch', '--contextId', 'abc']), cfg),
      /indexContext|contextId|查询键/,
    );
  });
});

describe('buildQuery - expand=1 精查', () => {
  const focus = (extra = []) => [
    '--uk', 'drp.switch', '--env', 'qa',
    '--begin', '2026-09-11 10:00:00.000', '--end', '2026-09-11 10:10:00.000',
    '--contextId', 'ctx-123', '--expand', '1', ...extra,
  ];

  test('拼起始 uk + 1 跳无向邻居（fwd 顺序，起点在首位）', () => {
    const q = buildQuery(focus(), cfg);
    assert.deepEqual(q.appUks, [
      'titc.java.drp.switch',
      'titc.java.dsf.drp.order',
      'titc.java.dsf.drp',
    ]);
    assert.equal(q.meta.neighborCount, 2);
  });

  test('expand=1 没有 contextId：拒绝', () => {
    assert.throws(
      () => buildQuery(['--uk', 'drp.switch', '--env', 'qa', '--minutes', '30', '--indexContext', 'x', '--expand', '1'], cfg),
      /contextId/,
    );
  });

  test('expand 只能是 1', () => {
    assert.throws(() => buildQuery(focus(['--expand', '2']), cfg), /expand/);
  });

  test('枢纽 uk（>8 邻居）：组不截断 + hubWarning', () => {
    const q = buildQuery([
      '--uk', 'hub', '--env', 'qa', '--minutes', '30',
      '--contextId', 'c', '--expand', '1',
    ], cfg);
    assert.equal(q.appUks.length, 11);
    assert.match(q.meta.hubWarning, /枢纽|hub/i);
  });
});

describe('buildQuery - appUks 旧路径', () => {
  test('完整 appUks 原样透传，不做简称解析、不加 resolvedUk', () => {
    const q = buildQuery(base(['--appUks', 'titc.java.a,titc.java.b']), cfg);
    assert.equal(q.rawAppUks, 'titc.java.a,titc.java.b');
    assert.deepEqual(q.appUks, ['titc.java.a', 'titc.java.b']);
    assert.equal(q.meta, undefined);
  });

  test('uk 与 appUks 同时给：拒绝（二选一）', () => {
    assert.throws(() => buildQuery(base(['--uk', 'drp.switch', '--appUks', 'titc.java.a']), cfg), UsageError);
  });
});

describe('runQuery - 执行与输出', () => {
  test('用 cfg.skyeye 的 nodeBin/tsEntry 启动；成功时输出压缩契约 + meta', () => {
    const calls = [];
    const spawn = (bin, args) => {
      calls.push({ bin, args });
      return {
        status: 0,
        stdout: JSON.stringify({
          success: true,
          code: 0,
          result: {
            count: 2,
            list: [
              { logTime: '2026-09-11 10:00:02.000', priority: 3, appUk: 'titc.java.drp.switch', contextId: 'c2', msg: 'later' },
              { logTime: '2026-09-11 10:00:01.000', priority: 1, appUk: 'titc.java.drp.switch', contextId: 'c1', msg: 'earlier' },
            ],
          },
        }),
        stderr: '',
      };
    };
    const out = runQuery(base(['--uk', 'drp.switch']), cfg, spawn);
    assert.equal(calls[0].bin, '/tmp/node');
    assert.equal(calls[0].args[0], '--experimental-strip-types');
    assert.equal(calls[0].args[1], '/tmp/skyeye.ts');
    assert.equal(out.success, true);
    assert.deepEqual(out.result.logs.map((l) => l.time), [
      '2026-09-11 10:00:01.000',
      '2026-09-11 10:00:02.000',
    ]);
    assert.deepEqual(Object.keys(out.result.logs[0]).sort(), ['appUk', 'contextId', 'level', 'msg', 'time']);
    assert.equal(out.result.logs[0].level, 'ERROR');
    assert.equal(out.resolvedUk, 'titc.java.drp.switch');
    assert.equal(out.neighborCount, 0);
  });

  test('code=1 账号信息为空：errorClass=AUTH_EXPIRED 包装', () => {
    const spawn = () => ({
      status: 0,
      stdout: JSON.stringify({ success: false, code: 1, message: '账号信息为空' }),
      stderr: '',
    });
    const out = runQuery(base(['--uk', 'drp.switch']), cfg, spawn);
    assert.equal(out.success, false);
    assert.equal(out.errorClass, 'AUTH_EXPIRED');
    assert.match(out.hint, /skyeye auth login/);
  });

  test('429 原文：RATE_LIMITED', () => {
    const spawn = () => ({ status: 1, stdout: '失败: 日志查询接口返回状态码 429', stderr: '' });
    const out = runQuery(base(['--uk', 'drp.switch']), cfg, spawn);
    assert.equal(out.errorClass, 'RATE_LIMITED');
  });

  test('参数层 UsageError：errorClass=USAGE', () => {
    const out = runQuery(['--env', 'qa', '--minutes', '30', '--indexContext', 'x'], cfg, () => {
      throw new Error('spawn should not be called');
    });
    assert.equal(out.success, false);
    assert.equal(out.errorClass, 'USAGE');
  });

  test('简称歧义：UK_AMBIGUOUS + candidates', () => {
    const c2 = { ...cfg, uks: { ...cfg.uks, 'titc.java.pms.openapi.switch': { display_name: '开放平台开关', related_uks: [] } } };
    const out = runQuery(base(['--uk', 'switch']), c2, () => { throw new Error('no'); });
    assert.equal(out.errorClass, 'UK_AMBIGUOUS');
    assert.ok(out.candidates.length >= 2);
  });
});

describe('compressSkyeyeJson', () => {
  test('字段名契约不变：success/code 顶层保留，result={count,returned,logs[]}', () => {
    const out = compressSkyeyeJson({
      success: true,
      code: 0,
      message: 'ok',
      result: { count: 1, list: [{ logTime: 't', priority: 2, appUk: 'u', contextId: 'c', msg: 'm' }], extraNoise: 1 },
    });
    assert.equal(out.success, true);
    assert.equal(out.code, 0);
    assert.equal(out.result.count, 1);
    assert.equal(out.result.returned, 1);
    assert.equal(out.result.logs[0].level, 'WARN');
  });
});
