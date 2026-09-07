import { Button, DatePicker, Form, Input, Select, Space } from 'antd'
import dayjs from 'dayjs'
import type { LogHandlerType, LogStatus } from '../types'

// 审计日志过滤表单（迭代 H，AC-40）：手动「查询」才发请求；时间范围 showTime
// 默认时分秒 00:00:00 / 23:59:59；「重置」清空并通知父组件回到首屏参数。

export interface LogFilterValues {
  toolName?: string
  sessionId?: string
  status?: LogStatus
  handlerType?: LogHandlerType
  range?: [dayjs.Dayjs, dayjs.Dayjs] | null
}

interface LogFiltersFormProps {
  onSearch: (values: LogFilterValues) => void
  onReset: () => void
}

const STATUS_OPTIONS: { value: LogStatus; label: string }[] = [
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'TIMEOUT', label: '超时' },
]

const HANDLER_OPTIONS: { value: LogHandlerType; label: string }[] = [
  { value: 'BUILTIN', label: '内置' },
  { value: 'SCRIPT', label: '脚本' },
  { value: 'MCP', label: 'MCP' },
]

export default function LogFiltersForm({ onSearch, onReset }: LogFiltersFormProps) {
  const [form] = Form.useForm<LogFilterValues>()

  return (
    <Form
      form={form}
      layout="inline"
      className="admin-log-filters"
      onFinish={(v) => onSearch(v)}
    >
      <Form.Item name="toolName">
        <Input placeholder="工具名" allowClear style={{ width: 140 }} />
      </Form.Item>
      <Form.Item name="sessionId">
        <Input placeholder="会话 ID" allowClear style={{ width: 160 }} />
      </Form.Item>
      <Form.Item name="status">
        <Select
          placeholder="全部状态"
          allowClear
          style={{ width: 120 }}
          options={STATUS_OPTIONS}
        />
      </Form.Item>
      <Form.Item name="handlerType">
        <Select
          placeholder="全部类型"
          allowClear
          style={{ width: 120 }}
          options={HANDLER_OPTIONS}
        />
      </Form.Item>
      <Form.Item name="range">
        <DatePicker.RangePicker
          showTime={{
            defaultValue: [dayjs('00:00:00', 'HH:mm:ss'), dayjs('23:59:59', 'HH:mm:ss')],
          }}
          format="YYYY-MM-DD HH:mm:ss"
        />
      </Form.Item>
      <Form.Item>
        <Space>
          <Button type="primary" htmlType="submit">
            查询
          </Button>
          <Button
            onClick={() => {
              form.resetFields()
              onReset()
            }}
          >
            重置
          </Button>
        </Space>
      </Form.Item>
    </Form>
  )
}
