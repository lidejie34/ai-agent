// ScriptToolHandler 以 /bin/sh script.sh --name value 传参；这里做白名单解析，
// 未声明参数直接报错（防止 skyeye CLI 静默吞掉未知 flag 造成假空命中）。
export class UsageError extends Error {}

/**
 * @param {string[]} tokens process.argv.slice(2)
 * @param {Record<string,{required?:boolean,type?:'int',enum?:any[],min?:number,max?:number}>} schema
 */
export function parseNamed(tokens, schema) {
  const out = {};
  for (let i = 0; i < tokens.length; i++) {
    const tok = tokens[i];
    if (!tok.startsWith('--') || tok.length <= 2) {
      throw new UsageError(`参数格式错误（期望 --name value）: ${tok}`);
    }
    const name = tok.slice(2);
    const spec = schema[name];
    if (!spec) throw new UsageError(`未声明的参数 --${name}（不会下发给 CLI，防止未知参数被静默丢弃）`);
    const raw = tokens[++i];
    if (raw === undefined) throw new UsageError(`--${name} 缺少值`);

    if (spec.type === 'int') {
      const n = Number(raw);
      if (!Number.isInteger(n)) throw new UsageError(`--${name} 需要整数，收到: ${raw}`);
      if (spec.enum && !spec.enum.includes(n)) throw new UsageError(`--${name} 取值非法: ${n}`);
      if (spec.min !== undefined && n < spec.min) throw new UsageError(`--${name} 不能小于 ${spec.min}`);
      if (spec.max !== undefined && n > spec.max) throw new UsageError(`--${name} 不能大于 ${spec.max}`);
      out[name] = n;
    } else {
      if (spec.enum && !spec.enum.includes(raw)) {
        throw new UsageError(`--${name} 取值非法: ${raw}（允许 ${spec.enum.join('|')}）`);
      }
      out[name] = raw;
    }
  }
  for (const [name, spec] of Object.entries(schema)) {
    if (spec.required && out[name] === undefined) throw new UsageError(`缺少必填参数 --${name}`);
  }
  return out;
}

/** 互斥/必填参数组：恰好提供一个（null/undefined/空串视为未提供）。 */
export function requireExactlyOne(obj, keys, label) {
  const provided = keys.filter((k) => obj[k] !== undefined && obj[k] !== null && obj[k] !== '');
  if (provided.length !== 1) {
    throw new UsageError(`${label}必须且只能提供一个（${keys.join(' / ')}），当前提供 ${provided.length} 个`);
  }
  return provided[0];
}
