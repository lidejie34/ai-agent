# dj-agent-chat

Spring Boot 3.4 + Spring AI 1.0.0-M7（OpenAI 兼容方式接入火山方舟）对话工程，
迭代 4 起为前后端双模块：

- `backend/`：同步问答 `POST /api/chat`、SSE 流式问答 `POST /api/chat/stream`、
  会话管理 REST（`/api/sessions`）+ 服务端会话持久化（MySQL/MyBatis-Plus）。
- `frontend/`：Vite + React 18 + TypeScript + antd 5 对话页，fetch 手写 SSE 分帧消费流式接口，
  会话侧边栏（列表/切换/重命名/删除）、Markdown 渲染、停止生成、草稿与刷新恢复。

**前后端同源部署**：后端不启用 CORS，开发期由 Vite proxy、生产期由 nginx 反向代理把 `/api`
转发到后端，浏览器只访问同源地址（业务代码中只有相对路径 `/api/...`，无硬编码后端主机）。

- 会话 `sessionId` 三态：`null`/缺省=无状态（零 DB 交互）；`""`=新建会话（服务端签发 UUID，
  同步响应体 / SSE `event:session` 帧回传）；36 位 UUID=续接（服务端加载历史，请求体 `history`
  被忽略并 WARN）。非法格式（非 UUID）→ 400。
- **缺库、缺 Key 均可启动**：记忆表启动期 best-effort 懒建表（DB 不可达仅 WARN），
  记忆路径在 DB 不可达时返回 503 `MEMORY_UNAVAILABLE`（不拖垮无状态路径）；测试全程离线 mock。

## 环境要求

- 后端：JDK **17**（Eclipse Temurin 17，`/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`；
  本机默认 JDK 为 1.8，**每个新终端必须先切换**）、Maven 3.9+（本机 3.9.9，离线仓库 `~/tc/tc_resp`）。
- 前端：Node 18+（推荐 20/22）、npm 10+。

## 快速开始

### 后端（在 `backend/` 目录执行）

```bash
cd backend

# 1) 切换 JDK17（每个新终端必做）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version    # 期望 openjdk version "17.0.x"（Temurin）
mvn -v           # 期望 Java version: 17.0.x；若显示 1.8 说明切换失败

# 2) 离线全量测试（无需 Key、无需外网、无需数据库，应全绿；216 测试）
mvn -o test

# 3) 不启动 MySQL、不填 Key，直接启动（验证缺库缺 Key 可启动；无 Key 调对话返回 503 ARK_NOT_CONFIGURED）
mvn -o spring-boot:run

# 4) 填入真实凭证后重启（二选一）
#    方式 A：环境变量
export ARK_API_KEY="ark-你的真实Key"
export ARK_CHAT_MODEL="doubao-seed-2-1-turbo-260628"   # 见下方「实测可用模型」
mvn -o spring-boot:run
#    方式 B：本地 profile（文件已被 gitignore，绝不入库）
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
#    编辑 application-local.yml 填入真实值后：
mvn -o spring-boot:run -Dspring-boot.run.profiles=local
```

接口自测：

```bash
# 同步问答
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍你自己"}'

# 流式问答（-N 关闭缓冲，逐段输出，event:done / [DONE] 结束；空闲期每 15s 一行 ":keepalive"）
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"从 1 数到 5，每个数字单独说"}'

# 会话管理（迭代4）
curl http://localhost:8080/api/sessions                          # 会话列表（含末条消息预览）
curl http://localhost:8080/api/sessions/<UUID>/messages          # 某会话升序历史
curl -X PATCH http://localhost:8080/api/sessions/<UUID> \
  -H 'Content-Type: application/json' -d '{"title":"新标题"}'     # 重命名
curl -X DELETE http://localhost:8080/api/sessions/<UUID>         # 删除会话及其消息
```

会话持久化冒烟（需本地 MySQL 在 127.0.0.1:13306，库 `dj_agent`；表由应用启动时 best-effort
自动建，亦可手动执行 `backend/src/main/resources/db/chat-memory-schema.sql`；无 MySQL 时应用照常启动，
仅会话路径返回 503）：

```bash
# 新建会话：sessionId 传空串，响应/首帧带回服务端签发的 36 位 sessionId
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"我叫小明，帮我记住","sessionId":""}'
# 续接：带上返回的 sessionId，无需 history，回答体现记忆
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"我叫什么名字？","sessionId":"<UUID>"}'
# 关闭记忆：APP_CHAT_MEMORY_ENABLED=false 重启后记忆 bean 不装配，带 sessionId 的请求 400，无状态不受影响
```

### 前端（在 `frontend/` 目录执行）

