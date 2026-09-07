import { Alert, Badge, Card, Collapse, Descriptions, Table } from 'antd'
import type { McpServer, McpTool } from '../types'
import TextBlock from './TextBlock'
import { fmtTime } from '../utils'

// 单个 MCP server 卡片（迭代 H，AC-15/16/17/19）：严格只读，无 env、无写按钮。

const toolColumns = [
  { title: '名称（暴露名）', dataIndex: 'name', key: 'name' },
  { title: '原始名', dataIndex: 'rawName', key: 'rawName' },
  {
    title: '描述',
    dataIndex: 'description',
    key: 'description',
    render: (v?: string) => (v && v.trim() ? v : '-'),
  },
]

function ArgsBlock({ args }: { args?: string[] }) {
  if (!args || args.length === 0) return <span>无</span>
  return (
    <div className="admin-args-list">
      {args.map((a, i) => (
        <pre key={i} className="admin-prewrap admin-arg-line">
          {a}
        </pre>
      ))}
    </div>
  )
}

export default function McpServerCard({ server }: { server: McpServer }) {
  const ready = server.status === 'READY'
  const tools: McpTool[] = server.tools ?? []
  return (
    <Card className="mcp-server-card" title={<span className="mcp-card-title">{server.name}</span>}>
      <div className="mcp-card-badge">
        <Badge status={ready ? 'success' : 'error'} text={ready ? '已连接' : '不可用'} />
      </div>
      <Descriptions column={1} bordered size="small" className="mcp-desc">
        <Descriptions.Item label="启动命令">{server.command && server.command.trim() ? server.command : '-'}</Descriptions.Item>
        <Descriptions.Item label="启动参数">
          <ArgsBlock args={server.args} />
        </Descriptions.Item>
        <Descriptions.Item label="连接时间">
          {ready && server.connectedAt ? fmtTime(server.connectedAt) : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="发现工具数">{server.toolCount}</Descriptions.Item>
      </Descriptions>

      <Collapse
        className="mcp-tools-collapse"
        items={[
          {
            key: 'tools',
            label: `发现工具（${server.toolCount}）`,
            children:
              tools.length === 0 ? (
                <span className="mcp-no-tools">无工具</span>
              ) : (
                <Table
                  size="small"
                  rowKey={(r: McpTool) => `${r.rawName}@${r.name}`}
                  pagination={false}
                  columns={toolColumns}
                  dataSource={tools}
                />
              ),
          },
        ]}
      />

      {server.lastError && server.lastError.trim() ? (
        <Alert
          type="error"
          showIcon
          className="mcp-last-error"
          message="最近错误"
          description={<TextBlock text={server.lastError} copyable={false} />}
        />
      ) : (
        <div className="mcp-no-error">无错误信息</div>
      )}
    </Card>
  )
}
