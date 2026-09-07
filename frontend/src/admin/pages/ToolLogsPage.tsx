import { useState } from 'react'
import { Alert, Button, Empty, Skeleton, Table, Tooltip } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { pageToolLogs } from '../api/adminApi'
import { useAdminAsync } from '../hooks/useAdminAsync'
import { adminErrorText } from '../../utils/errors'
import { fmtDuration, fmtTime, hasErrorCode } from '../utils'
import { HandlerTypeTag, LogStatusTag } from '../components/tags'
import TextBlock from '../components/TextBlock'
import LogFiltersForm, { type LogFilterValues } from '../components/LogFiltersForm'
import type { ToolCallLog, ToolLogQuery } from '../types'

// 审计日志页（迭代 H，AC-38~45、D8/D9）。
// - 分页 0 基对齐后端，pageSizeOptions 10/20/50；翻页保持过滤条件（AC-39/40）。
// - 手动「查询」「刷新」才发请求；无轮询（AC-45）。
// - 失败/超时行展开看 errorMessage（缺省兜底）；成功行展开看输入摘要（AC-41）。
// - 长文本一律 <pre> 纯文本（TextBlock），不渲染 markdown/HTML。

const DEFAULT_PAGE_SIZE = 20

/** 过滤表单值 + 分页 → 请求查询参数；空值不传，时间格式 yyyy-MM-dd HH:mm:ss（AC-40/D8）。 */
export function buildLogQuery(f: LogFilterValues, page: number, size: number): ToolLogQuery {
  const q: ToolLogQuery = { page, size }
  if (f.toolName?.trim()) q.toolName = f.toolName.trim()
  if (f.sessionId?.trim()) q.sessionId = f.sessionId.trim()
  if (f.status) q.status = f.status
  if (f.handlerType) q.handlerType = f.handlerType
  if (f.range && f.range[0] && f.range[1]) {
    q.from = f.range[0].format('YYYY-MM-DD HH:mm:ss')
    q.to = f.range[1].format('YYYY-MM-DD HH:mm:ss')
  }
  return q
}

const columns: ColumnsType<ToolCallLog> = [
  { title: '时间', dataIndex: 'createdAt', width: 170, render: (v: string) => fmtTime(v) },
  { title: '工具名', dataIndex: 'toolName', width: 140 },
  {
    title: '类型',
    dataIndex: 'handlerType',
    width: 100,
    render: (t: string) => <HandlerTypeTag type={t} />,
  },
  {
    title: '状态',
    dataIndex: 'status',
    width: 90,
    render: (s: string) => <LogStatusTag status={s} />,
  },
  {
    title: '会话 ID',
    dataIndex: 'sessionId',
    width: 160,
    ellipsis: { showTitle: false },
    render: (v?: string) =>
      v ? (
        <Tooltip title={v}>
          <span className="admin-cell-ellipsis">{v}</span>
        </Tooltip>
      ) : (
        <span className="admin-text-empty">-</span>
      ),
  },
  {
    title: '耗时',
    dataIndex: 'durationMs',
    width: 100,
    render: (v: number | null | undefined) =>
      v === null || v === undefined ? <span className="admin-text-empty">-</span> : fmtDuration(v),
  },
  {
    title: '结果字符数',
    dataIndex: 'resultChars',
    width: 100,
    render: (v: number | null | undefined) =>
      v === null || v === undefined ? <span className="admin-text-empty">-</span> : v,
  },
  {
    title: '输入摘要',
    dataIndex: 'inputSummary',
    ellipsis: { showTitle: false },
    render: (v?: string) =>
      v ? (
        <Tooltip title={v}>
          <span className="admin-cell-ellipsis">{v}</span>
        </Tooltip>
      ) : (
        <span className="admin-text-empty">-</span>
      ),
  },
]

export default function ToolLogsPage() {
  const [q, setQ] = useState<ToolLogQuery>({ page: 0, size: DEFAULT_PAGE_SIZE })
  const { data, loading, error, refresh } = useAdminAsync(() => pageToolLogs(q), [q])

  const onSearch = (f: LogFilterValues) => setQ(buildLogQuery(f, 0, q.size))
  const onReset = () => setQ({ page: 0, size: q.size })

  return (
    <div data-testid="admin-page-logs" className="logs-page">
      <div className="admin-page-toolbar admin-log-toolbar">
        <LogFiltersForm onSearch={onSearch} onReset={onReset} />
        <Button
          icon={<ReloadOutlined />}
          loading={loading}
          onClick={() => void refresh()}
          data-testid="logs-refresh"
        >
          刷新
        </Button>
      </div>

      {loading && <Skeleton active paragraph={{ rows: 6 }} />}

      {!loading && error && (
        <Alert
          type="error"
          showIcon
          message="审计日志加载失败"
          description={hasErrorCode(error) ? adminErrorText(error) : '请求失败，请稍后重试'}
          action={
            <Button size="small" onClick={() => void refresh()}>
              重试
            </Button>
          }
        />
      )}

      {!loading && !error && (
        <Table<ToolCallLog>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={data?.content ?? []}
          locale={{ emptyText: <Empty description="无符合条件的日志" /> }}
          expandable={{
            rowExpandable: () => true,
            expandedRowRender: (row) =>
              row.status === 'FAILED' || row.status === 'TIMEOUT' ? (
                <div className="admin-log-expand">
                  <div className="admin-log-expand-label">错误信息</div>
                  <TextBlock text={row.errorMessage} empty="无错误信息" copyable={false} />
                </div>
              ) : (
                <div className="admin-log-expand">
                  <div className="admin-log-expand-label">输入摘要</div>
                  <TextBlock text={row.inputSummary} empty="无输入摘要" copyable={false} />
                </div>
              ),
          }}
          pagination={{
            current: q.page + 1,
            pageSize: q.size,
            total: data?.total ?? 0,
            pageSizeOptions: [10, 20, 50],
            showSizeChanger: true,
            // 改每页条数时回到第 0 页（antd 回传的仍是旧页码，需主动归零，AC-39）
            onChange: (page, size) =>
              setQ((prev) => ({ ...prev, page: size !== prev.size ? 0 : page - 1, size })),
          }}
        />
      )}
    </div>
  )
}