```bash
cd frontend
npm install        # 首次安装依赖（版本已在 package.json 锁定主版本）

npm run dev        # 开发服务器（默认 5173），/api 经 Vite proxy 转发到后端
npm run test       # vitest 全量单测（jsdom，无需后端；83 测试）
npm run build      # tsc 严格类型检查 + 生产构建（dist/）
npm run preview    # 本地预览生产构建
```

开发期后端地址由环境变量控制（`.env.development` 已随仓库提交，仅含非敏感默认值）：

```bash
# frontend/.env.development
VITE_DEV_PROXY_TARGET=http://localhost:8080
```

后端不在本机 8080 时改此变量即可；**业务代码只请求相对路径 `/api/...`，不允许硬编码主机**。

### 生产部署（nginx 同源反代）

后端无 CORS，生产环境必须让浏览器与后端同源：nginx 托管前端静态资源，并把 `/api` 反代到后端。
SSE 流式接口需要以下三个关键指令，否则流式会被缓冲成「一次性返回」或连接被提前断开：

```nginx
server {
    listen 80;
    server_name your-host;

    # 前端静态资源（npm run build 产物）
    root /var/www/dj-agent-chat/frontend/dist;
    index index.html;
    location / {
        try_files $uri $uri/ /index.html;   # SPA 前端路由回退
    }

    # 后端 API + SSE
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;              # SSE 需要 HTTP/1.1 长连接
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header Connection '';      # 清空 Connection 头，启用 keep-alive 长连接
        proxy_buffering off;                 # 关键：关闭缓冲，SSE 片段即时转发
        proxy_cache off;
        proxy_read_timeout 300s;             # 长于后端 SSE 超时（120s）与心跳间隔
        proxy_send_timeout 300s;
    }
}
```

后端服务以普通进程/systemd 启动（`java -jar backend/target/dj-agent-chat-0.0.1-SNAPSHOT.jar`，
密钥走环境变量或 `application-local.yml`），无需对互联网暴露 8080 端口。

## 常用命令

```bash
# 后端（cd backend 后，JDK17）
mvn -o test                       # 离线全量测试（216 测试）
mvn -o package -DskipTests        # 编译打包（target/dj-agent-chat-0.0.1-SNAPSHOT.jar）
mvn dependency:resolve            # 解析依赖（Spring AI M7 从 Maven Central 获取）

# 前端（cd frontend 后）
npm run test                      # vitest run 全量单测
npm run build                     # tsc -b && vite build
```

## 工程结构

