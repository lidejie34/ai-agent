// uk 简称解析（精确 → 后缀/分段子序列/子串 候选池）+ 无向 1 跳邻居组。
// 歧义不猜测、零命中不臆造 appUk（对齐 analyzing-logs-with-code skill 的红线）。

export class UkAmbiguousError extends Error {
  constructor(message, candidates) {
    super(message);
    this.name = 'UkAmbiguousError';
    this.candidates = candidates;
  }
}

export class UkNotFoundError extends Error {
  constructor(message, nearest) {
    super(message);
    this.name = 'UkNotFoundError';
    this.nearest = nearest;
  }
}

export class UkResolveError extends Error {}

function levenshtein(a, b) {
  const m = a.length;
  const n = b.length;
  if (!m) return n;
  if (!n) return m;
  const prev = Array.from({ length: n + 1 }, (_, j) => j);
  for (let i = 1; i <= m; i++) {
    const cur = [i];
    for (let j = 1; j <= n; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev.splice(0, prev.length, ...cur);
  }
  return prev[n];
}

/** q 的点分段是否为 key 分段的有序子序列，且最后一个 token 落在 key 末段。 */
function segmentSubsequence(q, key) {
  if (!q.includes('.')) return false;
  const qt = q.toLowerCase().split('.');
  const kt = key.toLowerCase().split('.');
  if (kt[kt.length - 1] !== qt[qt.length - 1]) return false;
  let j = 0;
  for (const seg of kt) {
    if (seg === qt[j] && j < qt.length) j++;
  }
  return j === qt.length;
}

/**
 * @returns {{appUk:string, resolvedFrom:'exact'|'suffix'|'substring'}}
 */
export function resolveUk(input, uks) {
  const q = String(input ?? '').trim();
  if (!q) throw new UkResolveError('UK_EMPTY', 'uk 不能为空');
  if (uks[q]) return { appUk: q, resolvedFrom: 'exact' };

  const allKeys = Object.keys(uks);
  const suffixKeys = allKeys.filter((k) => k.endsWith('.' + q));
  const subseqKeys = allKeys.filter((k) => segmentSubsequence(q, k));
  const ql = q.toLowerCase();
  const substringKeys = allKeys.filter(
    (k) => k.toLowerCase().includes(ql) || String(uks[k].display_name ?? '').toLowerCase().includes(ql),
  );

  // 三种非精确命中合成一个候选池：池里 >1 即为歧义，不替用户挑链路。
  const pool = [...new Set([...suffixKeys, ...subseqKeys, ...substringKeys])];
  if (pool.length === 1) {
    return { appUk: pool[0], resolvedFrom: suffixKeys.includes(pool[0]) ? 'suffix' : 'substring' };
  }
  if (pool.length > 1) {
    throw new UkAmbiguousError(
      `「${q}」同时命中 ${pool.length} 个应用，请指定完整 appUk`,
      pool.map((key) => ({ key, display_name: uks[key].display_name })),
    );
  }

  const lastSeg = (k) => k.split('.').pop() ?? k;
  const nearest = allKeys
    .map((k) => ({
      k,
      d: Math.min(
        levenshtein(ql, lastSeg(k).toLowerCase()),
        Math.floor(levenshtein(ql, k.toLowerCase()) / 3),
      ),
    }))
    .sort((a, b) => a.d - b.d)
    .filter((x) => x.d <= 6)
    .slice(0, 5)
    .map((x) => x.k);
  throw new UkNotFoundError(`未找到应用「${q}」，请确认简称或使用完整 appUk`, nearest);
}

/**
 * 无向 1 跳邻居：显式 related_uks ∪ 反向声明了本 uk 的邻居。不做传递闭包。
 * @returns {{members:string[], hub:boolean, neighborCount:number}}
 */
export function neighborGroup(appUk, uks) {
  const fwd = uks[appUk]?.related_uks ?? [];
  const rev = Object.entries(uks)
    .filter(([k, v]) => k !== appUk && (v.related_uks ?? []).includes(appUk))
    .map(([k]) => k);
  const members = [...new Set([...fwd, ...rev])];
  return { members, neighborCount: members.length, hub: members.length > 8 };
}
