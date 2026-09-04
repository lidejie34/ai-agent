#!/bin/sh
# 只读示例：统计日志目录中 ERROR/WARN 行数（macOS/Linux 自带 grep/awk）。
# 用法: log_error_count.sh --minutes <N>
# 安全约定：不写文件、不联网；参数仅 --minutes；LOG_DIR 由执行器注入。
set -u
MIN=30
while [ $# -gt 0 ]; do
  case "$1" in
    --minutes) MIN="$2"; shift 2 ;;
    *) shift ;;
  esac
done
DIR="${LOG_DIR:-.}"
echo "log_dir=$DIR minutes=$MIN"
grep -h -c ' ERROR ' "$DIR"/*.log 2>/dev/null | awk '{e+=$1} END {print "ERROR_lines=" e+0}'
grep -h -c ' WARN '  "$DIR"/*.log 2>/dev/null | awk '{w+=$1} END {print "WARN_lines=" w+0}'