```
backend/src/main/java/com/dj/ai/agentchat/
├── DjAgentChatApplication.java        # 启动类
├── controller/
│   ├── ChatController.java            # POST /api/chat、/api/chat/stream（SSE 编排 + 心跳）
│   └── SessionController.java         # 迭代4：GET /api/sessions、/{id}/messages、PATCH/DELETE
├── service/
│   ├── ChatService.java               # 参数校验、多轮消息组装、ChatClient 同步/流式调用、异常归一、
│   │                                  #   流式首片段前有限重试（Flux.defer + retryWhen）
│   └── SessionService.java            # 迭代4：会话列表/历史/删除/重命名（ObjectProvider 可选记忆依赖，
│                                      #   记忆关闭→400、DB 故障→503、不存在→404）
├── memory/                            # 会话持久化
│   ├── SessionManager.java            # 迭代4：会话查询/删除/重命名能力接口
│   ├── ConversationStore.java         #   继承 Spring AI ChatMemory，扩展 createSessionIfAbsent
│   ├── ChatMemoryProperties.java      #   app.chat.memory.*（enabled/max-history/init-on-startup）
│   ├── po/                            #   ChatSessionPO / ChatMessagePO（@TableName/@TableId）
│   ├── mapper/                        #   ChatSessionMapper / ChatMessageMapper（升序全量、每会话末条
│   │                                  #   JOIN 批量预览、按会话删除、标题幂等 UPDATE）
│   └── mybatis/                       #   MybatisChatMemory（@Transactional 成对落库、首轮用户消息
│                                      #   best-effort 写标题）、MybatisSessionManager、
│                                      #   ChatMemorySchemaInitializer（AtomicBoolean 懒建表）、
│                                      #   ChatMemorySchemaStartupRunner（ApplicationRunner，best-effort）
├── util/
│   ├── TextTitleUtils.java            # 迭代4：标题(20)/预览(30) codePoint 截断 + …（emoji 代理对安全）
│   └── SessionIds.java                # 迭代4：服务端签发 UUID 校验（非法→400）
├── dto/session/                       # 迭代4：SessionSummary / SessionMessageView / RenameRequest 等
├── advisor/RequestLoggingAdvisor.java # 请求日志 Advisor（Call+Stream 双接口，不记内容/密钥）
├── sse/                               # SseHeartbeatScheduler/ScheduledHeartbeat 抽象 +
│                                      #   DefaultSseHeartbeatScheduler（daemon 线程，可注入/可测）
├── config/
│   ├── ChatMemoryConfig.java          # @ConditionalOnProperty(enabled 默认 true) 收口记忆全家桶：
│   │                                  #   @MapperScan + Store/SessionManager/Initializer/Runner bean；false 全不装配
│   ├── web/FastJsonWebConfig.java     # fastjson2 转换器 add(0) 接管 MVC JSON 序列化（默认省略 null）
│   ├── ChatClientConfig.java          # ChatClient 装配（日志 Advisor、条件 defaultSystem）
│   ├── http/                          # HttpClient5 连接池（同步）+ Netty ConnectionProvider（流式）
│   └── retry/ChatRetryConfig.java     # 自定义 RetryTemplate（3 次指数退避、429/5xx/网络故障）
└── exception/                         # ChatNotConfiguredException / ModelCallException /
                                       # InvalidChatRequestException / MemoryUnavailableException(503) /
                                       # SessionNotFoundException(404) / GlobalExceptionHandler
backend/src/main/resources/
├── application.yml                    # 入库配置（密钥占位、三套存储连接、Hikari 懒启动、
│                                      #   重试/连接池/心跳/system-prompt、app.chat.memory.* 全部外置）
├── db/chat-memory-schema.sql          # chat_session / chat_message 建表脚本（CREATE TABLE IF NOT EXISTS）
└── application-local.yml.example      # 本地凭证模板（复制为 application-local.yml，已被 gitignore）

frontend/
├── vite.config.ts                     # Vite + vitest（jsdom）；loadEnv 读 VITE_DEV_PROXY_TARGET 配 /api proxy
├── .env.development                   # 提交入库的非敏感开发配置（proxy 目标默认 http://localhost:8080）
└── src/
    ├── api/                           # http.ts（错误体归一）、sse.ts（fetch+reader 手写分帧、30s 看门狗、
    │                                  #   AbortController 双 reason）、sessions.ts（会话 REST）
    ├── hooks/                         # useChatStream（对话流状态机：三态请求体/乐观消息/停止/错误归一）、
    │                                  #   useSessions（会话列表/切换/删除/重命名，404 静默移除）、
    │                                  #   useAutoScroll（贴底阈值 80px/上滑脱离/回到底部）、
    │                                  #   useLocalDraft（草稿按会话作用域持久化）
    ├── components/                    # AppLayout / SessionSidebar / MessageList / MessageBubble /
    │                                  #   MarkdownView（react-markdown+gfm+highlight，禁 rehype-raw、
    │                                  #   代码块复制）/ ChatInput（Enter 发送/Shift+Enter/IME 组词/停止）/
    │                                  #   EmptyState（示例卡片）/ InlineError（Alert + toast）
    ├── utils/                         # sseFrames（SSE 分帧纯函数）、errors（错误码文案）、
    │                                  #   title（前后端同规则截断）、storage（localStorage 安全封装）
    └── types/                         # 前后端契约 TS 类型
```

## 关键说明

- **密钥不入库**：`application.yml` 中 `api-key: ${ARK_API_KEY:ark-placeholder-not-configured}`。
  Spring AI M7 自动配置在 bean 创建期要求 api-key 非空白，故默认值为明显非法的占位串；
  服务层调用前检测到占位值即按「未配置」处理（同步 503 / 流式 `event:error`），不会把占位串发出。
  前端代码、配置、测试中不出现任何密钥。
- **缺库可启动**：`spring.datasource.hikari.initialization-fail-timeout: -1` 关闭 Hikari 启动快速失败；
  Lettuce 懒连接；不引 JPA/actuator；Postgres 走 `app.storage.postgres` 自定义命名空间不绑定自动配置。
- **SSE 事件协议**：（新建会话时先发）`event:session`（`data:{"sessionId":"<UUID>"}`）
  → （空闲期）`:keepalive` 注释帧 → `event:message`（`data:{"content":"片段"}`，多帧）
  → `event:done`（`data:[DONE]`）；记忆阶段失败（DB 不可达等）在订阅前同步抛出、
  以 `event:error` 返回且**不会**先发出 session 帧或启动心跳；异常或缺 Key 同样发
  `event:error`（data 为错误 JSON）后立即关闭，不无限挂起；超时 120s（`app.chat.sse-timeout-ms`）。
- **前端 SSE 消费**：`fetch` + ReadableStream reader + `TextDecoder({stream:true})` 手写分帧
  （`\n\n` 分块、CRLF 兼容、`event:`/`data:` 解析、`[DONE]` 不做 JSON.parse）；30s 看门狗
  （任意帧字节到达即重置，为后端 15s 心跳的 2 倍余量），超时以 `AbortController.abort(reason)`
  中止并归一为 `WATCHDOG_TIMEOUT` 文案；用户点「停止」是无 reason abort，片段保留、标记「已停止」、
  不弹错误。网络层失败归一为 `NETWORK_ERROR`。
