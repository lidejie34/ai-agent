# dj-agent-chat

Spring Boot 3.4 + Spring AI 1.0.0-M7（OpenAI 兼容方式接入火山方舟）对话最小脚手架：
同步问答 `POST /api/chat` 与 SSE 流式问答 `POST /api/chat/stream`。

- **迭代 3 起支持服务端会话持久化**：请求传 `sessionId` 即可，历史由服务端从 MySQL 加载/落库
  （MyBatis-Plus，表 `chat_session` / `chat_message`）；不传 `sessionId` 保持迭代 1/2 的纯无状态行为。
- `sessionId` 三态：`null`/缺省=无状态（零 DB 交互）；`""`=新建会话（服务端签发 UUID，
  同步响应体 / SSE `event:session` 帧回传）；36 位 UUID=续接（服务端加载最近 N 条历史，
  请求体 `history` 被忽略并 WARN）。非法格式（非 UUID）→ 400。
- **缺库、缺 Key 均可启动**：记忆表启动期 best-effort 懒建表（DB 不可达仅 WARN），
  记忆路径在 DB 不可达时同步返回 503 `MEMORY_UNAVAILABLE`（不拖垮无状态路径）；测试全程离线 mock。

## 环境要求

- JDK **17**（Eclipse Temurin 17.0.20，`/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`）。
  本机默认 JDK 为 1.8，**每个新终端必须先切换**（见下）。
- Maven 3.9+（本机 3.9.9）。

## 本地联调 8 步

```bash
# 1) 切换 JDK17（每个新终端必做）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version    # 期望 openjdk version "17.0.x"（Temurin）
mvn -v           # 期望 Java version: 17.0.x；若显示 1.8 说明切换失败

# 2) 离线全量测试（无需 Key、无需外网、无需数据库，应全绿）
mvn clean test

# 3) 不启动 MySQL/Redis/PG、不填 Key，直接启动应用（验证缺库缺 Key 可启动）
mvn spring-boot:run
#    新开一个终端，不填 Key 调同步接口，应返回 503 ARK_NOT_CONFIGURED：
curl -i -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好"}'

# 4) 填入真实凭证（二选一）后重启
#    方式 A：环境变量
export ARK_API_KEY="ark-你的真实Key"
export ARK_CHAT_MODEL="doubao-seed-2-1-turbo-260628"   # 见下方「Coding Plan 实测可用模型」
#    可选：开启推理模型深度思考（默认 none=关闭，简单对话更快；需要复杂推理时设 high）
# export ARK_REASONING_EFFORT=high
mvn spring-boot:run
#    方式 B：本地 profile
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
#    编辑 application-local.yml 填入真实值后：
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 5) 同步问答（无历史）
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍你自己"}'

# 6) 流式问答（-N 关闭缓冲，观察逐段输出，最后以 event:done / [DONE] 结束）
#    迭代2：模型思考空闲期每 15s 会收到一行 ":keepalive" 注释帧（EventSource 标准忽略，
#    curl 中可见；模型开始出片段后计时重置），用于防网关/浏览器空闲断连。
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"从 1 数到 5，每个数字单独说"}'
#    可用 APP_CHAT_HEARTBEAT_INTERVAL 改间隔（如 5s 便于观察）、APP_CHAT_HEARTBEAT_ENABLED=false 关闭。

# 7) 多轮上下文（带 history 追问，回答应体现上下文）
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"那我刚才说我叫什么？","history":[{"role":"user","content":"我叫小明"},{"role":"assistant","content":"你好，小明！"}]}'

# 8) 迭代3 会话持久化（需本地 MySQL 在 127.0.0.1:13306，库 dj_agent；
#    表由应用启动时 best-effort 自动建（chat_session/chat_message），也可手动执行
#    src/main/resources/db/chat-memory-schema.sql；无 MySQL 时应用照常启动，仅会话路径返回 503）
# 8a) 新建会话：sessionId 传空串，响应体带回服务端签发的 36 位 sessionId
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"我叫小明，帮我记住","sessionId":""}'
#    响应：{"reply":"...","model":"...","sessionId":"<UUID>"}
# 8b) 续接会话：带上 8a 返回的 sessionId，无需再传 history，回答应体现记忆
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"我叫什么名字？","sessionId":"<上一步返回的UUID>"}'
# 8c) 流式新建会话：首个事件帧为 event:session（data:{"sessionId":"<UUID>"}），
#     随后才是 :keepalive 注释帧 / event:message 片段 / event:done
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"记住我喜欢Java","sessionId":""}'
# 8d) 无状态调用与迭代1/2 完全一致：不传 sessionId（或 null），不触发任何 DB 读写
# 8e) 关闭记忆：APP_CHAT_MEMORY_ENABLED=false 重启后，记忆 Mapper/Store bean 不装配，
#     传 sessionId 的请求同步返回 400（会话记忆功能未启用），无状态请求不受影响
# 8f) 真实 MySQL 冒烟清单（总控 step_8 执行）：
#     - 启动日志无建表异常；库中出现 chat_session / chat_message 两表
#     - 8a/8b 后 chat_session 有 1 行、chat_message 成对增长（user/assistant 各 1 行/轮）
#     - 停掉 MySQL 后新会话请求 503 MEMORY_UNAVAILABLE、无状态请求仍 200
#     - 流式中途断网/模型报错：chat_message 无本轮 assistant 孤儿行（错误不落库）
#     - 续接时请求体带 history：服务端 WARN「续接会话忽略请求体 history」且以库中历史为准
#     - 非法 sessionId（如 "abc"）→ 400 BAD_REQUEST

# 9) 【联调首验】若请求返回 404/路径错误，说明方舟端点路径拼接与预期不一致：
#    调整 spring.ai.openai.chat.completions-path（当前 /chat/completions），
#    或改用备选配法 base-url=https://ark.cn-beijing.volces.com/api/coding +
#    completions-path=/v3/chat/completions（两种配法最终 URL 等价，
#    均为 https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions）。
#    模型可在不改动代码的前提下用 ARK_CHAT_MODEL 切换，响应体 model 字段回显实际模型。
```

