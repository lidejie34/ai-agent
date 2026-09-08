# dj-agent-chat

Spring Boot 3.4 + Spring AI 1.0.0-M7（OpenAI 兼容方式接入火山方舟）对话工程，
迭代 4 起为前后端双模块：

- `backend/`：同步问答 `POST /api/chat`、SSE 流式问答 `POST /api/chat/stream`、
  会话管理 REST（`/api/sessions`）+ 服务端会话持久化（MySQL/MyBatis-Plus）；
  插入迭代 G 起内置 **DB 工具注册表 + Function Calling 闭环**：工具以数据库行注册（内置
  BUILTIN / 白名单脚本 SCRIPT 两类处理器），对话中模型自动调用，调用过程以 `event:tool`
  SSE 帧实时回传前端；另有 **管理端 REST**（`/api/admin/**`，X-Admin-Token 鉴权）做工具
  CRUD、指南热更新与调用审计日志查询。
- 迭代 5 起内置 **SDD 子智能体编排**（`app.sdd.enabled=true` 开启，默认关闭）：Planner
  先路由（直答 / 拆解计划），Executor 顺序执行子任务（共享工具挂载），Planner 再规划循环
  直至汇总收尾；过程以 `event:plan` / `event:task` SSE 帧实时回传，审计落
  `agent_orchestration_run` 表；关闭时编排 bean 全家桶不装配，行为与迭代 4 逐字节一致。
- `frontend/`：Vite + React 18 + TypeScript + antd 5 对话页，fetch 手写 SSE 分帧消费流式接口，
  会话侧边栏（列表/切换/重命名/删除）、Markdown 渲染、停止生成、草稿与刷新恢复；
  工具调用在助手气泡内显示为「🔧 调用工具 xxx」折叠块（进行中转圈/成功耗时/失败错误摘要），
  SDD 编排开启时气泡内工具块上方显示「📋 规划与执行」竖向 Steps 面板（待办/执行中/成功耗时/
  失败错误/已跳过，按 taskId 原地更新）。

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
- 本地 MySQL（会话持久化需要）：docker 容器 `db-mysql-1`，宿主机端口 `127.0.0.1:13306`，
  库 `dj_agent`（表由应用启动 best-effort 自动建）；MySQL/Key 缺失时应用仍可启动，仅相关功能降级。

## 启动与停止（本地开发速查）

本地联调需**同时运行后端（8080）与前端（5173）**两个进程；浏览器只访问 **http://localhost:5173**，
`/api` 由 Vite proxy 同源转发到后端（后端无 CORS，不要让浏览器直连 8080）。

### 方式一：两个终端前台运行（推荐，日志直接可见，停止最简单）

```bash
# 终端 1 —— 后端（每个新终端先切 JDK17；本机默认 JDK 是 1.8）
cd backend
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -o spring-boot:run -Dspring-boot.run.profiles=local    # local profile 读 gitignored 的 application-local.yml（真实 Key/MySQL）

# 终端 2 —— 前端
cd frontend
npm install        # 仅首次
npm run dev        # http://localhost:5173
```

**停止：在对应终端按 `Ctrl + C`**（两个终端各自停止；后端 mvn 退出后若端口未立即释放，等 2~3 秒即可）。

### 方式二：后台运行（不占用终端，日志落 `logs/`，已 gitignore）

```bash
# 启动后端（仓库根目录执行）
mkdir -p logs
cd backend
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
nohup mvn -o spring-boot:run -Dspring-boot.run.profiles=local > ../logs/backend.log 2>&1 &
echo $! > ../logs/backend.pid

# 启动前端
cd ../frontend
nohup npm run dev > ../logs/frontend.log 2>&1 &
echo $! > ../logs/frontend.pid

# 看日志
tail -f logs/backend.log logs/frontend.log
```

停止（二选一）：

```bash
# ① 按启动时记录的 PID 停止
kill "$(cat logs/backend.pid)" "$(cat logs/frontend.pid)"

# ② 按端口停止（PID 文件丢失/进程残留时兜底）
kill "$(lsof -ti:8080)"   # 后端
kill "$(lsof -ti:5173)"   # 前端
```

> 注：`mvn spring-boot:run` 后台运行时，`kill` mvn 进程后个别情况下 java 子进程会残留占用端口，
> 用 `lsof -ti:8080 | xargs kill` 兜底；启动前也可用 `lsof -nP -iTCP:8080 -sTCP:LISTEN` 确认端口空闲。

### 端口冲突时（如 8080 被 IDEA 里的实例占用）

后端换端口启动，前端 proxy 目标用环境变量同步指向（shell 变量优先级高于 `.env.development`）：

```bash
# 后端跑 8081（绝不 kill 别人占用 8080 的进程）
mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.port=8081"

# 前端指向 8081（或直接改 frontend/.env.development 的 VITE_DEV_PROXY_TARGET）
VITE_DEV_PROXY_TARGET=http://localhost:8081 npm run dev
```

