import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { resolveUk, neighborGroup } from '../uk.mjs';

// T0：简称三级匹配 + 歧义/零候选 + 无向 1 跳邻居

const uks = {
  'titc.java.drp.switch': { display_name: 'OTA渠道入口（drp-restapi-switch）', related_uks: ['titc.java.dsf.drp'] },
  'titc.java.pms.openapi.switch': {
    display_name: '开放平台开关',
    related_uks: ['titc.java.dubbo.pms.order'],
  },
  'titc.java.dsf.drp': {
    display_name: 'drp.dsf',
    related_uks: ['titc.java.drp.switch', 'titc.java.drp.job'],
  },
  'titc.java.drp.job': { display_name: 'drp.job hub', related_uks: [] },
  'titc.java.dubbo.pms.order': { display_name: 'pms order dubbo', related_uks: [] },
  'titc.java.pms.bridge.order': { display_name: 'pms bridge order', related_uks: [] },
};

describe('resolveUk', () => {
  test('精确命中完整 uk', () => {
    const r = resolveUk('titc.java.drp.switch', uks);
    assert.equal(r.appUk, 'titc.java.drp.switch');
    assert.equal(r.resolvedFrom, 'exact');
  });

  test('唯一句点后缀：drp.switch → titc.java.drp.switch', () => {
    const r = resolveUk('drp.switch', uks);
    assert.equal(r.appUk, 'titc.java.drp.switch');
    assert.equal(r.resolvedFrom, 'suffix');
  });

  test('display_name 子串：开放 → pms.openapi.switch', () => {
    const r = resolveUk('开放', uks);
    assert.equal(r.appUk, 'titc.java.pms.openapi.switch');
    assert.equal(r.resolvedFrom, 'substring');
  });

  test('switch 同时命中 OTA开关与开放平台开关 → UkAmbiguousError 列候选', () => {
    assert.throws(
      () => resolveUk('switch', uks),
      (e) =>
        e.name === 'UkAmbiguousError' &&
        e.candidates.length === 2 &&
        e.candidates.some((c) => c.key === 'titc.java.drp.switch') &&
        e.candidates.some((c) => c.key === 'titc.java.pms.openapi.switch'),
    );
  });

  test('pms.order 同时命中 dubbo 与 bridge → 歧义', () => {
    assert.throws(() => resolveUk('pms.order', uks), (e) => e.name === 'UkAmbiguousError');
  });

  test('零命中：UkNotFoundError + nearest 提示（不臆造 appUk）', () => {
    assert.throws(
      () => resolveUk('cdm', uks),
      (e) => e.name === 'UkNotFoundError' && Array.isArray(e.nearest) && e.nearest.length <= 5,
    );
  });

  test('空输入拒绝', () => {
    assert.throws(() => resolveUk('  ', uks));
  });
});

describe('neighborGroup', () => {
  test('正向邻居：switch → [dsf.drp]', () => {
    const r = neighborGroup('titc.java.drp.switch', uks);
    assert.deepEqual(r.members, ['titc.java.dsf.drp']);
    assert.equal(r.hub, false);
  });

  test('无向：从 dsf.drp 出发含 switch（对方声明的反向生效）+ drp.job', () => {
    const r = neighborGroup('titc.java.dsf.drp', uks);
    assert.ok(r.members.includes('titc.java.drp.switch'));
    assert.ok(r.members.includes('titc.java.drp.job'));
  });

  test('反向邻居：dubbo.pms.order 自身无声明，组内含 openapi.switch', () => {
    const r = neighborGroup('titc.java.dubbo.pms.order', uks);
    assert.ok(r.members.includes('titc.java.pms.openapi.switch'));
  });

  test('组不含起点自身（去重）', () => {
    const r = neighborGroup('titc.java.dsf.drp', uks);
    assert.ok(!r.members.includes('titc.java.dsf.drp'));
  });

  test('枢纽 >8 邻居：hub=true 但不截断', () => {
    const big = { 'titc.java.hub': { related_uks: [] } };
    for (let i = 0; i < 10; i++) big[`titc.java.n${i}`] = { related_uks: ['titc.java.hub'] };
    const r = neighborGroup('titc.java.hub', big);
    assert.equal(r.members.length, 10);
    assert.equal(r.hub, true);
  });
});
