# skyeye_query_log 使用指南（对话内日志排查）

包装本机 skyeye queryLog。返回压缩 JSON：`success`/`code`/`result`，
`result.logs[]` 每条只有 `time`/`level`/`appUk`/`contextId`/`msg`，按时间正序，msg 超 1500 字符截断。

## 铁律：两阶段查询，不要跳步

1. **首查（窄）**：只给 **1 个起始 uk** + `indexContext=<关键词>`（traceId/orderId/异常文案/业务号都行，
   不用判断类型），时间窗用用户给的。**不要**首查就 `expand=1`。
   - 例外：用户明确说"这个 contextId/链路 id 是 xxx"，直接 `contextId=xxx` + 单 uk，跳过模糊首查。
   - 关键词长得像 32 位十六进制不等于 contextId——用户没明说就一律走 indexContext。
2. 从首查结果抽 `contextId`：恰好 1 个直接用；≥2 个按 `(time, contextId, 首条摘要, uk)` 让用户选
   （"最新一次"这类语义可自行选定并说明）；0 个 contextId 告知用户，改线程栈/换关键词，不要编造。
3. **精查（宽）**：`contextId=<id>` + `expand=1`，时间窗收敛到首条命中前 5 分钟 ~ 末条命中后 5 分钟。
   工具自动把 uk 扩成「起始 uk + 无向 1 跳邻居」一次性下发，不做传递闭包。
   输出 meta 的 `neighborCount` 是实际邻居数；`hubWarning` 非空表示枢纽 uk，若 SkyEye 部分失败要逐步减半
   （12→6→3）并在回复里说明实际查了哪些、跳过哪些。

## 参数

| 参数 | 必填 | 说明 |
|---|---|---|
| `uk` | 二选一 | 应用简称/完整 uk，**工具自动做简称解析**（精确→后缀→子串），非精确命中会在 meta 回传 resolvedUk/resolvedFrom |
| `appUks` | 二选一 | 旧协议：完整 uk 逗号串，原样透传不解析。新对话一律用 `uk` |
| `env` | 是 | **仅 qa / uat / product**（stage 已被工具拒绝）；生产只查日志，不查库 |
| `minutes` | 二选一 | 1~1440 分钟 |
| `begin`/`end` | 二选一 | 必须成对，格式 `YYYY-MM-DD HH:mm:ss.SSS`；与 minutes 互斥 |
| `indexContext` | 二选一 | 模糊检索文本（首查用） |
| `contextId` | 二选一 | 链路直查（精查用）；与 indexContext 互斥 |
| `expand` | 否 | 仅允许 `1`，且**必须同时给 contextId**；首查严禁带 |
| `priority` | 否 | INFO / WARN / ERROR |
| `pageSize` | 否 | 1~50，默认 5。indexContext 用 5~20；contextId 精查建议 50 |

未声明参数会被工具直接拒绝（skyeye CLI 会静默吞掉未知 flag 造成假空命中，所以前置拦截）。

## 返回错误分类与动作

| errorClass | 含义 | 动作 |
|---|---|---|
| `UK_AMBIGUOUS` | 简称命中多个 uk，`candidates` 列出完整 uk + display | **不要替用户挑**，把候选给用户确认（switch 可能是 OTA 入口或开放平台，两条链路） |
| `UK_NOT_FOUND` | uk 未登记，`nearest` 给近似项 | 让用户确认；不要凭 `titc.java.` 前缀瞎拼。cdm 的完整 uk 是 titc.java.dubbo.core.data，与字面无关 |
| `AUTH_EXPIRED` | 登录态失效（code=1/账号信息为空） | 停下，提示用户在终端执行 `skyeye auth login` 后再让你重试 |
| `RATE_LIMITED` | 429 限流，**不是无数据** | 等 30 秒以上再查，不要连续重试 |
| `BAD_UK` | 传给 SkyEye 的 uk 名不合法，`failedUks` 列出 | 用完整 appUk 整组重查，不要减半 |
| `UK_SET_TOO_LARGE` | 单次 uk 数过多（阈值不稳定） | 逐步减半重试，并说明实际查询范围 |
| `EMPTY` | success=true 但 0 命中（notice.kind=EMPTY） | 见下方空命中扩张顺序 |
| `USAGE` / `SPAWN_FAILED` / `REMOTE_ERROR` | 参数错/本机执行失败/远端其他错误 | 按 message/hint 处理 |