## 常用命令

```bash
mvn dependency:resolve        # 解析依赖（Spring AI M7 从 Maven Central 获取）
mvn clean test                # 离线全量测试
mvn clean package -DskipTests # 编译打包（target/dj-agent-chat-0.0.1-SNAPSHOT.jar）
```

## 工程结构

```
src/main/java/com/dj/ai/agentchat/
├── DjAgentChatApplication.java        # 启动类
├── controller/ChatController.java     # POST /api/chat、/api/chat/stream（SSE 编排 + 心跳）
├── service/ChatService.java           # 参数校验、多轮消息组装、ChatClient 同步/流式调用、异常归一、
│                                      #   流式首片段前有限重试（Flux.defer + retryWhen）
├── advisor/RequestLoggingAdvisor.java # 迭代2：请求日志 Advisor（Call+Stream 双接口，不记内容/密钥）
├── sse/                               # 迭代2：SseHeartbeatScheduler/ScheduledHeartbeat 抽象 +
│                                      #   DefaultSseHeartbeatScheduler（daemon 线程，可注入/可测）
├── memory/                            # 迭代3：会话持久化
│   ├── ConversationStore.java         #   继承 Spring AI ChatMemory，扩展 createSessionIfAbsent
│   ├── ChatMemoryProperties.java      #   app.chat.memory.*（enabled/max-history/init-on-startup）
│   ├── po/                            #   ChatSessionPO / ChatMessagePO（@TableName/@TableId）
│   ├── mapper/                        #   ChatSessionMapper（INSERT IGNORE 幂等建会话）/
│   │                                  #   ChatMessageMapper（最近 N 条内层 DESC 外层 ASC、按会话删除）
│   └── mybatis/                       #   MybatisChatMemory（@Transactional 成对落库、角色映射）、
│                                      #   ChatMemorySchemaInitializer（AtomicBoolean 懒建表）、
│                                      #   ChatMemorySchemaStartupRunner（ApplicationRunner，best-effort）
├── config/
│   ├── ChatMemoryConfig.java          # 迭代3：@ConditionalOnProperty(enabled 默认 true) 收口记忆全家桶：
│   │                                  #   @MapperScan + Store/Initializer/Runner bean；enabled=false 全不装配
│   ├── web/FastJsonWebConfig.java     # 迭代3：fastjson2 转换器 add(0) 接管 MVC JSON 序列化（默认省略 null）
│   ├── ChatClientConfig.java          # 由自动配置的 ChatClient.Builder 装配 ChatClient（挂载日志 Advisor、
│   │                                  #   app.chat.system-prompt 非空白时条件注入 defaultSystem）
│   ├── http/ChatHttpProperties.java   # 迭代2：app.chat.http.* 连接池/超时配置属性
│   ├── http/SyncHttpClientConfig.java # 迭代2：HttpClient5 连接池 + RestClientCustomizer
│   ├── http/StreamHttpClientConfig.java # 迭代2：Netty ConnectionProvider + WebClientCustomizer
│   └── retry/ChatRetryConfig.java     # 迭代2：自定义 RetryTemplate（3 次指数退避、429/5xx/网络故障）
├── dto/                               # ChatRequest（含 sessionId 三态）/ ChatMessage / ChatResponse（含 sessionId）
│                                      #   / ApiError / StreamChunk / SessionEvent（SSE event:session 帧）
└── exception/                         # ChatNotConfiguredException / ModelCallException /
                                       # InvalidChatRequestException / MemoryUnavailableException(503) /
                                       # MemoryPersistException(500) / GlobalExceptionHandler
src/main/resources/
├── application.yml                    # 入库配置（密钥占位、三套存储连接、Hikari 懒启动、
│                                      #   迭代2 重试/连接池/心跳/system-prompt、迭代3 app.chat.memory.* 全部外置）
├── db/chat-memory-schema.sql          # 迭代3：chat_session / chat_message 建表脚本（CREATE TABLE IF NOT EXISTS，
│                                      #   应用启动与首次记忆路径自动执行；亦可手动执行）
└── application-local.yml.example      # 本地凭证模板（复制为 application-local.yml，已被 gitignore）
```

