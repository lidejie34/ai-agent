# dj-agent-chat

Spring Boot 3.4 + Spring AI 1.0.0-M7（OpenAI 兼容方式接入火山方舟）对话最小脚手架：
同步问答 `POST /api/chat` 与 SSE 流式问答 `POST /api/chat/stream`。
服务端无会话状态，多轮上下文由请求 `history` 传入；MySQL/Redis/Postgres 仅做依赖与连接配置预留，
**缺库、缺 Key 均可启动**，测试全程离线 mock 模型层。

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
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"从 1 数到 5，每个数字单独说"}'

# 7) 多轮上下文（带 history 追问，回答应体现上下文）
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"那我刚才说我叫什么？","history":[{"role":"user","content":"我叫小明"},{"role":"assistant","content":"你好，小明！"}]}'

# 8) 【联调首验】若请求返回 404/路径错误，说明方舟端点路径拼接与预期不一致：
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
├── controller/ChatController.java     # POST /api/chat、/api/chat/stream（SSE 编排）
├── service/ChatService.java           # 参数校验、多轮消息组装、ChatClient 同步/流式调用、异常归一
├── config/ChatClientConfig.java       # 由自动配置的 ChatClient.Builder 装配 ChatClient
├── dto/                               # ChatRequest / ChatMessage / ChatResponse / ApiError / StreamChunk
└── exception/                         # ChatNotConfiguredException / ModelCallException /
                                       # InvalidChatRequestException / GlobalExceptionHandler
src/main/resources/
├── application.yml                    # 入库配置（密钥占位、三套存储连接、Hikari 懒启动）
└── application-local.yml.example      # 本地凭证模板（复制为 application-local.yml，已被 gitignore）
```

## 关键说明

- **密钥不入库**：`application.yml` 中 `api-key: ${ARK_API_KEY:ark-placeholder-not-configured}`。
  Spring AI M7 自动配置在 bean 创建期要求 api-key 非空白，故默认值为明显非法的占位串；
  服务层调用前检测到占位值即按「未配置」处理（同步 503 / 流式 `event:error`），不会把占位串发出。
- **缺库可启动**：`spring.datasource.hikari.initialization-fail-timeout: -1` 关闭 Hikari 启动快速失败；
  Lettuce 懒连接；不引 JPA/actuator；Postgres 走 `app.storage.postgres` 自定义命名空间不绑定自动配置。
- **SSE 事件协议**：`event:message`（`data:{"content":"片段"}`，多帧）→ `event:done`（`data:[DONE]`）；
  异常或缺 Key 发 `event:error`（data 为错误 JSON）后立即关闭，不无限挂起；超时 120s（`app.chat.sse-timeout-ms`）。
- **错误码**：400 `BAD_REQUEST` / 503 `ARK_NOT_CONFIGURED` / 502 `MODEL_CALL_FAILED`，错误响应不含堆栈与密钥。
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
