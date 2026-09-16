// 三下拉「都不加载」互斥哨兵（单一定义文件）：仅作选择器内部 value，永不下发后端。
// 发送（useChatStream）/ 保存（toScopeBody）/ 恢复（restoreScope）三处翻译层统一映射：
// 哨兵 ↔ 显式 []（后端三态：缺键/null=默认全部、[]=都不加载、非空=子集）。
export const KB_NONE = '__none_kb__'
export const DB_TOOLS_NONE = '__none_db_tools__'
export const MCP_NONE = '__none_mcp__'

export const NONE_LABEL = '都不加载'