## 关键说明

- **密钥不入库**：`application.yml` 中 `api-key: ${ARK_API_KEY:ark-placeholder-not-configured}`。
  Spring AI M7 自动配置在 bean 创建期要求 api-key 非空白，故默认值为明显非法的占位串；
  服务层调用前检测到占位值即按「未配置」处理（同步 503 / 流式 `event:error`），不会把占位串发出。
- **缺库可启动**：`spring.datasource.hikari.initialization-fail-timeout: -1` 关闭 Hikari 启动快速失败；
  Lettuce 懒连接；不引 JPA/actuator；Postgres 走 `app.storage.postgres` 自定义命名空间不绑定自动配置。
- **SSE 事件协议**：（新建会话时先发）`event:session`（`data:{"sessionId":"<UUID>"}`）
  → （空闲期）`:keepalive` 注释帧 → `event:message`（`data:{"content":"片段"}`，多帧）
  → `event:done`（`data:[DONE]`）；记忆阶段失败（DB 不可达等）在订阅前同步抛出、
  以 `event:error` 返回且**不会**先发出 session 帧或启动心跳；异常或缺 Key 同样发
  `event:error`（data 为错误 JSON）后立即关闭，不无限挂起；超时 120s（`app.chat.sse-timeout-ms`）。
- **错误码**：400 `BAD_REQUEST`（含非法 sessionId、记忆开关关闭时传 sessionId）/
  503 `ARK_NOT_CONFIGURED`（缺 Key）/ 502 `MODEL_CALL_FAILED`（模型调用失败）/
  503 `MEMORY_UNAVAILABLE`（记忆读写阶段 DB 不可达，仅会话路径；无状态路径不受影响）/
  500 `MEMORY_PERSIST_FAILED`（仅同步路径：模型已成功但本轮落库失败，提示重试本轮）。
  错误响应不含堆栈与密钥；流式路径落库失败仅 ERROR 日志（不影响已推送片段与 done）。
- **会话持久化（迭代3）**：记忆实现走**业务层显式编排**（ChatService 内 prepareMemory/
  persistTurn），不使用 MessageChatMemoryAdvisor（M7 jar 实证 Advisor 消息归并约定下
  显式编排更可控）。记忆阶段（建会话/加载历史）在模型调用前同步完成：DB 不可达直接 503；
  同步路径模型成功后同线程落库（失败 500）；流式路径用 StringBuilder 聚合全文，
  `publishOn(boundedElastic)` 后在 `doOnComplete` 落库（阻塞 JDBC 不占 Netty 事件循环，
  done 帧在落库完成后发出；落库异常仅 log.error 吞掉）。模型出错/取消/重试耗尽**不落库**
  （不留孤儿 user 消息）。每轮成对写入 user + assistant；续接仅把库中历史进 Prompt、
  不重复落库；新建会话的种子 user 消息同时进 Prompt 与落库批次。
- **记忆配置项（迭代3）**：`app.chat.memory.enabled`（环境变量 `APP_CHAT_MEMORY_ENABLED`，
  默认 true；false 时记忆 Mapper/Store/Runner 全家桶不装配，会话请求 400）、
  `app.chat.memory.max-history`（`APP_CHAT_MEMORY_MAX_HISTORY`，默认 20，续接加载最近 N 条；
  落库全量不裁剪）、`app.chat.memory.init-on-startup`（`APP_CHAT_MEMORY_INIT_ON_STARTUP`，
  默认 true，启动 best-effort 建表；false 时首次记忆路径懒触发建表，DB 不可达同样自愈）。
