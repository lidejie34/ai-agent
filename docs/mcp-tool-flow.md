# MCP 工具链路：注册、发现、调用全流程

> 迭代4 交付的 stdio MCP Client（mcp-core 0.14.0 离线手工装配）。
> 代码位置：`backend/src/main/java/com/dj/ai/agentchat/tool/mcp/`
> 本文描述 MCP 工具从 yml 声明到被模型调用的完整链路。

## 总览

```
【注册】application.yml 声明 server
   └─ McpProperties 绑定 → McpRuntimeConfig 双开关装配（条件 bean）
        ├─ StdioMcpClientFactory    （唯一 SDK 装配点）
        ├─ McpServerConnectionManager（ApplicationRunner，启动即连）
        ├─ McpToolCallbackFactory   （raw Tool → 自包装回调）
        ├─ McpToolProvider          （实现 Spring AI ToolCallbackProvider）
        └─ AdminMcpService          （只读视图）

【发现】应用启动（ApplicationRunner）
   每个 server 并行：ProcessBuilder 起子进程 → JSON-RPC initialize 握手
   → listTools() 一次性发现 → 每个工具包成 McpToolCallback
   → 失败隔离 UNAVAILABLE（不阻断启动）→ volatile 快照 callbackSnapshot

【挂载】对话请求进来
   DefaultToolSupport.mountTools()：DB 工具快照（前）+ MCP 快照（后）
   按名去重（DB 优先，同名跳过 MCP）→ 一次 .tools(...) 挂给 ChatClient

【调用】模型决定调工具
   Spring AI 按名找到 McpToolCallback.call(argsJson, toolContext)
   → 幂等查缓存 → event:tool started 帧 → tool-executor 线程池
   → gateway.callTool()（JSON-RPC over stdin/stdout）
   → TextContent 提文本 / isError 业务失败 / 异常摘除
   → 脱敏+截断+<tool-result> 包装 → 审计落库（handler_type=MCP）
   → event:tool 终态帧 → 结果回给模型
```

---

## 一、注册：配置声明 + 条件装配

**1) 配置位置**：`app.tools.mcp` 段（`ToolProperties.Mcp`，含 `enabled`、`request-timeout`、`servers[]`；每个 server 为 `name / command / args / env`）。示例：

```yaml
app:
  tools:
    mcp:
      enabled: true
      servers:
        - name: everything
          command: /opt/homebrew/bin/npx      # 绝对路径，argv 直传，绝不经 sh -c
          args: ["-y", "@modelcontextprotocol/server-everything"]
        - name: fs
          command: /opt/homebrew/bin/npx
          args: ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"]
```

**2) 双开关装配**（`mcp/config/McpRuntimeConfig.java`）：

- 类级 `@ConditionalOnProperty(app.tools.enabled)`——工具总开关关闭时整包不生效；
- 每个 bean 再标 `app.tools.mcp.enabled`（matchIfMissing=true）——MCP 子开关关闭时，连接管理器/工厂/provider/admin service 全部不装配，`DefaultToolSupport` 经 `ObjectProvider<McpToolProvider>` 拿到 null，行为与纯 DB 工具（迭代 G）逐字节一致。

**3) 关键选型（路径 B：手工装配，不引 starter）**：

不使用 `spring-ai-starter-mcp-client`，只依赖离线的 `mcp-core 0.14.0` + `mcp-json-jackson2 0.14.0`。原因：starter 会自动注册它自己的 `SyncMcpToolCallbackProvider`，而 spring-ai-mcp M7 的 `SyncMcpToolCallback.call(String, ToolContext)` 源码注明 *"ToolContext is not supported by the MCP tools"*——会绕过自建横切层（事件帧/审计/脱敏/超时/幂等）。因此全工程触达模型的 MCP 回调全部由自产 `McpToolCallback` 产生（结构性保证，AC-16）。

---

## 二、发现：启动期 eager 连接 + 一次性 listTools

入口：`McpServerConnectionManager implements ApplicationRunner`（`mcp/connection/McpServerConnectionManager.java`），应用启动后执行 `startup()`。

**1) 并行连接**：每个 spec 扔给 daemon 缓存线程池并行执行 `connectOne()`，`CompletableFuture.allOf().get(总预算)` 有界等待（握手超时 + 10s 调度余量）；超时未完成的 cancel 并按 UNAVAILABLE 收敛。

**2) 单 server 连接流程**（`connectOne()` + `StdioMcpClientFactory.connect()`）：

