#!/bin/sh
# qa_db_query.sh —— SCRIPT 工具：QA 只读库受限 SELECT（经本机 docker 容器 mysql 客户端）
# 连接信息来自 ~/.ai-agent/troubleshoot/config.json，密码来自同目录 dbs.env（仓库外）。
# 具名 argv 透传无 shell 求值；runner 层做标识符白名单 + WHERE 只读闸门 + LIMIT 硬上限。
set -u

NODE_BIN=/opt/homebrew/bin/node
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

exec "$NODE_BIN" "$SCRIPT_DIR/qa_db_query_run.mjs" "$@"
