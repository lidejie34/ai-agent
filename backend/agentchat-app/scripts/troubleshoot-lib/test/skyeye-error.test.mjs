import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { classifySkyeyeResult } from '../skyeye-error.mjs';

// T0：CLI 原文/JSON → 错误分类（auth/限流/非法 uk/uk 组过大/空命中）

describe('classifySkyeyeResult', () => {
  test('code=1 + 账号信息为空 → AUTH_EXPIRED，提示登录', () => {
    const r = classifySkyeyeResult({ parsed: { success: false, code: 1, message: '账号信息为空' }, raw: '' });
    assert.equal(r.errorClass, 'AUTH_EXPIRED');
    assert.match(r.hint, /skyeye auth login/);
  });

  test('429 文案 → RATE_LIMITED，提示等待', () => {
    const r = classifySkyeyeResult({ parsed: null, raw: '失败: 日志查询接口返回状态码 429' });
    assert.equal(r.errorClass, 'RATE_LIMITED');
    assert.match(r.hint, /30/);
  });

  test('部分失败且失败名是简称（缺 titc. 前缀）→ BAD_UK', () => {
    const raw = '查询失败: 部分应用查询失败,失败应用参数信息=[drp.switch]';
    const r = classifySkyeyeResult({ parsed: { success: false }, raw });
    assert.equal(r.errorClass, 'BAD_UK');
    assert.ok(r.failedUks.includes('drp.switch'));
  });

  test('部分失败且失败名是一长串合法 appUk → UK_SET_TOO_LARGE', () => {
    const list = Array.from({ length: 14 }, (_, i) => `titc.java.drp.n${i}`).join(',');
    const raw = `查询失败: 部分应用查询失败,失败应用参数信息=[${list}]`;
    const r = classifySkyeyeResult({ parsed: { success: false }, raw });
    assert.equal(r.errorClass, 'UK_SET_TOO_LARGE');
  });

  test('success 且 count=0 → EMPTY（非错误，带扩圈提示）', () => {
    const r = classifySkyeyeResult({
      parsed: { success: true, code: 0, result: { count: 0, list: [] } },
      raw: '',
    });
    assert.equal(r.errorClass, 'EMPTY');
  });

  test('success 且有命中 → null（无需分类）', () => {
    const r = classifySkyeyeResult({
      parsed: { success: true, result: { count: 2, list: [{ msg: 'x' }] } },
      raw: '',
    });
    assert.equal(r, null);
  });

  test('success=false 其他远端错误 → REMOTE_ERROR', () => {
    const r = classifySkyeyeResult({ parsed: { success: false, code: 500, message: 'boom' }, raw: '' });
    assert.equal(r.errorClass, 'REMOTE_ERROR');
  });
});
