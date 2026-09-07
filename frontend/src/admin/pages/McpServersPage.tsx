import { Alert, Button, Empty, Skeleton } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { listMcpServers } from '../api/adminApi'
import { useAdminAsync } from '../hooks/useAdminAsync'
import { adminErrorText } from '../../utils/errors'
import McpServerCard from '../components/McpServerCard'
import type { McpServer } from '../types'

// MCP 服务器只读面板（迭代 H，AC-15~20）：手动刷新，无轮询、无写操作、不展示 env。
export default function McpServersPage() {
  const { data, loading, error, refresh } = useAdminAsync(() => listMcpServers(), [])
  const servers: McpServer[] = data?.servers ?? []

  return (
    <div className="mcp-page" data-testid="admin-page-mcp">
      <div className="admin-page-toolbar">
        <Button
          icon={<ReloadOutlined />}
          loading={loading}
          onClick={() => void refresh()}
          data-testid="mcp-refresh"
        >
          刷新
        </Button>
      </div>

      {loading && <Skeleton active paragraph={{ rows: 6 }} />}

      {!loading && error && (
        <Alert
          type="error"
          showIcon
          message="MCP 服务器加载失败"
          description={adminErrorText(error)}
          action={
            <Button size="small" onClick={() => void refresh()}>
              重试
            </Button>
          }
        />
      )}

      {!loading && !error && servers.length === 0 && <Empty description="未配置 MCP 服务器" />}

      {!loading && !error && servers.length > 0 && (
        <div className="mcp-server-list">
          {servers.map((s) => (
            <McpServerCard key={s.name} server={s} />
          ))}
        </div>
      )}
    </div>
  )
}