### 验证两个进程都活着

```bash
curl -s -o /dev/null -w 'backend 8080: %{http_code}\n' http://localhost:8080/api/sessions   # 期望 200
curl -s -o /dev/null -w 'vite    5173: %{http_code}\n' http://localhost:5173/               # 期望 200
lsof -nP -iTCP:8080 -sTCP:LISTEN    # 查看后端监听进程
lsof -nP -iTCP:5173 -sTCP:LISTEN    # 查看前端监听进程
```

## 快速开始

### 后端（在 `backend/` 目录执行）

```bash
cd backend

# 1) 切换 JDK17（每个新终端必做）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version    # 期望 openjdk version "17.0.x"（Temurin）
mvn -v           # 期望 Java version: 17.0.x；若显示 1.8 说明切换失败

# 2) 离线全量测试（无需 Key、无需外网、无需数据库，应全绿；594 测试）
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

### 工具调用与管理端（插入迭代 G）

工具以 **DB 注册表**（`agent_tool` / `agent_tool_call_log` 两表，启动 best-effort 建表 + 种子内置工具）
驱动：模型每轮对话前挂载当前启用工具，自主决定调用；执行在独立 daemon 线程池，超时/截断/脱敏统一治理，
每次调用落审计日志。处理器两类：

- **BUILTIN（内置）**：随应用发行，开箱即用。种子内置 `analyze_log_errors`（分析最近 N 分钟日志：
  ERROR/WARN 统计、典型错误摘录，尾部窗口、扫描三上限保护）；指南文本（guide_md）可在管理端热更新，
  写后立即对新对话生效。
- **SCRIPT（白名单脚本）**：`app.tools.script-dir` 目录内、经管理端登记的 shell 脚本，执行器以
  `/bin/sh <file>` + argv 数组方式运行（禁 `-c`）、环境变量白名单注入、工作目录锁定、超时强杀、
  输出截断。种子脚本 `log_error_count` **默认禁用**，需管理端启用并人工审计脚本内容后方可使用。

**SSE 工具帧**：流式问答中工具调用过程以 `event:tool` 帧推送（fastjson2 JSON）：
`event:tool` → `data:{"callId":"<请求ID>|<工具名>|<短随机>","tool":"...","arguments":"{...}",
"status":"started"}`，终态再发一帧 `status:"succeeded"`（带 `durationMs`）或 `status:"failed"`
（带 `durationMs` + `error`）；帧序为 session（如有）→ tool(started/succeeded) → message* → done，
工具静默期同样有 `:keepalive`。前端按 callId 原地更新折叠块；历史消息不重现工具块。

**管理端 REST**（`/api/admin/**`，全部需 `X-Admin-Token` 头，JSON 走 fastjson2）：

```bash
TOKEN='你的管理端令牌'   # 来自 APP_ADMIN_TOKEN 或 application-local.yml 的 app.admin.token

# 工具列表（不含指南正文，含 guideLength）
curl -s http://localhost:8080/api/admin/tools -H "X-Admin-Token: $TOKEN"
# 工具详情（含 inputSchema/handlerConfig/guideMd 全文）
curl -s http://localhost:8080/api/admin/tools/1 -H "X-Admin-Token: $TOKEN"
# 新增工具（BUILTIN 登记另一个内置 bean / SCRIPT 登记白名单脚本）
curl -s -X POST http://localhost:8080/api/admin/tools -H "X-Admin-Token: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"log_error_count","description":"统计日志 ERROR/WARN 行数",
       "inputSchema":{"type":"object","properties":{"minutes":{"type":"integer"}}},
       "handlerType":"SCRIPT","handlerConfig":{"script":"log_error_count.sh"},
       "enabled":true,"timeoutMs":10000,"outputMaxChars":4000}'
# 全量更新（PUT）/ 部分更新（PATCH，如热改指南、启停、改超时）
curl -s -X PATCH http://localhost:8080/api/admin/tools/2 -H "X-Admin-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"enabled":true}'
# 删除
curl -s -X DELETE http://localhost:8080/api/admin/tools/2 -H "X-Admin-Token: $TOKEN"
# 调用审计日志（0 基分页；可按 toolName/sessionId/status/时间区间过滤）
curl -s 'http://localhost:8080/api/admin/tool-call-logs?page=0&size=20&status=failed' \
  -H "X-Admin-Token: $TOKEN"
```

管理端三语义拦截顺序：**未配置 token（`app.admin.token` 空白）→ 503 `ADMIN_NOT_CONFIGURED`**；
工具总开关关闭（`app.tools.enabled=false`）→ 400 `TOOLS_DISABLED`；token 错误/缺失 →
401 `ADMIN_UNAUTHORIZED`（错误体不回显任何配置信息）。工具不存在 → 404 `TOOL_NOT_FOUND`；
管理端写库/刷新时 DB 不可达 → 503 `TOOLS_UNAVAILABLE`（写操作已落库的情况会 ERROR 日志标注）。
工具执行面安全：目录白名单 + realpath 防符号链接逃逸、参数仅接受 schema 声明的具名 `--key value`、
脚本输出与审计中的密钥形态（`ark-...`、Authorization 头等）统一脱敏为 `***REDACTED***`。

**工具相关配置**（全部在 `application.yml` 外置，密钥仅 env / gitignored 的 local 文件注入）：

| 配置项 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `app.tools.enabled` | `APP_TOOLS_ENABLED` | `true` | 工具总开关；false 时运行时全家桶不装配、对话零工具挂载，管理端 400 |
| `app.tools.script-dir` | `APP_TOOLS_SCRIPT_DIR` | `scripts` | SCRIPT 白名单根目录（相对 CWD）。**生产务必只读挂载**（如 `mount -o ro` / 容器 readOnlyRootfs + 挂载卷），新脚本上线需人工审计后再在管理端登记启用 |
| `app.tools.executor-pool-size` | `APP_TOOLS_EXECUTOR_POOL_SIZE` | `4` | 工具执行 daemon 线程池（与模型 reactive 调度隔离） |
| `app.tools.default-timeout-ms` | — | `30000` | 表行 timeout_ms 缺省；管理端校验硬范围 1~60000ms |
| `app.tools.default-output-max-chars` | — | `8000` | 表行 output_max_chars 缺省；硬范围 100~100000 |
| `app.tools.redact-patterns` | — | `[]` | 增补脱敏正则（YAML 列表；启动编译，非法正则仅 WARN 跳过） |
| `app.tools.builtin.log-dir` | `APP_TOOLS_LOG_DIR` | `logs` | 日志分析根目录（相对 CWD）。**强烈建议配绝对路径**：`mvn spring-boot:run` 的 CWD 是 `backend/`，而 nohup/IDE/jar 启动的 CWD 可能是仓库根或部署目录，相对路径会指向不同位置 |
| `app.tools.builtin.scan-max-files` | `APP_TOOLS_SCAN_MAX_FILES` | `200` | 单次扫描文件数上限 |
| `app.tools.builtin.scan-max-bytes-per-file` | `APP_TOOLS_SCAN_MAX_BYTES` | `52428800`（50MB） | 单文件读取字节上限 |
| `app.tools.builtin.scan-max-lines` | `APP_TOOLS_SCAN_MAX_LINES` | `200000` | 累计行数上限；触顶结果标注 truncated |
| `app.admin.token` | `APP_ADMIN_TOKEN` | 空 | 管理端令牌；**空白=管理端一律 503**。只从 env 或 `application-local.yml` 注入，不入库、不打日志、不进响应 |

端到端冒烟清单（帧序、CRUD、503/401/400 矩阵、SCRIPT 启停、脱敏、无状态工具调用）见
`reports/db-tool-smoke-checklist.md`（由流水线 step_8 在真实 MySQL + 方舟 Key 环境执行并回填结论）。

### MCP 工具接入（插入迭代4：MCP Client / stdio）

在 DB 工具之外，应用启动时可经 stdio 拉起本机 MCP server（[Model Context Protocol](https://modelcontextprotocol.io/)），
发现其工具并挂载给模型；MCP 工具与 DB 工具在**同一次** `.tools(...)` 挂载中合并（DB 在前、MCP 在后，
同名冲突跳过 MCP 并 WARN），完整复用 G 的横切治理：`event:tool` 三态帧、审计落 `agent_tool_call_log`
（`handler_type='MCP'`）、密钥脱敏、输出截断、超时硬顶 60s、流式重试幂等、全异常捕获转结构化错误。
模型可见的工具名为 server 命名空间前缀形式：`<server名>_<server工具名>`（小写、非 `[a-z0-9_]` 字符转 `_`，
超长截断 + 短哈希），例如 `fs_read_file`。

**开关语义**：`app.tools.mcp.enabled=false`（默认 true）时 MCP 全家桶不装配、不拉起任何子进程，
行为与迭代 G 逐字节一致；单个 server 握手失败/启动即退只把该 server 标记为 UNAVAILABLE（WARN +
管理端可见 lastError），不阻断应用启动、不影响其他 server；运行期进程崩溃摘除该 server 工具
（不自动重启，需重启应用恢复）。`server-everything` / `server-filesystem` 两个参考 server
首跑需联网拉 npm 包；MCP server 二进制/参数**只来自部署配置**（application.yml / 环境变量），
模型、对话参数、任何 HTTP 接口都不能新增/修改/删除 server——管理端**只有只读接口**。

**配置示例**（`application.yml`；真实 env 密钥只走 `application-local.yml` 或环境变量，不入库）：

```yaml
app:
  tools:
    mcp:
      enabled: true            # APP_TOOLS_MCP_ENABLED；false = 不拉子进程、纯 DB 工具
      request-timeout: 20s     # APP_TOOLS_MCP_REQUEST_TIMEOUT；握手/启动发现预算
      servers:
        - name: fs             # 须匹配 ^[a-z][a-z0-9-]{1,40}$（工具名前缀、日志/审计标识）
          # 推荐：node/npx 用绝对路径，避免依赖启动用户的 PATH 解析
          command: /opt/homebrew/bin/npx
          args: ["-y", "@modelcontextprotocol/server-filesystem", "/Users/me/ai-data/mcp-workspace"]
          env:                # 仅追加配置显式声明的变量（最小化；勿依赖父进程环境中的密钥）
            DEBUG: "mcp:*"
        - name: everything
          command: /opt/homebrew/bin/npx
          args: ["-y", "@modelcontextprotocol/server-everything"]
```

**部署红线 / 注意事项**：

- **argv 直传，不经 shell**：command + args 数组原样作为子进程 argv（`ProcessBuilder`），
  **禁止 `sh -c "..."` / `bash -c` 拼接**（配置校验直接拒绝）。参数中任何内容都不会被 shell 解释。
- **npx 命令写法**：首选 `command: <npx 绝对路径>` + `args: ["-y", "<包名>", ...]`（`-y` 自动确认安装，
  避免首次运行卡在交互提示）。个别 Windows/软链环境 npx 行为异常时的等效回退写法：
  `command: <node 绝对路径>` + `args: ["<npm 全局目录>/lib/node_modules/npm/bin/npx-cli.js", "-y", "<包名>", ...]`。
  拿绝对路径：`which npx`、`readlink -f "$(which npx)"`。
- **npx 缓存预热（离线/AC-44）**：首跑 npx 需联网下载包；部署机首次配置后、断网前，先手动执行一次
  `npx -y @modelcontextprotocol/server-everything --help`（filesystem 同理）把包灌入 npx 缓存，
  之后离线启动可正常拉起。首跑无网络时该 server 握手超时 → UNAVAILABLE（WARN），应用其余功能不受影响。
- **filesystem 工作目录红线（AC-29）**：`server-filesystem` **必须**显式传至少一个工作目录参数；
  未传目录的配置项视为非法、启动跳过。目录必须是**专用数据目录**（如应用数据目录下的 `mcp-workspace/`，
  仓库根的 `mcp-workspace/` 已 gitignore）——**严禁**配成仓库根、家目录（`$HOME`）、文件系统根 `/`、
  系统目录；server 以运行应用的同一用户权限运行（不提权），目录即其文件读写边界。
  目录不存在时应用尝试创建（失败仅 WARN），危险路径仅 WARN 不阻断（部署责任）。
- **子进程环境继承**：子进程默认继承最小父进程环境（显式补 `PATH`/`HOME` 供 node/npx 运行），
  `env` 中声明的变量叠加注入。server 若必须使用密钥，只能经配置显式注入，并确认日志/stdout/stderr
  不会回显；stderr 行会经 SecretRedactor 脱敏后落日志。
- **关闭与残留**：应用关闭（SIGTERM/容器停止）时对每个 server 发送优雅关闭并回收子进程；
  运维侧可用 `pgrep -f server-filesystem` 核验无孤儿进程。

**MCP 管理端只读接口**（同样需 `X-Admin-Token`，响应**不含 env**；写方法 405）：

```bash
TOKEN='你的管理端令牌'
# server 列表：name/command/args/status(READY|UNAVAILABLE)/工具清单与数量/lastError/connectedAt
curl -s http://localhost:8080/api/admin/mcp/servers -H "X-Admin-Token: $TOKEN"
# 审计按处理器类型过滤（BUILTIN/SCRIPT/MCP，非法值 400；不传返回全部）
curl -s 'http://localhost:8080/api/admin/tool-call-logs?handlerType=MCP&size=20' \
  -H "X-Admin-Token: $TOKEN"
```

| 配置项 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `app.tools.mcp.enabled` | `APP_TOOLS_MCP_ENABLED` | `true` | MCP 子开关；false 时不装配、不拉子进程，纯 DB 工具 |
| `app.tools.mcp.request-timeout` | `APP_TOOLS_MCP_REQUEST_TIMEOUT` | `20s` | 握手/初始化超时与启动发现预算；单工具调用超时沿用 `app.tools.default-timeout-ms`（硬顶 60s） |
| `app.tools.mcp.servers` | — | `[]` | server 列表：name/command/args/env；env 仅本地配置注入，不入库、不进任何响应 |

端到端冒烟清单（npx 预热→READY 日志→everything echo→filesystem 目录内外读写→杀进程崩溃 UNAVAILABLE→
SIGTERM 无孤儿→handlerType=MCP 过滤→401/无 env→无状态 session_id NULL）见
`reports/mcp-smoke-checklist.md`（由流水线 step_8 在真实 npx 环境执行并回填结论）。

### SDD 子智能体编排（迭代5：Planner/Executor 顺序循环）

**默认关闭**（`app.sdd.enabled=false`）：关闭时编排全家桶（OrchestrationService、Planner/Executor
Clients、ModelInvoker、审计 Mapper、两个 daemon 线程池）一律不装配，对话零额外模型调用、
SSE 零 `plan`/`task` 帧，行为与迭代 4 逐字节一致。开启后，每轮对话由编排状态机驱动：

1. **路由（Planner 同步调用）**：Planner 读用户消息 + 历史，输出约定 JSON——`direct`（普通问答，
   直答文本按 24 codePoint 切片回流，与普通流式帧序列一致）或 `plan`（任务清单，最多 8 个）。
   路由输出两次均无法解析 → 审计 FAILED 并**降级为迭代4 普通直答**（不把解析错误抛给用户）；
   路由模型调用异常 → 同样降级。
2. **执行（Executor 同步调用，严格顺序）**：逐任务发 `event:task`（started）→ Executor  fresh
   `chatClient.prompt()` 单次调用（共享同一组工具挂载，工具事件帧照常）→ 结果契约
   `{"ok":true,"result"}` / `{"ok":false,"error"}`（非 JSON 整段视为 result），经脱敏 +
   截断（默认 2000 字符，附 `…[观察结果已截断]`）后作为观察回灌 Planner；发 `event:task`
   （succeeded 带 durationMs / failed 带 error 摘要）。单任务超时/异常都收敛为失败观察，
   **不中断整轮**。
3. **再规划（Planner 同步调用）**：每轮执行后 Planner 输出 `next`（追加任务，被取代的旧任务
   标记 skipped 且不执行）或 `final`（进入汇总）。再规划两次无法解析 → 强制收尾汇总。
4. **汇总（Synth 单次流式）**：Planner 角色以流式输出最终答复（复用普通 `event:message` 帧序列），
   订阅一次、不重放；汇总失败/预算不足以汇总 → `event:error`。

**硬顶与熔断**：轮次 ≤ 6（含路由）、累计子任务 ≤ 8（含跳过）、连续失败 ≤ 2（成功/跳过重置）、
循环检测（规范化标题重复且上次再规划任务失败，累计 2 次 → 强制收尾）；总墙钟预算 SSE 110s /
同步 55s（短于容器 120s/60s 超时），每次新模型调用前检查剩余预算，单子任务超时取
`task-timeout` 与剩余预算的较小值；预算耗尽且不足 5s 汇总时间 → 明确错误提示重试。

**请求级三态开关**：请求体可选 `"sdd": true|false`（`ChatRequest.sdd`，缺省 null）——
总开关关闭时 `sdd:true` 仅 WARN 忽略、仍走普通路径；总开关开启时 `sdd:false` 强制普通路径，
null/true 进入编排。前端当前不发送该字段（总开关开启即全量启用），留作灰度/对比开关。

**SSE 帧协议**（仅编排开启且本轮进入 plan 路径时出现；`event:message`/`:keepalive`/`event:done`
语义不变）：

```
event:plan
data:{"round":1,"tasks":[{"taskId":"t1","title":"...","status":"pending"}],"truncated":true}

event:task
data:{"taskId":"t1","title":"...","status":"started","round":1}

event:task
data:{"taskId":"t1","title":"...","status":"succeeded","durationMs":3200}
```

- `event:plan`：每轮（路由 + 每次再规划）发全量台账，`round` 从 1 起；任务数超上限被截断时
  `truncated:true`；status 五态 `pending/running/succeeded/failed/skipped`，null 字段省略
  （fastjson2，故 started 帧无 durationMs、succeeded 帧无 round）。
- `event:task`：单任务状态推进，`started`/`succeeded`/`failed` 三态；迟到帧（done/error 后）
  一律丢弃，五条终止路径都会 detach 编排桥。

**审计**：`agent_orchestration_run` 表启动期 best-effort 懒建（DB 不可达仅 WARN，不拖垮对话）；
记录每次 Planner/Executor/Synth 调用的角色、轮次、模型、耗时、状态与错误摘要，审计写入失败
只记日志、绝不影响编排主流程。

**前端面板**：`event:plan`/`event:task` 由 sseFrames 纯函数解析（非法帧→null 忽略，与工具帧同一
容错策略），useChatStream 按 taskId upsert 到**当前流式助手消息**（状态只升级不降级，防御帧乱序；
历史消息不带规划面板），MessageBubble 在工具折叠块上方挂载 antd Steps 竖向面板
（📋 规划与执行：pending 等待 / running 转圈执行中 / succeeded 绿色 + 耗时 / failed 红色错误摘要 /
skipped 灰色「已跳过」）。

**配置项**（全部在 `application.yml` 外置，密钥仍只走 env / gitignored 的 local 文件）：

| 配置项 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `app.sdd.enabled` | `APP_SDD_ENABLED` | `false` | 编排总开关；false 时全家桶不装配、零额外模型调用零新帧，行为=迭代4 |
| `app.sdd.max-rounds` | `APP_SDD_MAX_ROUNDS` | `6` | Planner 调用轮次硬顶（路由=第 1 轮；理论模型调用上限 1+6+8+1=16） |
| `app.sdd.max-tasks` | `APP_SDD_MAX_TASKS` | `8` | 累计子任务数硬顶（含执行/重试/跳过）；超限计划截断并标 `truncated` |
| `app.sdd.max-consecutive-failures` | `APP_SDD_MAX_CONSECUTIVE_FAILURES` | `2` | 连续失败硬顶；成功/跳过重置计数 |
| `app.sdd.total-budget-sse` | `APP_SDD_TOTAL_BUDGET_SSE` | `110s` | SSE 路径总墙钟预算（须短于 sse-timeout 120s） |
| `app.sdd.total-budget-sync` | `APP_SDD_TOTAL_BUDGET_SYNC` | `55s` | 同步路径总墙钟预算（须短于 read-timeout 60s） |
| `app.sdd.task-timeout` | `APP_SDD_TASK_TIMEOUT` | 空（继承 60s） | 单子任务超时；实际取与剩余预算的较小值 |
| `app.sdd.planner.model` | `APP_SDD_PLANNER_MODEL` | 空（继承主模型） | Planner 角色模型 ID（路由/再规划/汇总共用） |
| `app.sdd.planner.system-prompt` | `APP_SDD_PLANNER_SYSTEM_PROMPT` | 空（内置常量） | 空白=回退内置默认提示词（角色必有提示词，**非**「不注入」语义） |
| `app.sdd.planner.temperature` | `APP_SDD_PLANNER_TEMPERATURE` | 空（继承默认） | Planner 采样温度 |
| `app.sdd.executor.model` | `APP_SDD_EXECUTOR_MODEL` | 空（继承主模型） | Executor 角色模型 ID |
| `app.sdd.executor.system-prompt` | `APP_SDD_EXECUTOR_SYSTEM_PROMPT` | 空（内置常量） | 同上，空白回退内置 Executor 提示词 |
| `app.sdd.executor.temperature` | `APP_SDD_EXECUTOR_TEMPERATURE` | 空（继承默认） | Executor 采样温度 |
| `app.sdd.executor.max-result-chars` | `APP_SDD_EXECUTOR_MAX_RESULT_CHARS` | `2000` | 子任务结果回灌前截断字符数（硬下限 64，附截断标记） |

> 真实方舟环境的端到端冒烟（路由直答/计划多任务/工具挂载/再规划跳过/各熔断/汇总/审计行/
> 前端面板推进）需在线执行；离线单测以 mock ChatClient 覆盖全状态机（后端 99 个编排相关测试）。

### 前端（在 `frontend/` 目录执行）

```bash
cd frontend
npm install        # 首次安装依赖（版本已在 package.json 锁定主版本）

npm run dev        # 开发服务器（默认 5173），/api 经 Vite proxy 转发到后端
npm run test       # vitest 全量单测（jsdom，无需后端；186 测试）
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
mvn -o test                       # 离线全量测试（594 测试）
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
├── tool/                              # 插入迭代 G：DB 工具注册表 + 调用闭环
│   ├── ToolProperties / AdminProperties  # app.tools.* / app.admin.* 配置绑定
│   ├── po/ mapper/                    #   AgentToolPO / AgentToolCallLogPO + MyBatis-Plus Mapper
│   ├── schema/                        #   ToolSchemaInitializer/Runner（best-effort 建表）+ ToolSeeder（种子内置工具）
│   ├── registry/                      #   ToolRegistry（volatile 快照，refresh 失败管理端 503/对话降级空集）、HandlerType
│   ├── handler/                       #   ToolHandler 路由 + ToolExecutionContext/Result；
│   │   ├── builtin/                   #     BuiltinTool/BuiltinToolHandler + LogAnalysisBuiltinTool（日志分析）
│   │   └── script/                    #     ScriptToolHandler（/bin/sh argv 数组、环境白名单、超时强杀、输出截断）
│   ├── security/                      #   PathGuard（目录白名单 + realpath 防逃逸）、SecretRedactor（ark-/Authorization 脱敏）
│   ├── callback/                      #   DbToolCallback（Spring AI ToolCallback 适配）、ToolCallbackFactory、SkippableToolException
│   ├── support/                       #   ToolSupport/DefaultToolSupport（挂载）、ToolCallBridge/ToolEvent（调用事件桥）
│   ├── audit/                         #   ToolAuditService（调用审计落库，best-effort）
│   ├── admin/                         #   AdminToolController（/api/admin/** CRUD + tool-call-logs）、
│   │   ├── AdminAuthInterceptor.java  #     X-Admin-Token 拦截（503 未配置 > 400 工具关闭 > 401 未授权）
│   │   ├── ToolAdminService.java      #     全量校验 + 写后 refresh + DB 故障 503
│   │   └── dto/                       #     ToolUpsertRequest/ToolListItem/ToolDetail/ToolCallLogView/PageResult
│   ├── config/                        #   ToolRuntimeConfig（@ConditionalOnProperty 工具运行时）、
│   │                                  #     ToolAdminWebConfig（常驻：拦截器注册）
│   └── dto/ToolEventFrame.java        #   event:tool 帧 DTO（fastjson2 序列化，null 字段省略）
├── orchestration/                     # 迭代5：SDD 子 Agent 编排（@ConditionalOnProperty app.sdd.enabled）
│   ├── SddProperties.java             #   app.sdd.* 配置绑定（开关/轮次/任务/失败/预算/角色）
│   ├── SddRuntimeConfig.java          #   条件装配：daemon 池（sdd-orchestrator/sdd-model-call）、
│   │                                  #     ModelInvoker、Planner/Executor Clients、OrchestrationService
│   ├── OrchestrationService.java      #   编排状态机：路由→顺序执行→再规划循环→汇总，硬顶/预算/循环检测
│   ├── OrchEventBridge / OrchInput / OrchSyncOutcome  # 帧桥与编排入参/同步出参
│   ├── frame/                         #   OrchFrame（sealed：PlanFrame/TaskFrame）+ TaskView，null 字段省略
│   ├── planner/                       #   PlannerClient（route/replan 同步 + synth 单次流式，
│   │                                  #     JSON 围栏提取/失败重试一次/降级）、PlannerProtocol、
│   │                                  #     PlannerContext、RouteDecision/ReplanDecision/TaskSpec、
│   │                                  #     SddPrompts（内置角色提示词常量）
│   ├── executor/                      #   ExecutorClient（单任务同步调用、结果契约解析、脱敏截断、
│   │                                  #     超时/异常收敛为失败观察）、TaskOutcome
│   ├── support/                       #   ModelInvoker（daemon 池 Future + 超时强杀）、
│   │                                  #     ObservationText、CapReasons、TaskTimeoutException
│   └── audit/                         #   OrchestrationAuditService + po/mapper + agent_orchestration_run 懒建表
└── exception/                         # ChatNotConfiguredException / ModelCallException /
                                       # InvalidChatRequestException / MemoryUnavailableException(503) /
                                       # SessionNotFoundException(404) / ToolNotFoundException(404) /
                                       # ToolsUnavailableException(503) / GlobalExceptionHandler
backend/src/main/resources/
├── application.yml                    # 入库配置（密钥占位、三套存储连接、Hikari 懒启动、
│                                      #   重试/连接池/心跳/system-prompt、app.chat.memory.*、
│                                      #   app.tools.*/app.sdd.*/app.admin.* 全部外置）
├── db/chat-memory-schema.sql          # chat_session / chat_message 建表脚本（CREATE TABLE IF NOT EXISTS）
├── skills/                            # 种子内置工具指南 md（analyze_log_errors / log_error_count）
└── application-local.yml.example      # 本地凭证模板（复制为 application-local.yml，已被 gitignore）
backend/scripts/
└── log_error_count.sh                 # SCRIPT 示例脚本（只读统计 ERROR/WARN；种子默认禁用，启用前人工审计）

