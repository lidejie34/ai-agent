import { Select, Space } from 'antd'

// 聊天页知识库过滤选择器（迭代10 追加）：项目单选 + 标签多选（OR 语义，与项目 AND 组合）。
// 选项来自 /api/kb/dimensions（受管项目 + 现有标签）；选中值随 kbProject/kbTags 发送。

export interface KbFilterValue {
  project?: string
  tags: string[]
}

export interface KbFilterBarProps {
  projects: string[]
  tags: string[]
  value: KbFilterValue
  onChange: (v: KbFilterValue) => void
  disabled?: boolean
}

export default function KbFilterBar({ projects, tags, value, onChange, disabled }: KbFilterBarProps) {
  return (
    <Space size={8} wrap className="kb-filter-bar" data-testid="kb-filter-bar">
      <span className="kb-filter-bar-label">知识库范围</span>
      {projects.length > 0 && (
        <Select
          allowClear
          showSearch
          size="small"
          placeholder="项目（默认全部）"
          style={{ minWidth: 140 }}
          options={projects.map((p) => ({ value: p, label: p }))}
          value={value.project}
          onChange={(v) => onChange({ ...value, project: v ?? undefined })}
          disabled={disabled}
          data-testid="chat-kb-project"
        />
      )}
      {tags.length > 0 && (
        <Select
          mode="multiple"
          allowClear
          showSearch
          size="small"
          placeholder="标签（默认全部）"
          style={{ minWidth: 180 }}
          options={tags.map((t) => ({ value: t, label: t }))}
          value={value.tags}
          onChange={(v) => onChange({ ...value, tags: v })}
          disabled={disabled}
          maxTagCount={3}
          data-testid="chat-kb-tags"
        />
      )}
    </Space>
  )
}
