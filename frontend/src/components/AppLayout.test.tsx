import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import AppLayout from './AppLayout'

describe('AppLayout', () => {
  it('渲染侧栏、顶栏扩展区与主区内容', () => {
    render(
      <AppLayout sidebar={<div>侧栏内容</div>} headerExtra={<span>记忆开关</span>}>
        <div>主区内容</div>
      </AppLayout>,
    )
    expect(screen.getByTestId('app-sider')).toHaveTextContent('侧栏内容')
    expect(screen.getByTestId('app-header')).toHaveTextContent('记忆开关')
    expect(screen.getByTestId('app-content')).toHaveTextContent('主区内容')
  })
})
