#!/bin/sh
# code_lookup.sh —— SCRIPT 工具：外部业务仓只读代码定位（FQCN+行号 / grep -rnF）
# 仓根与 code_include 来自 dev-local/troubleshoot/config.json（默认；AI_AGENT_CONF_DIR 可覆盖）。
# 具名 argv 透传，无 shell 求值；路径过 canonical 闸门。
set -u

NODE_BIN=/opt/homebrew/bin/node
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

exec "$NODE_BIN" "$SCRIPT_DIR/code_lookup_run.mjs" "$@"
