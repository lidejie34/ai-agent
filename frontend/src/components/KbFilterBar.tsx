import { Select, Space } from 'antd'
import { KB_NONE, NONE_LABEL } from '../scopeNone'

// 聊天页知识库过滤选择器（迭代10 追加；迭代11 项目升级多选）：
// 项目多选 + 标签多选（各自 OR 语义，两维之间 AND 组合）。
// 选项来自 /api/kb/dimensions（受管项目 + 现有标签）；选中值随 kbProjects/kbTags 发送。
// 三下拉「都不加载」：项目下拉首项互斥哨兵——选中即清空其他项目 + 标签下拉禁用清空；
// value.projects 可含 KB_NONE，由发送/保存翻译层映射为显式 []（后端=不加载）。

export interface KbFilterValue {
  projects: string[]
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
          mode="multiple"
          allowClear
          showSearch
          size="small"
          placeholder="项目（默认全部）"
          style={{ minWidth: 180 }}
          options={[
            { value: KB_NONE, label: NONE_LABEL },
            ...projects.map((p) => ({ value: p, label: p })),
          ]}
          value={value.projects}
          onChange={(v: string[]) => {
            // 「都不加载」互斥：新选哨兵 → 只留哨兵并清空标签；
            // 哨兵在场时再选项目 → 摘除哨兵保留项目
            if (v.includes(KB_NONE)) {
              const next = value.projects.includes(KB_NONE)
                ? v.filter((p) => p !== KB_NONE)
                : [KB_NONE]
              onChange({ projects: next, tags: [] })
            } else {
              onChange({ ...value, projects: v })
            }
          }}
          disabled={disabled}
          maxTagCount={2}
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
          disabled={disabled || value.projects.includes(KB_NONE)}
          maxTagCount={3}
          data-testid="chat-kb-tags"
        />
      )}
    </Space>
  )
}
