// 配置加载：~/.ai-agent/troubleshoot/config.json + dbs.env（node os.homedir() 解析，
// 不依赖被 ScriptToolHandler 净化掉的 HOME 环境变量）。仓内只放脱敏模板。
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const LIB_DIR = path.dirname(fileURLToPath(import.meta.url));
export const TEMPLATE_CONFIG = path.resolve(LIB_DIR, '..', 'troubleshoot.example.json');
export const TEMPLATE_DBS = path.resolve(LIB_DIR, '..', 'dbs.example.env');
export const TROUBLE_DIR = path.join(os.homedir(), '.ai-agent', 'troubleshoot');
export const CONFIG_PATH = path.join(TROUBLE_DIR, 'config.json');
export const DBS_PATH = path.join(TROUBLE_DIR, 'dbs.env');

export class ConfigError extends Error {
  constructor(kind, message, hint = '') {
    super(message);
    this.name = 'ConfigError';
    this.kind = kind;
    this.hint = hint;
  }
}

/** 加载并校验 config.json；任何问题抛 ConfigError（runner 统一转 JSON 错误输出）。 */
export function loadConfig(configPath = CONFIG_PATH) {
  let text;
  try {
    text = fs.readFileSync(configPath, 'utf8');
  } catch {
    throw new ConfigError(
      'CONFIG_MISSING',
      `配置文件不存在: ${configPath}`,
      `请执行：mkdir -p ~/.ai-agent/troubleshoot && cp "${TEMPLATE_CONFIG}" "${configPath}"`,
    );
  }
  let cfg;
  try {
    cfg = JSON.parse(text);
  } catch (e) {
    throw new ConfigError('CONFIG_BAD_JSON', `配置 JSON 解析失败: ${e.message}`);
  }
  validateConfig(cfg);
  return cfg;
}

function validateConfig(c) {
  if (!c || typeof c !== 'object') throw new ConfigError('CONFIG_INVALID', '配置不是 JSON 对象');
  if (c.version !== 1) throw new ConfigError('CONFIG_INVALID', '仅支持 version=1');
  if (!Array.isArray(c.logEnvs) || c.logEnvs.length === 0)
    throw new ConfigError('CONFIG_INVALID', 'logEnvs 必须是非空数组');
  if (!c.skyeye?.tsEntry || !c.skyeye?.nodeBin)
    throw new ConfigError('CONFIG_INVALID', 'skyeye.tsEntry / skyeye.nodeBin 必填');
  if (!c.uk || typeof c.uk !== 'object') {
    // 兼容拼写
  }
  const uks = c.uks;
  if (!uks || typeof uks !== 'object' || Object.keys(uks).length === 0)
    throw new ConfigError('CONFIG_INVALID', 'uks 必须是非空对象');
  for (const [key, v] of Object.entries(uks)) {
    for (const r of v.related_uks ?? []) {
      if (!uks[r])
        throw new ConfigError('CONFIG_BAD_REFS', `${key} 的 related_uks 成员 ${r} 未在 uks 中定义`);
    }
    if (v.database && !c.databases?.[v.database])
      throw new ConfigError('CONFIG_BAD_REFS', `${key} 绑定的 database ${v.database} 不在 databases 中定义`);
  }
}

/** 极简 KEY=VALUE 解析：忽略空行/# 注释，去首尾空白与成对引号。 */
export function loadDbsEnv(dbsPath = DBS_PATH) {
  let text;
  try {
    text = fs.readFileSync(dbsPath, 'utf8');
  } catch {
    throw new ConfigError(
      'DBS_MISSING',
      `凭据文件不存在: ${dbsPath}`,
      `请执行：cp "${TEMPLATE_DBS}" "${dbsPath}" 后填入 QA 只读账号密码`,
    );
  }
  const out = {};
  for (const line of text.split(/\r?\n/)) {
    const s = line.trim();
    if (!s || s.startsWith('#')) continue;
    const i = s.indexOf('=');
    if (i < 0) continue;
    let v = s.slice(i + 1).trim();
    if (
      (v.startsWith('"') && v.endsWith('"')) ||
      (v.startsWith("'") && v.endsWith("'"))
    ) {
      v = v.slice(1, -1);
    }
    out[s.slice(0, i).trim()] = v;
  }
  return out;
}
