import type { ReactNode } from 'react'
import { Button, Layout, Popconfirm, Tabs } from 'antd'
import type { AdminPageKey } from '../hooks/useHashRoute'

// 控制台骨架（迭代 H，AC-5）：顶栏（产品名/返回对话/退出登录）+ Tabs 子页导航（hash 联动，AC-4）。
const { Header, Content } = Layout

export interface AdminLayoutProps {
  page: AdminPageKey
  onNavigate: (to: string) => void
  onBack: () => void
  onLogout: () => void
  children: ReactNode
}

const TAB_ITEMS = [
  { key: 'tools', label: '工具注册表' },
  { key: 'mcp', label: 'MCP 服务器' },
  { key: 'logs', label: '审计日志' },
]

export default function AdminLayout({ page, onNavigate, onBack, onLogout, children }: AdminLayoutProps) {
  return (
    <Layout className="admin-layout" data-testid="admin-layout">
      <Header className="admin-header">
        <span className="admin-title">AI 管理控制台</span>
        <div className="admin-header-actions">
          <Button size="small" data-testid="admin-back-btn" onClick={onBack}>
            返回对话
          </Button>
          <Popconfirm
            title="确认退出登录？"
            description="退出后需重新输入管理令牌才能进入控制台。"
            okText="退出"
            cancelText="取消"
            onConfirm={onLogout}
          >
            <Button size="small" data-testid="admin-logout-btn">
              退出登录
            </Button>
          </Popconfirm>
        </div>
      </Header>
      <Content className="admin-content">
        <Tabs
          className="admin-tabs"
          activeKey={page}
          items={TAB_ITEMS}
          onChange={(k) => onNavigate(`/admin/${k}`)}
        />
        <div className="admin-page-body">{children}</div>
      </Content>
    </Layout>
  )
}