- `ServerSpecValidator` 校验配置（name 合法、command 非空、filesystem 目录预检）；
- `ServerParameters.builder(command).args(数组).env(...)`——**argv 直传 ProcessBuilder，不经 shell**（防注入）；env 继承父进程后显式补 PATH/HOME（npx/node 依赖），再叠加配置声明的 env；
- `new StdioClientTransport(params, new JacksonMcpJsonMapperSupplier().get())` 起子进程，接管 stdin/stdout 的 JSON-RPC 帧；
- `McpClient.sync(transport).clientInfo(...).requestTimeout(...).initializationTimeout(...).jsonSchemaValidator(no-op).build()` → `client.initialize()` 完成 MCP 握手；
  - SDK/离线适配两点：① 显式传 **no-op JsonSchemaValidator**（离线库缺 networknt json-schema-validator 依赖，默认 ServiceLoader 实现会初始化失败；本迭代不用 structuredContent 输出校验）；② 握手失败抛 `McpConnectException` 并 `closeGracefully()` 回收半启动子进程；
- `gateway.listTools()` 发一次 `tools/list`，拿回全部 `McpSchema.Tool`（name/description/inputSchema）。**不订阅 list_changed**——工具集是启动快照，配置变更需重启应用。

**3) 失败隔离**：任何一步抛错 → 记录脱敏原因、返回 UNAVAILABLE 连接（无 gateway、无工具），只 WARN 不阻断启动，其他 server 不受影响。

**4) 崩溃监听**（`StdioMcpClientGateway`）：构造时起 daemon 守护线程阻塞在 `transport.awaitForExit()`（子进程 `process.waitFor()`）。SDK 实证：`getErrorSink()` 是 unicast 的 stderr `Sinks.Many`、SDK 内部已独占订阅不可再订，故崩溃检测靠进程退出信号（`closing` 标志区分优雅关闭与崩溃）；stderr 文本经 `setStdErrorHandler` 打日志。

**5) 包装成回调 + 重建快照**（`rebuild()`）：READY 连接的每个 raw Tool 经 `McpToolCallbackFactory.build()` 包装为 `McpToolCallback`：

- **暴露名归一化**（`McpToolNames`）：`<server名>_<原始工具名>` 全小写、非 `[a-z0-9_]` 字符替换为 `_`（如 server `fs` + 工具 `Read-File` → `fs_read_file`）；超 64 字符截断为 55 前缀 + `_` + 8 位 sha1；同 server 内归一化碰撞（Foo/foo）跳过并 WARN；
- 产出存入 volatile `callbackSnapshot`（READY server 回调列表）与 `connections` map。

启动日志 `MCP 启动完成: server 总数=2, READY=2, UNAVAILABLE=0, 挂载工具数=27` 即此阶段输出。

---

## 三、挂载：DB 工具与 MCP 工具合并

每次对话请求，`DefaultToolSupport.mountTools(sessionId)`（`tool/support/DefaultToolSupport.java`）：

1. `mergeCallbacks()`：**DB 工具快照在前、MCP 快照在后**，`LinkedHashSet` 按工具名去重保序——MCP 与 DB 同名时跳过 MCP 并 WARN（D9，DB 优先）；
2. 合并为空 → 返回 null，调用方不触发 `.tools()`（与无工具场景逐字节等价）；
3. 非空 → 创建请求级 `ToolCallBridge`（发 event:tool 帧 + 幂等缓存）与 `ToolContext`，三个键：
   - `sessionId`：无状态会话不放该键（Spring AI M7 的 `Assert.noNullElements` 拒绝 null 值）；
   - `requestId`：**在 Flux.defer 之外生成的 UUID**，流式重订阅/重试复用同一幂等域，保证 dedupKey 稳定；
   - `toolBridge`：DB 回调与 MCP 回调共享同一桥（同一套帧、同一幂等域）。

---

## 四、调用：模型发起 tool call 后的完整横切

Spring AI 按工具名找到 `McpToolCallback.call(toolInput, toolContext)`（`mcp/callback/McpToolCallback.java`）：

1. **幂等检查**：`dedupKey = requestId|暴露名|sha1(参数JSON)`，命中 bridge 缓存直接返回（不执行/不审计/不发帧）——防流式重试导致工具重跑；
2. **started 帧**：经 ToolCallBridge 发 `event:tool` started（前端工具调用折叠块转圈）；入参摘要先脱敏再截 500 字符；
3. **执行**（`execute()`）：
   - 参数 JSON 解析失败 → `INVALID_ARGS`；
   - `aliveCheck`（连接是否 READY）为 false → `MCP_SERVER_UNAVAILABLE` 结构化失败（崩溃后工具已从挂载摘除，但在途/陈旧挂载仍可能调到）；
   - 提交到 **tool-executor 固定 daemon 线程池**，`future.get(超时)`——超时硬顶 60s（默认 30s）；SDK requestTimeout 设为「执行超时 - 2s」，保证 SDK 先超时、包装层沿异常因果链统一识别为 TIMEOUT 终态；