空命中扩张顺序（每步说明当前档位）：①核对时间参数拼写 → ②带 contextId 时扩到 1 跳邻居组重查
（首查阶段空了可在确认关键词后用同 uk 加大 minutes/pageSize）→ ③仍 0 且用户坚持链路更远，**问用户**
是否扩 2 跳/指定 uk，不自动扩 → ④建议调时间窗或关键词。

## 输出解读

- `result.count` 是 SkyEye 侧总量，`result.returned` 是实际压缩返回条数；`returned < count` 时换大 pageSize 重查。
- ERROR/WARN 在 `level` 字段；msg 里带 `...[msg 截断...]` 表示原文更长。
- 命中 SQL/MQ 里出现业务号只是**几百个 IN 值之一**时，不算本次链路证据。

## 回复纪律

- 默认给**信号级时间线**：`<time> - <uk> - <关键摘要>`，只收 ERROR + 入口 + 抛异常点 + 关键状态转换；
  用户说"完整时间线"才全量列出。
- 代码关联交给 `code_lookup`：优先栈里 FQCN+行号，其次异常类名，最后日志文案。
- 数据核对交给 `qa_db_query`（仅 qa），库结果只是旁证；日志与库矛盾列入"待确认点"，不直接当根因。
- 推测与事实分开写；没证据不要把根因说成确定事实。
- 转述日志/库数据时手机号、身份证号要掩码。

## UK 速查表（简称 → 完整 appUk，工具已自动解析，歧义仍需回问）

| 简称/线索 | 完整 appUk | 说明 |
|---|---|---|
| drp.switch / OTA开关 | titc.java.drp.switch | OTA渠道入口 drp-restapi-switch |
| drp.openapi / open | titc.java.drp.openapi | OTA渠道入口 drp-restapi-open |
| dsf.drp.order / drp-order | titc.java.dsf.drp.order | 订单 dsf |
| drp.order.job | titc.java.drp.order.job | 订单 job |
| dsf.drp / drp-dsf | titc.java.dsf.drp | drp.dsf |
| drp.ms | titc.java.drp.ms | drp.ms |
| drp.job（枢纽，9+ 邻居） | titc.java.drp.job | drp.job |
| workbench.gateway | titc.java.drp.workbench.gateway | 工作台网关 |
| ihotel.ms | digaiempower.java.drp.ihotel.ms | ihotel |
| pms.dsf | digaiempower.dsf.java.dsf.drp.pms | drp.pms.dsf |
| dsf.external | digaiempower.dsf.java.dsf.drp.external | drp.dsf.external |
| cloud.pms.pull.job | titc.java.drp.cloud.pms.pull.job | PMS 拉取 |
| alitrip/ctrip/douyin/jd/meituan/standard/te push.job | titc.java.drp.<渠道>.push.job | 各 OTA 推送 job |
| pms.push.job | titc.java.pms.push.job | 开放平台推送 |
| supplier.openapi | titc.java.supplier.openapi | 供应商开放平台 |
| pms.openapi | titc.java.pms.openapi | 开放平台 |
| 开放平台 switch | titc.java.pms.openapi.switch | 注意与 OTA switch 区分 |
| dubbo.pms.order | titc.java.dubbo.pms.order | pms 订单 dubbo |
| cdm / core.data | titc.java.dubbo.core.data | 核心基础数据（appUk 与仓名无字面关系） |
| core.data.job | titc.java.core.data.job | cdm job |
| pms.account | titc.java.dubbo.pms.account | pms 账户 |
| pms.bridge.order | titc.java.pms.bridge.order | pms bridge 订单 |
| pms.bridge.data | titc.java.pms.bridge.data | pms bridge 数据 |
