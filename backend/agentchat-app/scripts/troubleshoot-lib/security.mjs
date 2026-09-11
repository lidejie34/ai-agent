// 只读安全闸门：code_root canonical 防逃逸 + SQL 标识符白名单 + WHERE 保守黑名单 + PII 掩码。
import path from 'node:path';
import fs from 'node:fs';

export class SecurityError extends Error {}

function isInside(real, rootReal) {
  return real === rootReal || real.startsWith(rootReal + path.sep);
}

/** 把相对段解析到 root 内的真实路径（跟随符号链接）；任何越界/逃逸一律拒绝。 */
export function resolveWithinRoot(root, ...segments) {
  const rootReal = fs.realpathSync(root);
  const target = path.resolve(rootReal, ...segments);
  let real;
  try {
    real = fs.realpathSync(target);
  } catch {
    // 路径不存在时也要按词法判定 .. 越界，避免用报错文案掩盖逃逸尝试。
    if (!isInside(target, rootReal)) {
      throw new SecurityError(`路径越界（逃逸出 code_root）: ${target}`);
    }
    throw new SecurityError(`路径不存在或不可访问: ${target}`);
  }
  if (!isInside(real, rootReal)) {
    throw new SecurityError(`路径越界（逃逸出 code_root）: ${real}`);
  }
  return real;
}

const IDENT_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

export function assertIdentifier(name) {
  if (!IDENT_RE.test(String(name ?? ''))) {
    throw new SecurityError(`非法标识符（只允许字母/下划线开头的字母数字下划线）: ${name}`);
  }
}

/** '*' 或逗号分隔的标识符名单。 */
export function assertIdentifierList(list) {
  const s = String(list ?? '').trim();
  if (s === '*') return;
  for (const part of s.split(',')) {
    assertIdentifier(part.trim());
  }
}

// 词边界匹配的写/危险动词与结构；宁可误杀让模型改写为简单等值条件。
const FORBIDDEN_WORDS =
  /\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|replace|call|exec|execute|into|outfile|load|union|sleep|benchmark|information_schema)\b/i;
// 危险字符/写法：语句分隔、行注释、块注释、十六进制字面量、NUL。
const FORBIDDEN_SEQ = /;|--|\/\*|\*\/|0x[0-9a-f]+\b|\0/i;

/** WHERE 条件只读闸门：≤200 字符、禁写动词/危险结构。空白直接拒绝（调用方应省略 where）。 */
export function assertReadOnlyWhere(where) {
  const s = String(where ?? '').trim();
  if (!s) throw new SecurityError('where 为空时应省略该参数，而不是传空串');
  if (s.length > 200) throw new SecurityError(`where 超长（${s.length} > 200 字符），请收窄为简单等值条件`);
  const m = s.match(FORBIDDEN_WORDS);
  if (m) throw new SecurityError(`where 含禁止的 SQL 片段: ${m[0]}`);
  if (FORBIDDEN_SEQ.test(s)) {
    const seq = s.match(FORBIDDEN_SEQ);
    throw new SecurityError(`where 含禁止的字符序列: ${seq[0]}`);
  }
}

/** 输出前 PII 掩码：身份证 18 位（先于手机号，避免前 11 位被误遮）、手机号。 */
export function maskPii(text) {
  if (text === null || text === undefined) return text;
  let s = String(text);
  s = s.replace(/(?<!\d)(\d{6})\d{8}(\d{3}[0-9Xx])(?!\d)/g, '$1********$2');
  s = s.replace(/(?<!\d)(1[3-9]\d)\d{4}(\d{4})(?!\d)/g, '$1****$2');
  return s;
}