4. **真实协议调用**（`invoke()`，运行在隔离线程）：`gateway.callTool(rawName, args)` 发 `tools/call` JSON-RPC：
   - 正常：从 `CallToolResult.content()` 只抽取 `TextContent` 文本拼接；非文本内容（image/audio/resource）占位省略；
   - **`isError=true` 不抛异常**（MCP 协议的业务失败标志位）→ `MCP_TOOL_ERROR`，server 返回的错误文本作为 detail；
   - 抛异常（进程退出/管道断裂/协议错误）：连接已不可信 → 调 `markUnavailable` **摘除该 server 全部工具**（不自动重启），返回 `MCP_CALL_ERROR`；
5. **结果包装**：成功/失败均先经 `SecretRedactor` 脱敏（ark key / Authorization / api_key 等模式）、截断到 outputMaxChars，包成 `<tool-result>…</tool-result>`（**MCP 工具无 guide 注入**）；失败时 body 为结构化错误 JSON（ok/errorCode/error/detail）；
6. **审计**：best-effort 写 `agent_tool_call_log`，`handler_type='MCP'`、`tool_name` 为带前缀全名（列宽 VARCHAR(128) 完整留存）；
7. **终态**：发 `event:tool` succeeded/failed 帧（含 durationMs、错误信息）、结果记入幂等缓存、返回给模型。

---

## 五、运行期治理与可观测

- **崩溃降级**：子进程退出 → 守护线程触发 crash 回调 → 管理器 `markUnavailable(name, reason)`：状态翻 UNAVAILABLE、从 `callbackSnapshot` 摘除该 server 全部回调（其他 server 与 DB 工具不受影响）、**不自动重启**（恢复需重启应用）；管理端视图对 UNAVAILABLE server 返回空工具清单；
- **关闭回收**：`@PreDestroy shutdown()` 对全部连接（含半启动的）`closeGracefully()`，杀子进程零孤儿；
- **只读管理端**：`GET /api/admin/mcp/servers`（`AdminMcpService` 装配视图：name/status/command/args/toolCount/tools/lastError/connectedAt——视图 record 不含 env 字段；写方法 405）；管理控制台页面见 `#/admin` 的 MCP 面板；
- **审计页**：`GET /api/admin/tool-call-logs` 支持 handlerType=MCP 过滤、分页与时间范围过滤。

## 六、关键类速查

| 类 | 职责 |
|---|---|
| `mcp/McpProperties` | `app.tools.mcp.*` 配置绑定（enabled/requestTimeout/servers） |
| `mcp/config/McpRuntimeConfig` | 双开关条件装配（总开关 + mcp 子开关） |
| `mcp/connection/StdioMcpClientFactory` | **唯一 SDK 装配点**：ServerParameters/StdioClientTransport/McpClient.sync/initialize |
| `mcp/connection/StdioMcpClientGateway` | 连接壳：listTools/callTool/stderr/崩溃守护线程/closeGracefully |
| `mcp/connection/McpServerConnectionManager` | ApplicationRunner：并行连接发现、失败隔离、崩溃摘除、@PreDestroy 回收、volatile 快照 |
| `mcp/connection/McpServerConnection` | 单连接状态（name/spec/gateway/tools/status/lastError/callbacks） |
| `mcp/callback/McpToolCallbackFactory` | raw Tool → McpToolCallback 包装（含命名归一化/去重） |
| `mcp/callback/McpToolCallback` | 模型可调用回调：幂等/帧/超时/脱敏截断/审计/全捕获（385 行，横切核心） |
| `mcp/callback/McpToolNames` | 暴露名归一化（`server_tool`，超长截断+sha1） |
| `mcp/callback/McpToolProvider` | ToolCallbackProvider 实现，供 DefaultToolSupport 合并挂载 |
| `mcp/admin/*` | 只读管理端（McpAdminController/AdminMcpService/视图 record） |
| `mcp/migrate/AuditColumnWidthMigration` | 审计 tool_name 列放宽 VARCHAR(128) 幂等迁移 |
| `tool/support/DefaultToolSupport` | DB + MCP 回调合并挂载点（DB 优先、同名跳过 MCP） |
