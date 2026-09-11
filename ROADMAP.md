# dj-agent-chat 演进路线图

> 目标：从「调通大模型对话」的最小脚手架，逐步演进为具备会话记忆、工具调用（MCP）、
> 多 Agent 编排（SDD）、知识库（RAG）能力的 ai-agent 工程。
> 本文件随每次迭代更新状态；各迭代的需求/设计文档在 `/Users/lidejie/aiSpecs/<日期>-ai-project-<slug>/`。

## 总体推进顺序

```
迭代1 ✅ 对话调通（最小脚手架）
   ↓
迭代2 ✅ 地基加固：Advisor 层 + 连接池 + 超时重试 + SSE 保活
   ↓ （为后续所有功能铺好 Advisor 挂载点与稳定性底座）
迭代3 ✅ 会话持久化：ChatMemory + MyBatis-Plus/MySQL（sessionId 三态、event:session、fastjson2）
   ↓
迭代4 ✅ MCP 工具接入：MCP Client stdio（模型长出"手"，接入 MCP 生态）
   ↓
迭代5 ✅ SDD 子 Agent 编排：Planner/Executor 顺序循环 + 再规划（任务拆解 → 多 Agent 协作）
   ↓
迭代6 ✅ RAG 知识库：全局单库（PG/pgvector + Ollama bge-m3）+ 常驻检索 Advisor（答案引文件名）
   ↓
迭代7 ✅ 对话内排障三件套：SkyEye 日志两阶段查询 × 外部代码只读定位 × QA 只读库旁证（SCRIPT MVP，Java 零改动）
   ↓
按需穿插：可观测性 / Redis 缓存 / 安全加固

插入迭代 F ✅ 前端对话页：Vite+React+antd5 + 会话管理 REST（MCP 迭代4 应需求延后，F 提前插入；
   仓库已重构为 backend/ + frontend/ 双子目录，后续迭代后端命令均在 backend/ 下执行）
   ↓
插入迭代 G ✅ 工具能力底座：DB 工具注册表 + Function Calling 闭环（模型长出"手"的第一步；
   迭代4 MCP Client 的前置形态——ToolCallback 体系/审计/管理端可直接复用）
   ↓
迭代4 ✅ MCP 工具接入：stdio MCP Client（mcp-core 0.14.0 离线手工装配）——
   everything + filesystem 两 server 启动发现、MCP 回调包装层复用 G 全套横切、
   崩溃降级/孤儿回收/只读管理端（模型的"手"接入整个 MCP 工具生态）
   ↓
插入迭代 H ✅ 管理端可视化页面：纯前端 Admin Console（hash 路由 #/admin + Token 登录 +
   MCP 只读面板 + 工具注册表 CRUD + 审计日志页，零新依赖/后端零改动；
   冒烟修复迭代G潜伏的 MyBatis-Plus 分页拦截器缺失）
   ↓
迭代5 ✅ SDD 子 Agent 编排：Planner 首调路由（direct 直答/plan 拆解）→ Executor 顺序
   执行 → 再规划循环 → Planner 兼汇总流式；app.sdd.* 全外置默认关（关闭与迭代4 逐字节一致）；
   Executor 共享对话链路同一 ToolMount（DB+MCP 全量工具/帧/审计/幂等/脱敏横切零改动继承），
   Planner 不挂工具、编排层不触达 ConversationStore；event:plan/event:task 帧 + 前端 Steps
   竖向面板（工具块上方）；agent_orchestration_run 懒建表审计（run_id 与工具审计 call_id
   前缀关联）；六类触顶强制收尾；真实方舟+MCP+MySQL 冒烟 7 组全 PASS 零修复
   ↓
迭代6 ✅ RAG 知识库（E 线）：「维持 M7 + 自写轻量件」——不升级 Spring AI，仅用 M7
   EmbeddingModel 抽象；PG 第二数据源（@ConditionalOnProperty，懒连接不阻断启动）+
   自写 pgvector 仓储（余弦 <=>）；标题感知切片（500 字/80 重叠）；常驻 RagAdvisor 双态
   （topK=4/阈值 0.45，注入 userText+引用规则 system，答案附「参考资料：文件名」）挂同步/
   SSE/Executor 三处，Planner/Synth 不检索；无命中放行、故障 WARN 降级普通对话不虚构引用；
   管理端 /api/admin/kb（health 分项探测/上传 multipart/列表/删除级联/重建索引/同名覆盖事务）；
   前端知识库 Tab（健康 Alert 不轮询、上传、表格、重建、Popconfirm 删除）；真实
   Ollama bge-m3+PG+方舟冒烟 SMOKE-RAG-1~8 全 PASS 零修复
   ↓
迭代7 ✅ 对话内排障三件套（SCRIPT MVP）：skyeye_query_log（uk 简称解析/首查单 uk/expand=1
   无向 1 跳组/env 白名单/错误分类）+ code_lookup（外部业务仓 FQCN±上下文/grep -rnF，
   canonical 闸门）+ qa_db_query（docker 容器 mysql 通道，SELECT 闸门/LIMIT 硬顶/凭据外置
   dbs.env）；配置在仓库外 ~/.ai-agent/troubleshoot，仓内仅脱敏模板与登记清单；
   node:test 114 例、后端 699 零回归；真实 qa 日志+业务仓+双 QA 库冒烟 SMOKE-T1~8 全 PASS
```

