import { apiFetch } from './http'

// 聊天侧知识库维度选项（迭代10 追加）：GET /api/kb/dimensions。
// 无 admin token；RAG 开关关闭时后端返回空数组（200）。

export interface KbDimensionOptions {
  projects: string[]
  tags: string[]
}

export async function getKbDimensions(): Promise<KbDimensionOptions> {
  return apiFetch<KbDimensionOptions>('/api/kb/dimensions')
}
