import { lazy, Suspense } from 'react'
import { Spin } from 'antd'
import App from '../App'
import { useHashRoute } from './hooks/useHashRoute'

// 控制台懒加载分包：对话首屏 chunk 不含管理端重组件（AC-50）。
const AdminConsole = lazy(() => import('./AdminConsole'))

/**
 * 顶层切换（迭代 H，T0）：
 * - 对话页 <App/> 常驻挂载，进入控制台时仅 CSS 隐藏——会话/草稿/流式状态完整保留（AC-1）；
 * - 控制台仅在 #/admin* 时挂载，返回对话即卸载（无后台请求、无定时器）。
 */
export default function Root() {
  const { isAdmin } = useHashRoute()
  return (
    <>
      <div data-testid="chat-root" style={{ display: isAdmin ? 'none' : undefined, height: '100%' }}>
        <App />
      </div>
      {isAdmin && (
        <Suspense
          fallback={
            <div className="admin-suspense">
              <Spin size="large" />
            </div>
          }
        >
          <AdminConsole />
        </Suspense>
      )}
    </>
  )
}
