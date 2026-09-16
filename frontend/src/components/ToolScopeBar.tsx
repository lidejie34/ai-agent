import { Select, Space, Switch } from 'antd'
import { DB_TOOLS_NONE, MCP_NONE, NONE_LABEL } from '../scopeNone'

// 聊天页对话级工具选择器（迭代12）：「启用工具」开关 + 内置工具多选 + MCP server 组多选。
// 选项来自 /api/tools/available；undefined=默认全部，开关关闭=本轮对话不挂任何工具。
// 注意：选择器清空（[]）在开启态等价「默认全部」——onChange 时归一为 undefined。
// 三下拉「都不加载」：两多选各加首项互斥哨兵（各自侧内互斥）——该侧「全不挂」
// 无需关总开关即可表达；value.toolNames/mcpServers 可含哨兵，翻译层映射为显式 []。

export interface ToolScopeValue {
  enabled: boolean
  toolNames?: string[]
  mcpServers?: string[]
}

export interface ToolScopeBarProps {
  dbTools: string[]
  mcpServers: string[]
  value: ToolScopeValue
  onChange: (v: ToolScopeValue) => void
  disabled?: boolean
}

export default function ToolScopeBar({ dbTools, mcpServers, value, onChange, disabled }: ToolScopeBarProps) {
  return (
    <Space size={8} wrap className="tool-scope-bar" data-testid="tool-scope-bar">
      <span className="tool-scope-bar-label">工具范围</span>
      <Switch
        size="small"
        checked={value.enabled}
        onChange={(on) =>
          onChange(on ? { enabled: true } : { enabled: false, toolNames: [], mcpServers: [] })
        }
        disabled={disabled}
        data-testid="chat-tool-enabled"
      />
      {value.enabled && dbTools.length > 0 && (
        <Select
          mode="multiple"
          allowClear
          showSearch
          size="small"
          placeholder="内置工具（默认全部）"
          style={{ minWidth: 180 }}
          options={[
            { value: DB_TOOLS_NONE, label: NONE_LABEL },
            ...dbTools.map((t) => ({ value: t, label: t })),
          ]}
          value={value.toolNames}
          onChange={(v: string[]) => {
            // 「都不加载」互斥：新选哨兵 → 只留哨兵；哨兵在场时再选工具 → 摘除哨兵
            if (v.includes(DB_TOOLS_NONE)) {
              const next = (value.toolNames ?? []).includes(DB_TOOLS_NONE)
                ? v.filter((t) => t !== DB_TOOLS_NONE)
                : [DB_TOOLS_NONE]
              onChange({ ...value, toolNames: next.length > 0 ? next : undefined })
            } else {
              onChange({ ...value, toolNames: v.length > 0 ? v : undefined })
            }
          }}
          disabled={disabled}
          maxTagCount={2}
          data-testid="chat-tool-names"
        />
      )}
      {value.enabled && mcpServers.length > 0 && (
        <Select
          mode="multiple"
          allowClear
          showSearch
          size="small"
          placeholder="MCP 服务（默认全部）"
          style={{ minWidth: 180 }}
          options={[
            { value: MCP_NONE, label: NONE_LABEL },
            ...mcpServers.map((s) => ({ value: s, label: s })),
          ]}
          value={value.mcpServers}
          onChange={(v: string[]) => {
            // 「都不加载」互斥（MCP 侧，与内置工具侧互不影响）
            if (v.includes(MCP_NONE)) {
              const next = (value.mcpServers ?? []).includes(MCP_NONE)
                ? v.filter((s) => s !== MCP_NONE)
                : [MCP_NONE]
              onChange({ ...value, mcpServers: next.length > 0 ? next : undefined })
            } else {
              onChange({ ...value, mcpServers: v.length > 0 ? v : undefined })
            }
          }}
          disabled={disabled}
          maxTagCount={2}
          data-testid="chat-mcp-servers"
        />
      )}
    </Space>
  )
}
