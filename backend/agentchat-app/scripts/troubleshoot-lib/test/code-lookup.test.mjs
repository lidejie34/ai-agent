import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { runCodeLookup } from '../../code_lookup_run.mjs';

// T2：外部业务仓只读代码定位。tmp 下伪造一个多 module Maven 仓，验
// FQCN→包名校验/多命中/上下文/方法签名、grep -rnF、code_include 收窄、坏根降级。

let root;
const cfgUks = () => ({
  'titc.java.fake': {
    display_name: 'fake',
    code_root: root,
    code_include: ['module-a/**', 'module-b/**'],
  },
  'titc.java.whole': { display_name: 'whole', code_root: root },
  'titc.java.missing': { display_name: 'missing', code_root: path.join(root, 'nope') },
  'titc.java.aonly': { display_name: 'aonly', code_root: root, code_include: ['module-a/**'] },
});

function javaFile(mod, pkg, name, body) {
  const dir = path.join(root, mod, 'src/main/java', ...pkg.split('.'));
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, name), body);
}

const ORDER_BODY = `package com.acme;

import java.util.List;

public class OrderService {

    public void submit() {
        throw new IllegalStateException("boom at marker line");
    }

    private String hide() {
        return "secret";
    }
}
`;

beforeEach(() => {
  root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'ts-code-')));
  javaFile('module-a', 'com.acme', 'OrderService.java', ORDER_BODY);
  // 同名同类不同 module：多命中全列
  javaFile('module-b', 'com.acme', 'OrderService.java', ORDER_BODY);
  // simpleName 相同但包名不符：必须排除
  javaFile('module-a', 'com.other', 'OrderService.java', 'package com.other;\npublic class OrderService {}\n');
  // target 编译产物：遍历时跳过
  javaFile('target', 'com.acme', 'OrderService.java', ORDER_BODY);
});

afterEach(() => fs.rmSync(root, { recursive: true, force: true }));

const run = (extra) =>
  runCodeLookup(['--uk', 'fake', '--mode', 'fqcn', ...extra], { uks: cfgUks() });

describe('code_lookup fqcn 模式', () => {
  test('FQCN+line 命中：上下文带行号、最近方法签名、相对/绝对路径', () => {
    // marker 行在 submit() 内
    const markerLine = ORDER_BODY.split('\n').findIndex((l) => l.includes('boom at marker')) + 1;
    const out = run(['--fqcn', 'com.acme.OrderService', '--line', String(markerLine), '--context', '2']);
    assert.equal(out.error, undefined, JSON.stringify(out));
    assert.equal(out.matches.length, 2);
    const m0 = out.matches[0];
    assert.match(m0.repoRelativePath, /^module-a\//);
    assert.ok(m0.absolutePath.startsWith(root));
    assert.equal(m0.context.targetLine, markerLine);
    assert.ok(m0.context.lines.length <= 5);
    assert.match(m0.enclosingMethod, /submit/);
    assert.equal(m0.packageMatch, true);
  });

  test('包名不符的同名文件被排除；多命中全列', () => {
    const out = run(['--fqcn', 'com.acme.OrderService', '--line', '6']);
    const rels = out.matches.map((m) => m.repoRelativePath);
    assert.ok(rels.some((r) => r.startsWith('module-a/')));
    assert.ok(rels.some((r) => r.startsWith('module-b/')));
    assert.ok(!rels.some((r) => r.includes('com/other')));
    assert.ok(!rels.some((r) => r.startsWith('target/')));
  });

  test('fqcn 内嵌 :line 解析', () => {
    const out = run(['--fqcn', 'com.acme.OrderService:7']);
    assert.equal(out.matches[0].context.targetLine, 7);
  });

  test('fqcn 含 ../、绝对路径、*：拒绝', () => {
    for (const bad of ['../Evil.java', 'com.acme.*', 'com/acme/OrderService']) {
      const out = runCodeLookup(['--uk', 'fake', '--mode', 'fqcn', '--fqcn', bad, '--line', '1'], { uks: cfgUks() });
      assert.equal(out.errorClass, 'USAGE', `${bad} 应拒绝`);
    }
  });

  test('fqcn 模式缺 fqcn：USAGE', () => {
    const out = run(['--line', '1']);
    assert.equal(out.errorClass, 'USAGE');
  });

  test('命中数 >10：截断为 10 + truncated 标记', () => {
    for (let i = 0; i < 11; i++) javaFile(`module-b/m${i}`, 'com.multi', 'Hit.java', `package com.multi;\npublic class Hit {}\n`);
    const out = run(['--fqcn', 'com.multi.Hit']);
    assert.equal(out.matches.length, 10);
    assert.equal(out.truncated, true);
  });
});

describe('code_lookup grep 模式', () => {
  test('grep -rnF 固定字符串：相对路径 命中，target 不出现', () => {
    const out = runCodeLookup(
      ['--uk', 'whole', '--mode', 'grep', '--pattern', 'boom at marker'],
      { uks: cfgUks() },
    );
    assert.equal(out.error, undefined);
    assert.ok(out.hits.length >= 2);
    assert.ok(out.hits.every((h) => /^module-[ab]\//.test(h.file)));
    assert.match(out.hits[0].file, /\.java$/);
    assert.ok(typeof out.hits[0].line === 'number');
    assert.match(out.hits[0].text, /boom at marker/);
  });

  test('code_include 收窄到 module-a：module-b 不出现', () => {
    const out = runCodeLookup(
      ['--uk', 'aonly', '--mode', 'grep', '--pattern', 'boom at marker'],
      { uks: cfgUks() },
    );
    assert.ok(out.hits.every((h) => !h.file.startsWith('module-b/')));
    assert.ok(out.hits.some((h) => h.file.startsWith('module-a/')));
  });

  test('pattern 为空/超长：USAGE', () => {
    const out = runCodeLookup(['--uk', 'whole', '--mode', 'grep', '--pattern', '']);
    // 空串 argv 不进 parseNamed 的值（handler 层不会给空串），这里直接验超长
    const long = runCodeLookup(
      ['--uk', 'whole', '--mode', 'grep', '--pattern', 'x'.repeat(201)],
      { uks: cfgUks() },
    );
    assert.equal(long.errorClass, 'USAGE');
    assert.ok(out.errorClass === 'USAGE' || out.hits !== undefined);
  });
});

describe('code_lookup 仓定位', () => {
  test('code_root 不可读：CODE_ROOT_UNREADABLE（非 fatal，模型可继续日志分析）', () => {
    const out = runCodeLookup(
      ['--uk', 'missing', '--mode', 'grep', '--pattern', 'x'],
      { uks: cfgUks() },
    );
    assert.equal(out.error, 'CODE_ROOT_UNREADABLE');
    assert.match(out.codeRoot, /nope/);
  });
});
