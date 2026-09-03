import type { ReactNode } from 'react'
import { Layout } from 'antd'

const { Sider, Header, Content } = Layout

export interface AppLayoutProps {
  /** 左侧栏内容（SessionSidebar） */
  sidebar: ReactNode
  /** 顶栏右侧操作区（记忆开关等） */
  headerExtra?: ReactNode
  /** 主区：消息区/空态、错误条、输入框 */
  children: ReactNode
}

/**
 * 应用骨架：左侧会话栏 + 顶栏 + 主内容区（FR-10）。
 * 后端无 CORS，前后端同源部署（dev 由 vite proxy，prod 由 nginx 反代）。
 */
export default function AppLayout({ sidebar, headerExtra, children }: AppLayoutProps) {
  return (
    <Layout className="app-layout" data-testid="app-layout">
      <Sider width={280} theme="light" className="app-sider" data-testid="app-sider">
        <div className="app-logo">AI 对话</div>
        {sidebar}
      </Sider>
      <Layout className="app-body">
        <Header className="app-header" data-testid="app-header">
          <span className="app-title">AI 对话助手</span>
          <div className="app-header-extra">{headerExtra}</div>
        </Header>
        <Content className="app-content" data-testid="app-content">
          {children}
        </Content>
      </Layout>
    </Layout>
  )
}
