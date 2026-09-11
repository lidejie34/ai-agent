import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  resolveWithinRoot,
  assertIdentifier,
  assertIdentifierList,
  assertReadOnlyWhere,
  maskPii,
} from '../security.mjs';

// T0：路径 canonical 闸门 + 标识符白名单 + SELECT 只读闸门（黑名单/禁字符/长度）

describe('security.resolveWithinRoot', () => {
  let root, outside;
  beforeEach(() => {
    // macOS 上 /var 是 /private/var 的符号链接，先 canonical 化便于断言
    root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'ts-sec-')));
    outside = fs.mkdtempSync(path.join(os.tmpdir(), 'ts-sec-out-'));
    fs.mkdirSync(path.join(root, 'a/b'), { recursive: true });
    fs.writeFileSync(path.join(root, 'a/b/F.java'), 'x');
  });
  afterEach(() => {
    fs.rmSync(root, { recursive: true, force: true });
    fs.rmSync(outside, { recursive: true, force: true });
  });

  test('根内文件解析成功', () => {
    const p = resolveWithinRoot(root, 'a', 'b', 'F.java');
    assert.ok(p.startsWith(root));
  });

  test('.. 越界拒绝', () => {
    assert.throws(() => resolveWithinRoot(root, 'a', '../../etc/passwd'), /越界|escape/i);
  });

  test('绝对路径越界拒绝', () => {
    assert.throws(() => resolveWithinRoot(root, outside), /越界|escape/i);
  });

  test('符号链接指向根外：拒绝', () => {
    fs.writeFileSync(path.join(outside, 'secret.txt'), 'x');
    fs.symlinkSync(path.join(outside, 'secret.txt'), path.join(root, 'a/link.txt'));
    assert.throws(() => resolveWithinRoot(root, 'a', 'link.txt'), /越界|escape/i);
  });

  test('不存在的根内路径：返回 realpath 校验失败错误（不静默）', () => {
    assert.throws(() => resolveWithinRoot(root, 'a', 'nope.java'));
  });
});

describe('security.assertIdentifier', () => {
  test('合法标识符', () => {
    assert.doesNotThrow(() => assertIdentifier('order_info'));
    assert.doesNotThrow(() => assertIdentifier('_t1'));
  });
  test('数字开头/空格/点/分号拒绝', () => {
    assert.throws(() => assertIdentifier('1abc'));
    assert.throws(() => assertIdentifier('a b'));
    assert.throws(() => assertIdentifier('a.b'));
    assert.throws(() => assertIdentifier('a;DROP'));
  });
});

describe('security.assertIdentifierList', () => {
  test('* 通过', () => assert.doesNotThrow(() => assertIdentifierList('*')));
  test('逗号名单通过', () => assert.doesNotThrow(() => assertIdentifierList('id, order_no,status')));
  test('含表达式拒绝', () => assert.throws(() => assertIdentifierList('id, (select 1)')));
});

describe('security.assertReadOnlyWhere', () => {
  const ok = [
    "order_no = 'SH20260911001'",
    'hotel_id = 12345 AND status = 2',
    "create_time >= '2026-09-01 00:00:00'",
  ];
  for (const w of ok) test(`放行简单条件：${w}`, () => assert.doesNotThrow(() => assertReadOnlyWhere(w)));

  const bad = [
    "1=1; DROP TABLE t",
    "1=1 -- ",
    "1=1 /* c */",
    "1 OR 1=1 UNION SELECT password FROM user",
    "1; INSERT INTO t VALUES(1)",
    "1; update t set a=1",
    "1; delete FROM t",
    "1 INTO OUTFILE '/tmp/x'",
    "1 OR SLEEP(5)",
    "1 OR BENCHMARK(1000000,MD5(1))",
    "1 OR 1=1; CREATE TABLE x(a int)",
    "1 OR 1=1; TRUNCATE t",
    "1 OR 1=1; ALTER TABLE t ADD COLUMN c int",
    "1 OR 1=1; GRANT ALL ON *.* TO 'x'",
    "column LIKE '%information_schema%'",
    "name=CHAR(0x41)",
  ];
  for (const w of bad) test(`拒绝危险片段：${w}`, () => assert.throws(() => assertReadOnlyWhere(w)));

  test('超过 200 字符拒绝', () => {
    assert.throws(() => assertReadOnlyWhere('a=' + '1'.repeat(200)));
  });

  test('空/空白：拒绝（调用方应省略 where 而非传空）', () => {
    assert.throws(() => assertReadOnlyWhere('   '));
  });
});

describe('security.maskPii', () => {
  test('手机号掩码', () => {
    assert.match(maskPii('call 13812345678 now'), /138\*{4}5678/);
  });
  test('身份证掩码', () => {
    const out = maskPii('id 11010119900307123X ok');
    assert.ok(!out.includes('11010119900307123X'));
    assert.match(out, /\*{4}/);
  });
  test('无 PII 原文不变', () => {
    assert.equal(maskPii('order SH123 status 2'), 'order SH123 status 2');
  });
});
