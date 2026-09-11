// SkyEye CLI 返回分类：登录态 / 限流 / 非法 uk / uk 组过大 / 空命中 / 远端错误。
// 关键区分：单个简称失败（BAD_UK）与一长串合法 uk 失败（UK_SET_TOO_LARGE）在 CLI
// 里是同一句文案，不分类就会在不存在的 uk 上反复减半重试。

const APPUK_RE = /^(?:titc|digaiempower)\./;

/**
 * @param {{parsed:any, raw:string}} input runner 解析出的 JSON（可能为 null）+ CLI 原文
 * @returns {{errorClass:string,message:string,hint?:string,failedUks?:string[]}|null}
 */
export function classifySkyeyeResult({ parsed, raw }) {
  const text = String(raw ?? '');

  if (/429/.test(text)) {
    return {
      errorClass: 'RATE_LIMITED',
      message: 'SkyEye 限流（HTTP 429），不是无数据',
      hint: '等待 30 秒以上再重试，不要连续查询',
    };
  }

  const partial = text.match(/部分应用查询失败[,:：，]?\s*失败应用参数信息=\[([^\]]*)\]/);
  if (partial) {
    const failedUks = partial[1]
      .split(/[，,]/)
      .map((s) => s.trim())
      .filter(Boolean);
    const illegal = failedUks.filter((u) => !APPUK_RE.test(u));
    if (illegal.length > 0) {
      return {
        errorClass: 'BAD_UK',
        message: `应用标识不合法（SkyEye 只认完整 appUk）: ${illegal.join(',')}`,
        failedUks,
        hint: '回到 uk 解析使用完整 appUk 后整组重查，不要减半重试',
      };
    }
    return {
      errorClass: 'UK_SET_TOO_LARGE',
      message: `单次查询 uk 数过多（${failedUks.length} 个应用查询失败，阈值不稳定）`,
      failedUks,
      hint: '确认 uk 名都合法后逐步减半（如 12→6→3）重试，并说明实际查了哪些',
    };
  }

  const ok = parsed && parsed.success === true;
  if (ok) {
    const list = parsed.result?.list;
    const count = parsed.result?.count;
    if (count === 0 || (Array.isArray(list) && list.length === 0 && (count ?? 0) === 0)) {
      return {
        errorClass: 'EMPTY',
        message: '该时间窗内 0 命中（success=true）',
        hint: '先核对时间参数（--minutes 或 --begin/--end）；确认无误后扩到 1 跳邻居组重查',
      };
    }
    return null;
  }

  const msg = String(parsed?.message || text || 'SkyEye 查询失败');
  if (parsed?.code === 1 || /账号信息为空|登录态|token|auth/i.test(msg)) {
    return {
      errorClass: 'AUTH_EXPIRED',
      message: `SkyEye 登录态失效：${msg}`,
      hint: '请在终端执行 skyeye auth login 后再让我重试',
    };
  }
  return { errorClass: 'REMOTE_ERROR', message: msg, hint: '' };
}