- **fastjson2（迭代3）**：`FastJsonWebConfig` 以 `extendMessageConverters(converters.add(0,...))`
  把 `FastJsonHttpMessageConverter` 置于通用 Jackson 之前，全线 JSON 线网格式由 fastjson2 治理
  （UTF-8、默认省略 null 字段——故无状态响应不回显 sessionId、SSE 无状态流不发 session 帧）；
  record 三态反序列化：字段缺省/null→null、`""`→空串（新建会话语义靠空串区分）。
- **SSE 心跳保活（迭代2）**：模型思考空闲期按 `app.chat.heartbeat.interval`（默认 15s）发注释帧
  `:keepalive`（SSE 标准注释，EventSource 自动忽略，不是 message/done/error 事件）；每个模型片段到达即重置计时；
  完成/出错/超时/客户端断连四条路径都会取消心跳。`curl -N` 可直接观察到 `:keepalive` 行。
  开关 `app.chat.heartbeat.enabled`（环境变量 `APP_CHAT_HEARTBEAT_ENABLED=false` 关闭），
  间隔 `APP_CHAT_HEARTBEAT_INTERVAL`，帧文本 `APP_CHAT_HEARTBEAT_TEXT`。
- **重试策略（迭代2）**：同步路径由自定义 RetryTemplate 接管 `spring.ai.retry.*`——最多 3 次、指数退避
  1s→2x→10s，仅对 429（`on-http-codes` 显式放行）、5xx、网络层故障（连接拒绝/超时，`ResourceAccessException`
  按 cause 链穿透）重试；400/401/404 等 4xx 立即失败（缺 Key 不重试）。流式路径框架不重试，应用层
  仅在**首个模型片段到达前**对同样的瞬时故障重试 3 次；首片段后中断不重放（避免内容重复），直接发 error 事件。
  日志关键字：`模型调用第 N 次尝试失败`、`模型调用重试已耗尽`。
- **HTTP 连接池（迭代2）**：同步走 Apache HttpClient5 连接池（`app.chat.http.sync.*`），
  流式走 Reactor Netty 连接池（`app.chat.http.stream.*`，含 `response-timeout` 兜底模型卡死）；
  池大小/超时全部在 application.yml 外置，生产按并发量上调 pool 上限。
- **系统提示词（迭代2）**：`app.chat.system-prompt`（环境变量 `APP_CHAT_SYSTEM_PROMPT`）非空白时
  自动以 `ChatClient.defaultSystem(...)` 对每次调用生效；留空（默认）保持迭代 1 纯用户对话行为。
- **请求日志 Advisor（迭代2）**：`RequestLoggingAdvisor` 同时挂载同步/流式链路，INFO 记录消息数、model、
  耗时、成功/失败、流式片段数；**只记长度不记内容、绝不记密钥/Authorization 头**（DEBUG 也仅记消息长度）。
  日志关键字：`模型调用开始[call|stream]`、`模型调用成功[call|stream]`、`模型调用失败[call|stream]`。
  该 Advisor 同时是后续 ChatMemory/MCP/RAG 的统一挂载点示范。
- **思考链与响应速度**：Coding Plan 套餐内 doubao/deepseek/glm 均为推理模型，默认会先生成思考链
  （`reasoning_content`），简单问题也要数秒、同步接口尤为明显。配置
  `spring.ai.openai.chat.options.reasoning-effort=none`（入库默认值，环境变量 `ARK_REASONING_EFFORT` 可覆盖）
  可关闭思考，实测简单对话从 ~4.6s 降到 ~1.1s；复杂推理场景改为 `high`。
- **Coding Plan 实测可用模型**（2026-09-01 验证；不在套餐内的模型返回 `UnsupportedModel`，
  如 `doubao-seed-2-1-pro-260628`、`glm-4-5-air-20250728`）：
  - `doubao-seed-2-1-turbo-260628`（通用对话，默认推荐）
  - `doubao-seed-2-0-code-preview-260215`（代码专项）
  - `deepseek-v4-pro-ga-260813` / `deepseek-v4-flash-ga-260731`
  - `glm-5-2-260617`
- **日志级别**：默认 INFO（请求入口、model、耗时、片段数、异常摘要，不打印密钥）；
  打开 DEBUG 可看消息内容与 SSE 每个片段：环境变量 `APP_LOG_LEVEL=debug`，
  或在 `application-local.yml` 配置 `logging.level.com.dj.ai.agentchat: debug`。