## 主线功能演进

| 迭代 | 能力 | 内容 | 状态 | 需求目录 |
|---|---|---|---|---|
| 迭代 1 | 对话调通 | Spring Boot 3.4 + Spring AI 1.0.0-M7 + JDK17；同步 `POST /api/chat`、SSE 流式 `POST /api/chat/stream`；多轮 history 由请求传入；MySQL/Redis/PG 依赖+配置预留 | ✅ 已交付（26 测试全绿，真实联调通过） | `20260901-...-springai-ark-chat-bootstrap` |
| 迭代 2 | 地基加固 | ① ChatClient Advisor 层（配置化 system prompt + 日志 Advisor 示例，立起 ChatMemory/MCP 挂载点）；② 显式 HTTP 连接池（RestClient/HttpClient5 + WebClient/Netty）；③ 超时 + 3 次指数退避重试（仅网络/429/5xx）；④ SSE 15s 心跳保活 | ✅ 已交付（75 测试全绿：26 既有 + 49 新增；本地真实冒烟 PASS：同步/SSE/心跳帧/400/多轮，冒烟中修复 Advisor 消息数统计 1 处；分支 feat/advisor-resilience-foundation 已推送，commits ae98d61+4da2db4） | `20260902-...-advisor-resilience-foundation` |
| 迭代 3 | 会话持久化（A） | Spring AI `ChatMemory` 契约 + MyBatis-Plus 落 MySQL 13306（chat_session/chat_message，懒建表+启动 best-effort）；接口加 `sessionId` 三态（null 无状态/`""` 新建/UUID 续接）；SSE 新增 `event:session` 帧；fastjson2 接管 JSON；记忆开关 `app.chat.memory.enabled`（false 全家桶不装配、会话路径 400）；503 `MEMORY_UNAVAILABLE` / 500 `MEMORY_PERSIST_FAILED` | ✅ 已交付（146 测试全绿：75 既有 + 71 新增；真实 MySQL 8 + 方舟冒烟 PASS：自动建表、首轮新建/续接多轮记忆、event:session、成对落库中文 emoji 完整、无状态零 DB；分支 feat/chatmemory-mysql-persistence 已推送 commit ac0febc） | `20260902-...-chatmemory-mysql-persistence` |
| 插入迭代 G | 工具能力底座（G） | DB 驱动的工具注册表：`agent_tool`/`agent_tool_call_log` 两表（懒建表+种子），工具元数据 DB 维护、运行时**动态组装 ToolCallback**（新增同类型工具=插 DB 行不发版）；BUILTIN 日志分析（纯 JDK 扫 logs/：时间窗/级别计数/异常分组/堆栈片段）+ SCRIPT 白名单执行器（canonical 防逃逸、argv 数组禁 sh -c、超时强杀、环境净化、示例脚本）；管理端 `/api/admin/tools` CRUD + 审计分页（**X-Admin-Token 首个鉴权接口**：503 未配置/401 未授权/400 开关关闭）；SSE 新增 `event:tool` 帧 + ToolCallBridge（ToolContext 透传）+ 前端工具调用折叠块；工具结果密钥脱敏、三层去重（requestId+dedupKey+call_id）防流式重试重跑 | ✅ 已交付（后端 **406** 测试全绿：219 既有 + 187 新增；前端 **95** 测试全绿、tsc/build 零错误；真实 MySQL+方舟+Vite proxy 冒烟 SMOKE-1~6 全 PASS **零冒烟修复**：工具全链路/event:tool 帧序/假密钥脱敏/SCRIPT 真实执行/审计/401·400·404；TDD 期实测修复 M7 toolContext 禁 null 值 1 处；分支 feat/db-tool-log-analysis 已推送 commit 4342aff） | `20260904-...-db-tool-log-analysis` |
| 迭代 4 | MCP 工具（B） | **MCP Client（stdio）接入外部工具生态**：选型 mcp-core 0.14.0 离线手工装配（不引 starter/webflux），`app.tools.mcp.*` yml 白名单声明 server（重启生效）；启动 eager 握手+listTools 发现（everything 13 + fs 14 = 27 工具），失败隔离 UNAVAILABLE 不阻断、崩溃标记不自动重启、stderr 消费、@PreDestroy closeGracefully 零孤儿；发现工具经 **McpToolCallback 包装层**挂载（SyncMcpToolCallback 不支持 ToolContext，包装层复用 G 的 event:tool 帧/审计 handler_type=MCP/脱敏/截断/超时/三层幂等/全捕获）；`<server>_<tool>` 命名规整、与 DB 工具同点合并挂载（冲突跳过 WARN、空配置与 G 逐字节一致）；管理端只读 `GET /api/admin/mcp/servers`（无 env）+ 审计 handlerType 过滤 + tool_name 列放宽 VARCHAR(128) 幂等迁移 | ✅ 已交付（后端 **494** 测试全绿：406 既有 + 88 新增；前端 **95** 零改造；真实方舟+MySQL+双 server 冒烟 SMOKE-0/2~10 全 PASS：echo 全链路/fs 目录内读写/越权拒绝/崩溃 UNAVAILABLE/无孤儿/审计 MCP 过滤/401·405；冒烟修复 1 处低危——UNAVAILABLE 视图清空工具清单；分支 feat/mcp-tool-client 已推送 commits 1df1828+a3b40a6） | `20260904-...-mcp-tool-client` |
| 插入迭代 H | 管理端可视化页面（H） | **纯前端管理控制台**：自写 hash 路由 `#/admin`（~35 行，对话页常驻 CSS 切换不丢状态，AdminConsole React.lazy 分包）；Admin Token 登录（内存+localStorage 独立键静默降级、验证期暂存内存、401 client 层广播顶层登出去重、仅 /api/admin/** 注头）；MCP 服务器只读面板（Badge/command/args/工具清单 Collapse/lastError Alert，无 env 无写、写方法 405）；工具注册表 CRUD（列表/启停 Switch 乐观更新失败回滚/详情抽屉/新建编辑表单预校验+Modal.confirm+PATCH 全字段不带 name+400 中文透传+503 已落库文案/删除二次确认）；审计日志页（0 基分页、toolName/sessionId/status/handlerType/时间范围过滤、失败行展开 errorMessage、手动刷新不轮询）；长文本一律 `<pre>` 纯文本不渲染 markdown/HTML；错误码差异化中文文案 | ✅ 已交付（前端 **170** 测试全绿：95 既有零修改 + 75 新增；后端 **495** 测试全绿；tsc 0 错误、vite build 独立懒加载 chunk、零新依赖；真实后端+双 MCP server+Vite proxy 冒烟 11 组全 PASS：**冒烟修复 1 处中危——迭代 G 潜伏的 MyBatis-Plus 分页拦截器缺失（审计分页 total=0/未分页）**；分支 feat/admin-management-ui 已推送 commits 1bb2052+a3c3bb7） | `20260907-...-admin-management-ui` |
| 迭代 5 | SDD 子 Agent（C） | **Planner/Executor 顺序循环 + 再规划**（Plan-and-Execute）：`app.sdd.*` 14 键全外置、默认 `enabled=false`（条件装配 + ObjectProvider 收口，关闭与迭代4 逐字节一致）；Planner 首调结构化路由（`direct` 一次调用直答 24 码点拆帧 / `plan` 任务拆解），Executor 顺序执行任务后 Planner 再规划（`next`/`final`），Planner 兼 Synth 流式汇总；协议容错四道防线（代码块/平衡括号提取→fastjson2→重试1→路由失败降级直答/再规划失败强制收尾）；**Executor 共享对话链路同一 ToolMount**（Flux 外创建、DB+MCP 全量工具、同一 bridge/requestId/toolContext，event:tool 帧/审计/三层幂等/脱敏/超时横切零改动继承），Planner/Synth 不挂工具；编排层不注入 ConversationStore（历史以 List&lt;Message&gt; 瞬态传入，chat_message 仅落 user+最终答案）；每次模型调用新建 `prompt()` spec（M7 Advisor 链独立性实证）、sdd-orchestrator/sdd-model-call 双 daemon 池、总预算 SSE 110s/同步 55s 先于容器超时；六类触顶（轮次6/任务8/连续失败2/墙钟/重复规划/任务超时）强制收尾且答案标注未完成；SSE 新增 `event:plan`/`event:task` 帧（OrchEventBridge，五终止路径 detach、fastjson2 null 省键、error 脱敏≤500）；前端 PlanTaskBlocks（antd Steps 竖向、taskId upsert 原地更新、挂工具块上方、仅当前流式消息持有）；`agent_orchestration_run` 懒建表 best-effort 审计（role PLANNER/EXECUTOR/SYNTH、run_id=挂载 requestId 与 agent_tool_call_log.call_id 前缀关联、无外键无 admin 页）；请求体可选 `Boolean sdd` 三态（null 随开关/false 绕过/开关关时 true warn 忽略） | ✅ 已交付（后端 **595** 测试全绿：495 既有断言零修改 + 100 新增；前端 **186** 测试全绿：170 既有 + 16 新增；tsc 0 错误、vite build 通过；真实方舟+MySQL+双 MCP server+Vite proxy 冒烟 7 组全 PASS **零冒烟修复**：direct 判定/plan 多任务+MCP 工具真实挂载/再规划台账/sdd:false 绕过/轮次触顶 truncated+强制收尾/两审计表前缀关联/代理透传；2 个 low 观察项（SYNTH 审计 model 列为空、Planner 偶发冗余任务无副作用）；分支 feat/sdd-subagent-orchestration 已推送 commit c3b33a1） | `20260908-...-sdd-subagent-orchestration` |
| 迭代 6 | RAG 知识库（E） | **全局单库 + 常驻检索增强**（「维持 M7 + 自写轻量件」，不升级 Spring AI，仅用 M7 `EmbeddingModel` 抽象）：`app.rag.*` 全外置默认 `enabled=false`（RagRuntimeConfig 全 `@Bean` 显式声明 + ObjectProvider 收口，关闭时 PG 第二数据源/Hikari 池/Ollama 客户端/Advisor 均不装配，`/api/admin/kb/**` 拦截器路径闸门 503 `KB_DISABLED`，对话与迭代5 逐字节一致）；PG 16/pgvector 第二数据源（MySQL 仍 @Primary，Hikari 懒连接不阻断启动，扩展+`rag_document`/`rag_chunk` 首次访问 best-effort 建）；TextChunker 标题感知切片（`#{1,3}` 切段、max-chars=500/overlap=80、max-chunks=2000 硬顶）；本机 Ollama bge-m3（OpenAI 兼容 /v1/embeddings，1024 维，连接+读 10s 超时，分批 embedding）；KbRepository 余弦（`<=>`）top-k 检索 + 同名覆盖「删旧级联→新建」单事务；**RagAdvisor 常驻非工具**（同步/SSE/SDD Executor 三处请求级挂载，Planner/Synth 不检索）：问题 embedding→topK=4→阈值 0.45 过滤→片段注入 userText + 「只依据资料作答、末尾列参考资料文件名（去重）」注入 system；无高于阈值命中按原请求放行（不虚构引用），embedding/检索异常 WARN 降级普通对话不阻断；管理端 `KbAdminController`：`GET /health`（Ollama 探活+PG 双计数独立 try/catch，任一计数失败双计数归零无半截值）、`POST /documents`（multipart 字段 file，扩展名白名单 md/markdown/txt + UTF-8 校验 + 10MB 上限，201 READY；向量化失败 502 KB_EMBEDDING_FAILED）、列表（不含原文）/DELETE 级联 204/`reindex` 用存文重建（失败置 FAILED 可重试）；错误码 KB_DISABLED/KB_INVALID_FILE/KB_FILE_TOO_LARGE/KB_NOT_FOUND/KB_EMBEDDING_FAILED/KB_STORE_FAILED；前端第 4 个 Tab「知识库」：健康 Alert（Ollama/PG 徽标+文档/切片/维度，仅手动刷新不轮询）、antd Upload customRequest（禁手设 Content-Type，accept .md,.markdown,.txt 单选）、文档表格（大小/切片数/状态 Tag/错误 tooltip/更新时间/重建索引/Popconfirm 危险删除级联提示）、KB_* 中文文案，AdminConsole 独立懒加载 chunk，对话页零改动 | ✅ 已交付（后端 **699** 测试全绿：origin/master 基线 568（Surefire 实测聚合，含迭代5 口径偏差修订）+ 本迭代新增 **131**（rag 包 115 + ChatServiceRagAdvisorMountTest 5 + AdminGateResolverTest 5 + ExecutorClient 2 + AdminAuthInterceptor 4），既有测试仅构造器补 ObjectProvider 适配、断言语义零改动；前端 **201** 测试全绿：186 既有 + 15 新增（KbPage 15；hash 路由 kb 用例并入既有用例不新增 it 块）；tsc 0 错误、vite build 通过；真实 Ollama bge-m3 + PG/pgvector + 方舟 + Vite proxy 冒烟 **SMOKE-RAG-1~8 全 PASS、零冒烟修复**：健康检查 401/200、上传落库一致、中文知识问答带「参考资料：文件名」、无关问题无引用放行、同名覆盖旧片段不双份、删除级联后不再引用、Ollama SIGSTOP 冻结→health 分项报红+10s 超时+对话降级 200+SIGCONT 恢复、独立实例 enabled=false 零 RAG 装配+503；另验 SSE 引用帧/reindex/`.pdf` 400/404；TDD 期修复健康探测半截计数 1 处；分支 feat/rag-knowledge-base，step_7 统一提交推送） | `20260910-...-rag-knowledge-base` |
| 迭代 7 | 对话内日志×代码×QA 库联合排障三件套（SCRIPT MVP） | 把 analyzing-logs-with-code skill 的两阶段方法论搬进对话：`skyeye_query_log`（既有行增强：`--uk` 简称三级解析 精确→后缀→分段子序列/子串、歧义 UK_AMBIGUOUS 列候选不替用户选、首查强制单 uk、`--expand=1` 必须配 contextId 拼无向 1 跳邻居组不做传递闭包、枢纽 >8 hubWarning；env 白名单 qa/uat/product 拒 stage；argv 白名单前置拦截防 skyeye CLI 静默丢参造假空；错误分类 AUTH_EXPIRED/RATE_LIMITED(429 等 30s)/BAD_UK/UK_SET_TOO_LARGE/EMPTY；nodeBin/tsEntry 外置）、`code_lookup`（uk→外部业务仓 code_root/code_include：fqcn 模式 FQCN+行号取±40 上下文（max 80）、包名校验排同名、多命中≤10、最近方法签名、git HEAD；grep 模式 `-rnF --include=*.java --exclude-dir` 固定串≤20 命中；realpath canonical 闸门防 `..`/绝对路径/符号链接逃逸；CODE_ROOT_UNREADABLE 非致命）、`qa_db_query`（仅两个 QA 只读库，经本机 `docker exec -i -e MYSQL_PWD db-mysql-1 mysql --batch --raw` 通道；SELECT 词法闸门：标识符白名单/写动词+UNION/SLEEP/OUTFILE/information_schema 黑名单/分号注释十六进制禁/where≤200/LIMIT 默认 20 硬顶 50；TSV 解析 NULL→null、单元格截断；错误分类 DB_CREDENTIAL_MISSING/AUTH_ERROR/BAD_TABLE/UNAVAILABLE，stderr 密码 replaceAll 兜底）；环境差异全外置到仓库外 `~/.ai-agent/troubleshoot/config.json`（os.homedir() 解析，不依赖被执行器净化的 HOME）+ `dbs.env`（600，MYSQL_PWD 注入不进 argv），仓内只带脱敏模板与交付登记清单 JSON；模型指南母版 scripts/guides/ 三件（两阶段纪律/空命中扩张序/429·auth 话术/FQCN→异常→文案优先级/信号级时间线默认/事实推测分离/PII 掩码）；**Java/前端零改动**，登记行仍由管理端维护（冒烟时 PATCH+POST，无种子 SQL） | ✅ 已交付（node:test 原生 runner **114** 测试全绿零 npm 依赖：5 纯函数库 config/uk/security/argv/skyeye-error 69 + skyeye runner 19 + code_lookup 12 + qa_db 14；后端 **699** 逐字节零回归（无 Java 变更面）；前端 201 不涉及；真实 SkyEye qa + ~/tc/lvzhi 真实业务仓 + 双 QA 只读库冒烟 **SMOKE-T1~8 全 PASS、零冒烟修复**：单 uk 首查拿到 contextId→expand 组 3 uk/27 条、FQCN 命中 CTripAdapter:1506+方法+git HEAD、QA 真实 SELECT/NO_ROW/坏表分类/双库连通、歧义/未知 uk/stage/expand/注入/路径逃逸全拦、缺凭据话术、三轮真实 SSE 对话 event:tool 帧序+审计 51/52/53 SCRIPT/SUCCESS、结果与日志无密码、8080(pid 15777)未触碰、8081 用后释放；分支 feat/skyeye-log-code-tools，step_7 统一提交） | `20260911-...-skyeye-log-code-tools` |
| 插入迭代 F | 前端对话页（F） | 仓库重构为 `backend/`+`frontend/` 双子目录（T0 纯 git mv，历史保留、146 基线零回归）；后端新增会话管理 REST（`/api/sessions` 列表/历史/重命名/删除，400/404/503 齐备）+ 首轮用户消息自动生成会话标题（20/30 codePoint 截断，best-effort）；前端 Vite5+React18+TS+antd5：fetch 手写 SSE 分帧消费（30s 看门狗、AbortController 双 reason 停止/超时）、会话侧边栏（列表/切换/重命名/删除/骨架/重试，流式中切换阻止）、Markdown 渲染（gfm+highlight，禁 rehype-raw，XSS 回归测试、代码块复制）、Enter/Shift+Enter/IME 输入、自动贴底滚动、错误码差异化文案、草稿与刷新恢复（localStorage 仅存非敏感 UI 状态）；生产同源部署（后端无 CORS，nginx 反代 `proxy_buffering off` 等 SSE 指令） | ✅ 已交付（后端 **219** 测试全绿：146 既有 + 73 新增；前端 **83** 测试全绿、`tsc -b && vite build` 零错误；真实 MySQL 8 + 方舟 + Vite proxy 双进程冒烟 PASS：页面/proxy/会话 CRUD/标题落库/级联删除/三态/400/404 全过；冒烟修复 2 处线网问题——SSE 帧被 fastjson2 JSON 转义（迭代3 潜伏）、MySQL UTC 时间差 8h；分支 feat/frontend-chat-ui 已推送 commits 98f734e+a59e4f8） | `20260903-...-frontend-chat-ui` |

## 横切工程优化（地基类，按需穿插）

| 方向 | 内容 | 状态 |
|---|---|---|
| Advisor 扩展层 | system prompt、Advisor 机制（ChatMemory/MCP/RAG 的统一挂载点） | ✅ 迭代 2 已落地（RequestLoggingAdvisor 双接口 + 条件 defaultSystem） |
| HTTP 连接池 | RestClient(HttpClient5) + WebClient(Netty) 连接复用、池参可配 | ✅ 迭代 2 已落地（app.chat.http.* 全外置） |
| 超时与重试 | 显式超时、指数退避重试、SSE 心跳 | ✅ 迭代 2 已落地（同步 RetryTemplate 3 次指数退避；流式首片段前 retryWhen；15s 注释帧心跳） |
| 可观测性（③） | Spring AI Observation（token 用量/模型耗时）、traceId、慢请求统计；Micrometer | ⏳ 待排 |
| Redis 缓存（D） | 热点问答/embedding 缓存、会话热数据（Lettuce 已在 classpath，启用需补 commons-pool2） | ⏳ 待排 |
| 接口契约增强（⑤） | 请求级覆盖 model/temperature/maxTokens、sessionId | ✅ sessionId 随迭代 3 落地（三态：null/空串/UUID；同步响应体与 SSE event:session 回传）；model/temperature 覆盖仍待排 |
| 安全加固（⑥） | 接口鉴权、Key 走环境变量/配置中心 | ⏳ 待排 |
| 测试增强（⑦） | WireMock 模拟方舟 SSE 的契约测试，补充全 mock 之外的集成验证 | ⏳ 待排 |

## 已验证的环境事实（2026-09 实测，后续迭代复用）

- 方舟 Coding Plan 端点：`https://ark.cn-beijing.volces.com/api/coding/v3`，chat 路径 `/chat/completions`（已联调验证）
- 套餐内可用模型：`doubao-seed-2-0-mini-260428`（快，当前默认）、`doubao-seed-2-1-turbo-260628`、`doubao-seed-2-0-code-preview-260215`、`deepseek-v4-pro/flash-ga`、`glm-5-2-260617`；`doubao-seed-2-1-pro`、`glm-4-5-air` 不在套餐内
- 推理模型默认生成思考链（简单请求也可达数秒~数十秒）；`reasoning-effort: none` 可关闭（实测 4.6s→1.1s），复杂推理设 `high`
- 本地依赖仓库在 `~/tc/tc_resp`；构建必须显式 JDK17（`/Library/Java/JavaVirtualMachines/temurin-17.jdk`）
- Spring AI M7 重试（RetryTemplate）只覆盖同步 `internalCall`；流式 `internalStream` 不重试，需应用层 `retryWhen`；
  且 M7 Advisor 链为有状态 Deque（逐订阅 pop），流式重试的重订阅必须把 ChatClient 装配包在 `Flux.defer` 中重建链，
  否则抛 "No AroundAdvisor available to execute"
- M7 `OpenAiChatAutoConfiguration` 不消费自定义 `OpenAiApi` bean；HTTP 层定制走 Boot 的
  `RestClientCustomizer` / `WebClientCustomizer`（作用于自动配置 ObjectProvider 提供的 Builder）
- `spring.ai.retry.on-http-codes` 命中的状态码在 M7 ResponseErrorHandler 中分类为 Transient（可重试）；
  默认 `on-client-errors=false` 时其余 4xx 为 NonTransient、5xx 为 Transient
- **【迭代3 ChatMemory 关键】M7 Advisor 视角的消息归并**（字节码实证 `DefaultChatClient.toAdvisedRequest`）：
  `chatClient.prompt().messages(列表)` 传入的**最后一条 UserMessage 会被提升为 `AdvisedRequest.userText()`
  并从 `messages()` 移除**（subList(0,n-1)）；Advisor 视角 `messages()`=仅历史消息、`userText()`=本轮用户消息、
  `systemText()`=系统提示（toPrompt 时渲染为 SystemMessage）。MessageChatMemoryAdvisor 往 `messages()`
  塞历史的模式与此约定天然兼容；自定义 Advisor 统计消息数须三者相加（迭代2 冒烟实测修复过此缺陷）
- SSE 心跳冒烟实测（2026-09-02）：reasoning-effort=high 时模型思考期 ~11s 无内容帧，5s 间隔的 `:keepalive`
  注释帧准时到达且首片段后停止；心跳帧为 SSE 注释（`:keepalive\n`），EventSource 标准忽略
- Netty 在 macOS 缺 `netty-resolver-dns-native-macos` 时启动打印一条 ERROR 级 DNS 提示，回退 JDK 系统
  DNS，不影响功能（开发机无害警告，生产 Linux 无此现象）
- **【迭代3 实证】** mybatis-plus-spring-boot3-starter 3.5.5 + fastjson2 2.0.61（含 fastjson2-extension-spring6）
  均在 `~/tc/tc_resp`；@MapperScan 标在 @ConditionalOnProperty 的 @Configuration 上时，开关 false 连 Mapper
  代理 bean 都不注册（@Mapper/Repository 构造型会被组件扫描无条件捞起，勿与条件装配混用）
- **【迭代3 实证】** 全量 @SpringBootTest 上下文中 spring-data-web 的 ProjectingJackson2HttpMessageConverter
  会经其自身 WebMvcConfigurer 插到转换器链最前（仅对投影代理 canWrite）；fastjson2 只需排在「通用
  MappingJackson2HttpMessageConverter」之前即可治理普通 DTO 的 JSON 线网格式
- **【迭代3 实证】** Hikari `initialization-fail-timeout: -1` 下 MySQL 不可达上下文照常刷新；
  DataSource.getConnection() 首次取用才失败，记忆层以此实现「无状态零 DB、会话路径 503」的懒失败语义
- **【迭代6 实证】** 条件装配红线：RAG 全家桶一律在 `RagRuntimeConfig` 以显式 `@Bean` 声明
  （`@ConditionalOnProperty(app.rag.enabled)`），任何 RAG 类都不加 `@Service/@Repository/@Component`
  构造型——否则开关 false 也会被组件扫描捞起，倒逼数据源 bean 初始化；消费侧统一
  `ObjectProvider<T>` 收口，控制器始终被扫描（关闭时拦截器路径闸门先返 503，ObjectProvider 空仅兜底）
- **【迭代6 实测】** Ollama bge-m3（本机原生 :11434，OpenAI 兼容 `/v1/embeddings`）输出 1024 维；
  中文知识相关片段余弦 ~0.78、无关问题（兵马俑 vs 差旅制度）~0.37，阈值 0.45 分界清晰；
  `kill -STOP` 冻结 ollama/llama-server 可无损模拟宕机（embed 在 10s read timeout 失败，
  `kill -CONT` 即恢复）；pgvector 余弦距离操作符 `<=>`，建索引只需 `CREATE EXTENSION vector`
- **【迭代6 实证】** multipart 上传前端绝不能手设 Content-Type——浏览器自动生成
  `multipart/form-data; boundary=...`，手设会丢 boundary 导致 400；antd 5 双汉字按钮
  accessible name 会插空格（「删 除」），测试须用 `/删\s*除/` 正则；Vite 5 dev server
  仅绑 IPv6 `::1`，curl 用 `localhost`，`127.0.0.1` 得 HTTP 000
- **【迭代7 实证】** ScriptToolHandler 净化环境（PATH=/usr/bin:/bin、无 HOME）下，node 仍可用
  `os.homedir()` 从 passwd 取到真实家目录——外部配置放 `~/.ai-agent/` 不依赖 HOME env；
  本机无 mysql 客户端时，`docker exec -i -e MYSQL_PWD=<pwd> db-mysql-1 mysql -h <QA host>`
  是可用只读通道（容器 mysql 8.0.46 实测可达 qa.cdb.17usoft.com 两端口），密码只经 `-e`
  注入进程环境、不进 SQL argv；团队化/服务器化应改 `--env-file` 600 临时文件（防本机 ps 可见）
- **【迭代7 实证】** skyeye queryLog CLI 会**静默丢弃未知 flag** 并降级默认 15 分钟窗
  （`--startTime` 拼错 → success=true 假空命中），故 argv 白名单必须在脚本层前置拦截；
  `部分应用查询失败,失败应用参数信息=[...]` 单句文案两成因（简称 uk vs uk 数过多），
  以方括号内名字是否带 `titc.|digaiempower.` 前缀分类，实测 qa 3 uk 组 27 条秒级返回
