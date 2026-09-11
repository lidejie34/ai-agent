import { useHashRoute } from './hooks/useHashRoute'
import { useAdminAuth } from './hooks/useAdminAuth'
import AdminLayout from './components/AdminLayout'
import LoginPage from './pages/LoginPage'
import ToolsPage from './pages/ToolsPage'
import McpServersPage from './pages/McpServersPage'
import ToolLogsPage from './pages/ToolLogsPage'
import KbPage from './pages/KbPage'

// 控制台壳（迭代 H）：鉴权门 + AdminLayout + 按 hash 切换子页。
// - 未登录渲染登录卡片（hash 保持目标子页，登录成功后自动落在目标子页，AC-2）；
// - 子页随 hash 挂载/卸载，独立加载、不缓存（无轮询，重进重拉，AC-4/20/43）。
export default function AdminConsole() {
  const { page, navigate, backToChat } = useHashRoute()
  const { authed, login, logout } = useAdminAuth()

  return (
    <div className="admin-console-root" data-testid="admin-console">
      {!authed ? (
        <div className="admin-login-wrap">
          <LoginPage onLogin={login} />
        </div>
      ) : (
        <AdminLayout page={page} onNavigate={navigate} onBack={backToChat} onLogout={logout}>
          {page === 'tools' && <ToolsPage />}
          {page === 'mcp' && <McpServersPage />}
          {page === 'logs' && <ToolLogsPage />}
          {page === 'kb' && <KbPage />}
        </AdminLayout>
      )}
    </div>
  )
}
