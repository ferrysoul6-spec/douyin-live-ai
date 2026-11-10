#!/usr/bin/env bash
# 网易云解析服务（NeteaseCloudMusicApi-enhanced）启停脚本
#
# 作用：给点歌功能提供「歌名 → 可播放直链」的解析能力。
# 后端配置项：live-ai.music.resolve-base（默认 http://127.0.0.1:3000）
#
# 用法：
#   ./scripts/netease-api.sh start     启动（后台）
#   ./scripts/netease-api.sh stop      停止
#   ./scripts/netease-api.sh status    查看状态 + 搜索自检

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API_DIR="${NETEASE_API_DIR:-$(dirname "$ROOT")/netease-api}"
PORT="${NETEASE_API_PORT:-3000}"
LOG=/tmp/netease-api.log

info() { printf '\033[0;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[0;32m✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[0;33m⚠ %s\033[0m\n' "$*" >&2; }
fail() { printf '\033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

pid_of() { pgrep -f "node app.js" | while read -r p; do
  if lsof -nP -p "$p" 2>/dev/null | grep -q ":$PORT"; then echo "$p"; break; fi
done; }

do_start() {
  [ -d "$API_DIR" ] || fail "找不到解析服务目录: ${API_DIR}（先 git clone NeteaseCloudMusicApi-enhanced）"
  local pid
  pid="$(pid_of)"
  if [ -n "$pid" ]; then
    warn "解析服务已在运行 (pid $pid)"
    return 0
  fi
  info "启动解析服务（端口 ${PORT}）"
  ( cd "$API_DIR" && PORT="$PORT" nohup node app.js > "$LOG" 2>&1 & )
  for _ in $(seq 1 20); do
    sleep 1
    if curl -s --max-time 2 "http://127.0.0.1:$PORT/search?keywords=test&limit=1" >/dev/null 2>&1; then
      ok "解析服务已就绪（http://127.0.0.1:${PORT}）"
      return 0
    fi
  done
  warn "启动超时，查看日志: $LOG"
}

do_stop() {
  local pid
  pid="$(pid_of)"
  if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null
    ok "已停止解析服务 (pid $pid)"
  else
    info "解析服务未运行"
  fi
}

do_status() {
  local pid
  pid="$(pid_of)"
  echo "────────────── 解析服务 ──────────────"
  if [ -n "$pid" ]; then echo "进程      : 运行中 (pid $pid)"; else echo "进程      : 未运行"; fi
  echo "地址      : http://127.0.0.1:$PORT"
  echo "目录      : $API_DIR"
  echo "日志      : $LOG"
  local hit
  hit="$(curl -s --max-time 3 "http://127.0.0.1:$PORT/search?keywords=%E8%B5%B7%E9%A3%8E%E4%BA%86&limit=1" 2>/dev/null)"
  if printf '%s' "$hit" | grep -q '"songs"'; then
    echo "搜索自检  : 正常"
  else
    echo "搜索自检  : 失败（服务未起或接口变更）"
  fi
  echo "──────────────────────────────────────"
}

case "${1:-status}" in
  start)  do_start ;;
  stop)   do_stop ;;
  status) do_status ;;
  -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) fail "未知参数：$1（start / stop / status）" ;;
esac
