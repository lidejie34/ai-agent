# qa_db_query 使用指南（QA 只读库交叉验证）

经本机 docker 容器 mysql 客户端查 QA 只读库。**只能 SELECT**，写操作在工具层被硬拦截。
product/uat 没有数据库连接，**生产只查日志不查库**；查库仅用于验证日志里的状态/落库是否一致。

## 参数

| 参数 | 必填 | 说明 |
|---|---|---|
| `db` | 是 | 库键，仅两个：`TETitcDRP-qa`（DRP 主库）、`TETitcDrpOrder-qa`（订单库） |
| `table` | 是 | 单表名，标识符白名单（不支持 join/子查询，需要时分多次查） |
| `fields` | 否 | 默认 `*`；逗号分隔列名，不接受表达式/函数 |
| `where` | 否 | ≤200 字符**简单条件**：等值/范围/AND/OR/LIKE/IN；禁写动词、UNION、分号、注释、SLEEP、information_schema 等 |
| `limit` | 否 | 默认 20，硬上限 50（传更大也会夹到 50） |

选库按 uk 的归属：dsf.drp/drp.ms/drp.job 等走 `TETitcDRP-qa`；dsf.drp.order/drp.order.job 走 `TETitcDrpOrder-qa`。
不确定先问，不要两库盲扫。

## 返回

- 成功：`{db, table, sql, columns, rows[], rowCount, truncated}`，mysql --batch 原始 TSV 解析；
  `NULL`→null、空串保留、数值以字符串返回；单元格超 200 字符截断并计 `cellTruncated`。
- `rowCount=0`：**NO_ROW，不是错误、也不是连不上**——可能是时间窗/主键不对，或数据根本没落到该库。
- 错误分类：`USAGE`（SQL 被拦截/参数非法，改写为简单等值条件，不要换编码绕过）、
  `DB_CREDENTIAL_MISSING`（dbs.env 缺密码，提示用户补，不要重试）、
  `DB_AUTH_ERROR`、`DB_BAD_TABLE`（表/列名不对，先用已知表核对）、`DB_UNAVAILABLE`（容器/网络问题）、
  `DB_ERROR`（其他，message 已做密码脱敏）。

## 使用纪律

- 查库是**旁证**：从日志/用户关键词提取业务主键（orderId、hotelId、mchCode…）再查，禁止无 where 扫大表。
- 先确认表/字段存在（管理端或已知实体），再发查询；一条链路里同库只查一次，别重复打。
- 日志说失败但库里是成功态（或相反）→ 写进"待确认点"，不要直接当根因。
- 转述结果时手机号/身份证等 PII 要掩码；不要把查询出来的整行敏感数据原样贴进对话。
