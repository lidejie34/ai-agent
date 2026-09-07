import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
// 代码高亮主题（rehype-highlight 输出 hljs class）
import 'highlight.js/styles/github.css'
import './index.css'
import Root from './admin/Root'

dayjs.locale('zh-cn')

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ConfigProvider locale={zhCN}>
      <Root />
    </ConfigProvider>
  </StrictMode>,
)
