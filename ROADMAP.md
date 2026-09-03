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
迭代3 ✅ 会话持久化：ChatMemory + MySQL（接口加 sessionId；真实 MySQL 冒烟待 step_8）
   ↓
迭代4    MCP 工具接入：Function Calling / MCP Client（模型长出"手"）
   ↓
迭代5    SDD 子 Agent 编排：任务拆解 → 多 Agent 协作
   ↓
按需穿插：可观测性 / Redis 缓存 / RAG 知识库 / 前端对话页 / 安全加固
```

## 主线功能演进

| 迭代 | 能力 | 内容 | 状态 | 需求目录 |
|---|---|---|---|---|
| 迭代 1 | 对话调通 | Spring Boot 3.4 + Spring AI 1.0.0-M7 + JDK17；同步 `POST /api/chat`、SSE 流式 `POST /api/chat/stream`；多轮 history 由请求传入；MySQL/Redis/PG 依赖+配置预留 | ✅ 已交付（26 测试全绿，真实联调通过） | `20260901-...-springai-ark-chat-bootstrap` |
| 迭代 2 | 地基加固 | ① ChatClient Advisor 层（配置化 system prompt + 日志 Advisor 示例，立起 ChatMemory/MCP 挂载点）；② 显式 HTTP 连接池（RestClient/HttpClient5 + WebClient/Netty）；③ 超时 + 3 次指数退避重试（仅网络/429/5xx）；④ SSE 15s 心跳保活 | ✅ 已交付（75 测试全绿：26 既有 + 49 新增；本地真实冒烟 PASS：同步/SSE/心跳帧/400/多轮，冒烟中修复 Advisor 消息数统计 1 处；分支 feat/advisor-resilience-foundation 已推送，commits ae98d61+4da2db4） | `20260902-...-advisor-resilience-foundation` |
| 迭代 3 | 会话持久化（A） | Spring AI `ChatMemory` 契约 + MyBatis-Plus 落 MySQL 13306（chat_session/chat_message，懒建表+启动 best-effort）；接口加 `sessionId` 三态（null 无状态/`""` 新建/UUID 续接）；SSE 新增 `event:session` 帧；fastjson2 接管 JSON；记忆开关 `app.chat.memory.enabled`（false 全家桶不装配、会话路径 400）；503 `MEMORY_UNAVAILABLE` / 500 `MEMORY_PERSIST_FAILED` | ✅ TDD 已交付（146 测试全绿：75 既有 + 71 新增，全程离线 mock；真实 MySQL/方舟冒烟待总控 step_8，冒烟清单见 README 8f）；零 git commit，改动全部留在工作区 | `20260902-...-chatmemory-mysql-persistence` |
| 迭代 4 | MCP 工具（B） | MCP Client / ToolCallback，模型可调用外部工具（查库、调内部接口、搜文档）；工具以 Advisor/ToolCallback 形式挂载 | ⏳ 待开始 | — |
| 迭代 5 | SDD 子 Agent（C） | 任务拆解 → 规划者/执行者多 Agent 编排，对话服务作为底层模型调用能力被复用 | ⏳ 待开始 | — |
| 待定 | RAG 知识库（E） | PG 15432 + pgvector：文档切片 → embedding → 检索增强问答；可复用 MCP 工具能力 | ⏳ 待开始 | — |
| 待定 | 前端对话页（F） | 打字机界面（消费 SSE）、会话管理、停止生成；建议用 Vercel AI SDK 类库 | ⏳ 待开始 | — |

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