frontend/
├── vite.config.ts                     # Vite + vitest（jsdom）；loadEnv 读 VITE_DEV_PROXY_TARGET 配 /api proxy
├── .env.development                   # 提交入库的非敏感开发配置（proxy 目标默认 http://localhost:8080）
└── src/
    ├── api/                           # http.ts（错误体归一）、sse.ts（fetch+reader 手写分帧、30s 看门狗、
    │                                  #   AbortController 双 reason）、sessions.ts（会话 REST）
    ├── hooks/                         # useChatStream（对话流状态机：三态请求体/乐观消息/停止/错误归一；
    │                                  #   迭代5 plan/task 帧按 taskId upsert、状态秩只升不降）、
    │                                  #   useSessions（会话列表/切换/删除/重命名，404 静默移除）、
    │                                  #   useAutoScroll（贴底阈值 80px/上滑脱离/回到底部）、
    │                                  #   useLocalDraft（草稿按会话作用域持久化）
    ├── components/                    # AppLayout / SessionSidebar / MessageList / MessageBubble /
    │                                  #   PlanTaskBlocks（迭代5：📋 规划与执行 antd Steps 竖向面板，
    │                                  #   挂工具块上方；待办/执行中转圈/成功耗时/失败错误/已跳过）/
    │                                  #   ToolCallBlocks（插入迭代 G：🔧 工具调用折叠块，
    │                                  #   进行中转圈/成功耗时/失败展开错误，手动开合覆盖自动策略）/
    │                                  #   MarkdownView（react-markdown+gfm+highlight，禁 rehype-raw、
    │                                  #   代码块复制）/ ChatInput（Enter 发送/Shift+Enter/IME 组词/停止）/
    │                                  #   EmptyState（示例卡片）/ InlineError（Alert + toast）
    ├── utils/                         # sseFrames（SSE 分帧纯函数，含 event:tool/event:plan/event:task
    │                                  #   解析/未知事件忽略）、
    │                                  #   errors（错误码文案）、
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
  → （工具调用时）`event:tool`（`data:{callId,tool,arguments,status,...}`，started 与终态各一帧；
  仅插入迭代 G 工具启用且本轮发生调用时出现）
  → （SDD 编排时）`event:plan`（`data:{round,tasks:[{taskId,title,status}],truncated?}`，每轮全量台账）
  与 `event:task`（`data:{taskId,title,status,round?,durationMs?,error?}`，started/终态各一帧；
  仅 `app.sdd.enabled=true` 且本轮进入计划路径时出现）
  → （空闲期）`:keepalive` 注释帧 →
  `event:message`（`data:{"content":"片段"}`，多帧）→ `event:done`（`data:[DONE]`）；
  记忆阶段失败（DB 不可达等）在订阅前同步抛出、
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
  500 `MEMORY_PERSIST_FAILED`（仅同步路径：模型已成功但本轮落库失败，提示重试本轮）/
  404 `TOOL_NOT_FOUND`（管理端操作的工具 id 不存在）/
  503 `TOOLS_UNAVAILABLE`（管理端写库或注册表刷新时 DB 不可达）/
  400 `TOOLS_DISABLED`（`app.tools.enabled=false` 时访问 `/api/admin/**`）/
  503 `ADMIN_NOT_CONFIGURED`（未配置 `app.admin.token`）/
  401 `ADMIN_UNAUTHORIZED`（管理端 token 缺失或错误）。
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
- **工具调用闭环（插入迭代 G）**：工具注册表在 DB（`agent_tool` 表，启动 best-effort 建表 + 种子），
  `ToolRegistry` 持有 volatile 快照——对话路径每次挂载读快照（DB 故障降级为零工具，不拖垮对话），
  管理端写操作后强制 `refresh()`（失败 503，保证管理端强一致）；工具在独立 daemon 线程池执行，
  调用起止经 `ToolCallBridge` 发 `event:tool` 帧（流结束后迟到的帧一律丢弃），审计 best-effort 落
  `agent_tool_call_log`；脚本执行受目录白名单/realpath/argv 数组/环境白名单/超时强杀/输出截断多重约束，
  输出与审计中的密钥形态统一脱敏 `***REDACTED***`；工具帧与管理端 JSON 全部走 fastjson2
  （SSE `.data(obj, APPLICATION_JSON)`，null 字段省略故 started 帧无 durationMs/error 键）。
