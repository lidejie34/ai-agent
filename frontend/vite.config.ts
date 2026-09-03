import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// 业务代码只请求相对路径 /api/...；dev 期经此 proxy 同源转发到后端（FR-3/AC-15/AC-29）。
// 目标地址由 VITE_DEV_PROXY_TARGET 控制（.env.development 入库，仅非密），默认本机 8080。
// SSE 经 http-proxy 流式透传，dev 期无需特殊响应头；生产 nginx 反代须关 buffering（见 README）。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080'
  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': { target: proxyTarget, changeOrigin: true },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
      globals: false,
      css: false,
    },
  }
})
