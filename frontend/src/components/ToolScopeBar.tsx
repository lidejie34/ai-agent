import { Select, Space, Switch } from 'antd'

// 聊天页对话级工具选择器（迭代12）：「启用工具」开关 + 内置工具多选 + MCP server 组多选。
// 选项来自 /api/tools/available；undefined=默认全部，开关关闭=本轮对话不挂任何工具。
// 注意：选择器清空（[]）在开启态等价「默认全部」——onChange 时归一为 undefined，
// 「全不挂」只能经开关表达（消除空数组与未选择的显示歧义）。

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
          options={dbTools.map((t) => ({ value: t, label: t }))}
          value={value.toolNames}
          onChange={(v) =>
            onChange({ ...value, toolNames: v.length > 0 ? v : undefined })
          }
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
          options={mcpServers.map((s) => ({ value: s, label: s }))}
          value={value.mcpServers}
          onChange={(v) =>
            onChange({ ...value, mcpServers: v.length > 0 ? v : undefined })
          }
          disabled={disabled}
          maxTagCount={2}
          data-testid="chat-mcp-servers"
        />
      )}
    </Space>
  )
}