- **SDD 子智能体编排（迭代5）**：编排代码全部收口在 `orchestration/` 包，`@ConditionalOnProperty
  (app.sdd.enabled, matchIfMissing=false)` 条件装配 + 独立 `@MapperScan`；开关关闭时容器内无任何
  编排 bean，ChatService 经 ObjectProvider 取空即走迭代4 路径（已由「bean 缺席」回归测试锁定）。
  每次模型调用都新建 `chatClient.prompt()` spec（M7 下复用 spec 会静默绕过 Advisor，同一 Flux
  重订阅直接抛错）；Planner/Executor 同步调用跑在 `sdd-model-call` daemon 池（Future + 超时
  cancel(true)），编排循环跑在 `sdd-orchestrator` daemon 池，与 Netty/工具池全部隔离；Synth 汇总
  为唯一流式调用、Flux 仅订阅一次、不做 Reactor 重试。Planner JSON 协议经围栏/平衡括号提取后
  fastjson2 解析，失败带纠正指令重试一次：路由仍失败→降级迭代4 直答，再规划失败→强制汇总。
  编排层不注入 ConversationStore（历史以 `List<Message>` 传入，落库复用 ChatService 成对写入）；
  审计表 `agent_orchestration_run` 懒建、best-effort 写入；编排帧经 OrchEventBridge 推送，
  流结束后迟到帧一律丢弃，五条 SSE 终止路径都 detach 桥。
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