- **错误码**：400 `BAD_REQUEST`（含非法 sessionId、记忆开关关闭时传 sessionId）/
  404 `SESSION_NOT_FOUND`（会话不存在或已删除）/
  503 `ARK_NOT_CONFIGURED`（缺 Key）/ 502 `MODEL_CALL_FAILED`（模型调用失败）/
  503 `MEMORY_UNAVAILABLE`（记忆读写阶段 DB 不可达，仅会话路径；无状态路径不受影响）/
  500 `MEMORY_PERSIST_FAILED`（仅同步路径：模型已成功但本轮落库失败，提示重试本轮）。
  错误响应不含堆栈与密钥；流式路径落库失败仅 ERROR 日志（不影响已推送片段与 done）。
  前端按错误码差异化文案（如 503 提示可关闭「记住本次对话」无状态继续）。
- **会话管理（迭代4）**：后端新增 `SessionManager` 接口 + MyBatis 实现，与记忆全家桶同生命周期
  条件装配（`app.chat.memory.enabled=false` 时不注册，会话 REST 返回 400）；会话标题取首轮用户消息
  前 20 个 code point（emoji 代理对安全），列表预览取末条消息前 30 字，标题写入为 best-effort
  （失败仅 log.error，不影响消息落库）。前端会话列表以服务端为权威源（新建/每轮完成/删除/重命名后
  刷新），流式生成中切换/新建会话被阻止并提示「生成中，请先停止」；切换到已删除会话（404）侧边栏
  静默移除并回空态。
- **会话持久化（迭代3）**：记忆实现走**业务层显式编排**（ChatService 内 prepareMemory/
  persistTurn），不使用 MessageChatMemoryAdvisor（M7 jar 实证 Advisor 消息归并约定下
  显式编排更可控）。记忆阶段（建会话/加载历史）在模型调用前同步完成：DB 不可达直接 503；
  同步路径模型成功后同线程落库（失败 500）；流式路径用 StringBuilder 聚合全文，
  `publishOn(boundedElastic)` 后在 `doOnComplete` 落库（阻塞 JDBC 不占 Netty 事件循环，
  done 帧在落库完成后发出；落库异常仅 log.error 吞掉）。模型出错/取消/重试耗尽**不落库**
  （不留孤儿 user 消息）。每轮成对写入 user + assistant；续接仅把库中历史进 Prompt、
  不重复落库；新建会话的种子 user 消息同时进 Prompt 与落库批次。
- **记忆配置项（迭代3）**：`app.chat.memory.enabled`（环境变量 `APP_CHAT_MEMORY_ENABLED`，
  默认 true；false 时记忆 Mapper/Store/SessionManager/Runner 全家桶不装配，会话请求 400）、
  `app.chat.memory.max-history`（`APP_CHAT_MEMORY_MAX_HISTORY`，默认 20，续接加载最近 N 条；
  落库全量不裁剪）、`app.chat.memory.init-on-startup`（`APP_CHAT_MEMORY_INIT_ON_STARTUP`，
  默认 true，启动 best-effort 建表；false 时首次记忆路径懒触发建表，DB 不可达同样自愈）。
- **fastjson2（迭代3）**：`FastJsonWebConfig` 以 `extendMessageConverters(converters.add(0,...))`
  把 `FastJsonHttpMessageConverter` 置于通用 Jackson 之前，全线 JSON 线网格式由 fastjson2 治理
  （UTF-8、默认省略 null 字段——故无状态响应不回显 sessionId、SSE 无状态流不发 session 帧；
  时间字段格式 `yyyy-MM-dd HH:mm:ss`）；record 三态反序列化：字段缺省/null→null、`""`→空串。
- **前端 Markdown 安全**：`MarkdownView` 仅启用 remark-gfm 与 rehype-highlight，**禁止 rehype-raw**；
  模型输出中的原始 HTML（`<img onerror>`、`<script>` 等）只作为转义文本显示、不成为 DOM 元素
  （有 XSS 回归测试）；代码块带一键复制按钮，highlight.js github 主题。
- **前端本地持久化**：localStorage 仅存非敏感 UI 状态——当前会话 ID（`chat.currentSessionId`，
  仅记忆模式写入；刷新后自动拉取历史恢复，404 静默回空态；关闭记忆开关即清除，无状态对话不留痕）
  与输入草稿（`chat.draft:<sessionId|'global'>`，按会话作用域隔离，发送即清）。
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
  自动以 `ChatClient.defaultSystem(...)` 对每次调用生效；留空（默认）保持纯用户对话行为。
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
