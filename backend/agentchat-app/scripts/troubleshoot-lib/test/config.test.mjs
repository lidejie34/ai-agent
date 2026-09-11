import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { loadConfig, loadDbsEnv, ConfigError } from '../config.mjs';

// T0：配置加载/校验（缺文件引导、坏 JSON、悬空邻居/库绑定、dbs.env 解析）

describe('config.mjs', () => {
  let dir;
  beforeEach(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ts-cfg-'));
  });
  afterEach(() => fs.rmSync(dir, { recursive: true, force: true }));

  const cfgPath = () => path.join(dir, 'config.json');
  const writeCfg = (o) => fs.writeFileSync(cfgPath(), JSON.stringify(o));

  const minimal = () => ({
    version: 1,
    logEnvs: ['qa', 'uat', 'product'],
    skyeye: { tsEntry: '/tmp/skyeye.ts', nodeBin: '/opt/homebrew/bin/node' },
    mysql: { dockerBin: '/usr/local/bin/docker', container: 'db-mysql-1' },
    uks: {
      'titc.java.drp.switch': {
        display_name: 'switch',
        code_root: '/tmp/repo-a',
        related_uks: ['titc.java.dsf.drp'],
      },
      'titc.java.dsf.drp': { display_name: 'dsf', code_root: '/tmp/repo-b', related_uks: [] },
    },
    databases: {
      'TETitcDRP-qa': { host: 'h', port: 1, user: 'u', database: 'd', passwordEnv: 'P' },
    },
  });

  test('配置缺失：CONFIG_MISSING + 复制模板指引', () => {
    assert.throws(
      () => loadConfig(cfgPath()),
      (e) => e instanceof ConfigError && e.kind === 'CONFIG_MISSING' && /troubleshoot\.example\.json/.test(e.hint),
    );
  });

  test('坏 JSON：CONFIG_BAD_JSON', () => {
    fs.writeFileSync(cfgPath(), '{ not json');
    assert.throws(() => loadConfig(cfgPath()), (e) => e.kind === 'CONFIG_BAD_JSON');
  });

  test('最小合法配置加载成功', () => {
    writeCfg(minimal());
    const c = loadConfig(cfgPath());
    assert.equal(c.version, 1);
    assert.deepEqual(c.logEnvs, ['qa', 'uat', 'product']);
  });

  test('related_uks 指向未定义 uk：报错点名', () => {
    const c = minimal();
    c.uks['titc.java.drp.switch'].related_uks = ['titc.java.not.exist'];
    writeCfg(c);
    assert.throws(
      () => loadConfig(cfgPath()),
      (e) => e.kind === 'CONFIG_BAD_REFS' && /titc\.java\.not\.exist/.test(e.message),
    );
  });

  test('uk 的 database 键在 databases 中不存在：报错', () => {
    const c = minimal();
    c.uks['titc.java.dsf.drp'].database = 'GHOST-qa';
    writeCfg(c);
    assert.throws(() => loadConfig(cfgPath()), (e) => /GHOST-qa/.test(e.message));
  });

  test('logEnvs 为空：CONFIG_INVALID', () => {
    const c = minimal();
    c.logEnvs = [];
    writeCfg(c);
    assert.throws(() => loadConfig(cfgPath()), (e) => e.kind === 'CONFIG_INVALID');
  });

  test('loadDbsEnv：注释/空行/引号/空白正确解析', () => {
    fs.writeFileSync(
      path.join(dir, 'dbs.env'),
      '# comment\n\nA_PWD= abc123 \nB_PWD="x y"\nC_PWD=\n',
    );
    const env = loadDbsEnv(path.join(dir, 'dbs.env'));
    assert.equal(env.A_PWD, 'abc123');
    assert.equal(env.B_PWD, 'x y');
    assert.equal(env.C_PWD, '');
  });

  test('dbs.env 缺失：DBS_MISSING', () => {
    assert.throws(() => loadDbsEnv(path.join(dir, 'dbs.env')), (e) => e.kind === 'DBS_MISSING');
  });
});
