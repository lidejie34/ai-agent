#!/bin/sh
# skyeye_query_log.sh —— SCRIPT 工具：包装本机 skyeye queryLog
# 执行器净化环境（PATH=/usr/bin:/bin、无 HOME、cwd=脚本目录），
# 故显式指定 node 绝对路径；token 缓存在 skill 目录，不依赖 HOME。
# 模型入参由执行器转译为独立 argv（--key value），本脚本原样透传给 runner，无 shell 求值。
# runner 先执行 skyeye.ts queryLog，再把成功 JSON 压缩为 {time,level,appUk,contextId,msg}
# 精简结构（噪声字段剔除、超长 msg/堆栈截断），错误响应原样透传。
set -u

NODE_BIN=/opt/homebrew/bin/node
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

exec "$NODE_BIN" "$SCRIPT_DIR/skyeye_query_run.mjs" "$@"
