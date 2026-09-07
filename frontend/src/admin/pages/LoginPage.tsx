import { useState } from 'react'
import { Alert, Button, Card, Form, Input } from 'antd'
import { adminErrorText } from '../../utils/errors'

// 管理端登录页（迭代 H，AC-6~10）：渲染期零请求；验证请求只在提交时发出（GET /api/admin/tools）。
// 401 用 AC-8 专属文案（区别于会话失效的「登录已失效」）；其余错误码走 adminErrorText。

interface LoginPageProps {
  onLogin: (token: string) => Promise<void>
}

function hasCode(e: unknown): e is { code: string; message?: string } {
  return !!e && typeof e === 'object' && typeof (e as { code?: unknown }).code === 'string'
}

function loginErrorText(e: { code: string; message?: string }): string {
  if (e.code === 'ADMIN_UNAUTHORIZED') return '管理令牌无效，请重新输入'
  return adminErrorText(e)
}

export default function LoginPage({ onLogin }: LoginPageProps) {
  const [token, setToken] = useState('')
  const [loading, setLoading] = useState(false)
  const [errorText, setErrorText] = useState<string | null>(null)

  const submit = async () => {
    const t = token.trim()
    if (!t || loading) return // 空值不可提交（AC-6）
    setLoading(true)
    setErrorText(null)
    try {
      await onLogin(t)
    } catch (e) {
      // 失败保留输入内容（AC-8）：token state 不清
      setErrorText(hasCode(e) ? loginErrorText(e) : '登录失败，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card className="admin-login-card" title="AI 管理控制台">
      <Form layout="vertical" onFinish={submit}>
        <Form.Item label="管理令牌">
          <Input.Password
            data-testid="admin-token-input"
            placeholder="请输入管理令牌"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            autoComplete="off"
          />
        </Form.Item>
        {errorText && (
          <Alert type="error" showIcon className="admin-login-error" message={errorText} />
        )}
        <Button
          type="primary"
          htmlType="submit"
          block
          loading={loading}
          disabled={!token.trim()}
          data-testid="admin-login-btn"
        >
          登录
        </Button>
      </Form>
    </Card>
  )
}
