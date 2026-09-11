import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { parseNamed, UsageError, requireExactlyOne } from '../argv.mjs';

// T0：--k v 参数解析（白名单、必填、整型、互斥组）

const schema = {
  uk: { required: true },
  env: { required: true, enum: ['qa', 'uat', 'product'] },
  minutes: { type: 'int', min: 1, max: 1440 },
  begin: {},
  end: {},
  indexContext: {},
  contextId: {},
  expand: { type: 'int', enum: [1] },
};

describe('parseNamed', () => {
  test('正常解析具名参数', () => {
    const o = parseNamed(['--uk', 'drp.switch', '--env', 'qa', '--minutes', '30'], schema);
    assert.equal(o.uk, 'drp.switch');
    assert.equal(o.minutes, 30);
  });

  test('必填缺失：UsageError', () => {
    assert.throws(() => parseNamed(['--uk', 'x'], schema), (e) => e instanceof UsageError && /env/.test(e.message));
  });

  test('枚举外的值拒绝', () => {
    assert.throws(() => parseNamed(['--uk', 'x', '--env', 'stage'], schema), /env/);
  });

  test('schema 未声明参数拒绝（不下发给 CLI）', () => {
    assert.throws(
      () => parseNamed(['--uk', 'x', '--env', 'qa', '--startTime', '1'], schema),
      /startTime|未声明/,
    );
  });

  test('int 范围越界拒绝', () => {
    assert.throws(() => parseNamed(['--uk', 'x', '--env', 'qa', '--minutes', '9999'], schema), /minutes/);
    assert.throws(() => parseNamed(['--uk', 'x', '--env', 'qa', '--minutes', 'abc'], schema), /minutes/);
  });

  test('值里以 -- 开头不会被当作键吞掉', () => {
    const o = parseNamed(['--uk', '-weird', '--env', 'qa'], schema);
    assert.equal(o.uk, '-weird');
  });
});

describe('requireExactlyOne', () => {
  test('恰好一个通过', () => {
    assert.doesNotThrow(() => requireExactlyOne({ a: 1, b: null }, ['a', 'b'], '组'));
  });
  test('零个报错', () => {
    assert.throws(() => requireExactlyOne({ a: null }, ['a', 'b'], '组'), /组/);
  });
  test('多个报错', () => {
    assert.throws(() => requireExactlyOne({ a: 1, b: 2 }, ['a', 'b'], '组'), /组/);
  });
});
