#!/usr/bin/env bash
# 鲸鱼娘 AI 直播间 · 一键启动 / 状态 / 停止（Mac 单机）
#
# 用法：
#   ./scripts/start-live.sh <直播间>             支持 分享短链 / live.douyin.com/xxx / roomId / liveId
#   ./scripts/start-live.sh <直播间> --speakers  声音走扬声器（自己听得到，不推流）
#   ./scripts/start-live.sh <直播间> --virtual   声音走虚拟声卡（推流用，本机听不到）
#   ./scripts/start-live.sh <直播间> --multi     走多输出设备（自己听 + 推流）
#   ./scripts/start-live.sh --simulate           本地模拟事件（不连直播间，先验证链路）
#   ./scripts/start-live.sh --status             查看运行状态
#   ./scripts/start-live.sh --stop               停止全部并恢复音频
#   ./scripts/start-live.sh --player             只跑语音播放器（前台，调试用）
#
# 配置：全部在 src/main/resources/application.yml（可用同名环境变量覆盖）。
# .env 只放密钥：RAPIDAPI_KEY / ARK_API_KEY（兼容 DEEPSEEK_API_KEY）/ SEEDTTS_API_KEY。

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

# ---------------- 常量 ----------------
ROOT="$(pwd)"
VIDEO="$ROOT/live-assets/whale-live-final.mp4"
BACKEND_LOG=/tmp/douyin-live-ai-backend.log
PLAYER_LOG=/tmp/douyin-live-ai-player.log
MUSIC_LOG=/tmp/douyin-live-ai-music.log
STATE_FILE="$ROOT/.live-state"
VIRTUAL_DEV="${VIRTUAL_DEVICE:-}"
# 虚拟声卡默认自动探测：BlackHole 与向日葵各装各的，名字不一样，
# 写死一个名字会在换了驱动的机器上直接启动失败。
if [ -z "$VIRTUAL_DEV" ]; then
  for cand in "BlackHole 2ch" "OrayVirtualAudioDevice" "BlackHole 16ch"; do
    if SwitchAudioSource -a -t output 2>/dev/null | grep -Fxq "$cand"; then
      VIRTUAL_DEV="$cand"; break
    fi
  done
fi
VIRTUAL_DEV="${VIRTUAL_DEV:-BlackHole 2ch}"
BASE_URL="http://127.0.0.1:${SERVER_PORT:-8080}"

# 播放器本地行为（不是业务配置，可用环境变量临时覆盖）
POLL="${POLL:-1}"          # 队列空时的轮询间隔（秒）
MAX_AGE="${MAX_AGE:-60}"   # 超过该秒数的语音视为过时，直接丢弃不播
GAP="${GAP:-0.3}"          # 两条语音之间的间隔（秒），避免粘连

info() { printf '\033[0;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[0;32m✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[0;33m⚠ %s\033[0m\n' "$*" >&2; }
fail() { printf '\033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; }

# 从 .env 取一个键的值（不 source，避免 .env 里的特殊字符被执行）
env_get() { sed -n "s/^$1=//p" .env 2>/dev/null | tail -1; }

# ---------------- 语音播放器（轮询队列 → afplay） ----------------
json_field() {
  python3 -c 'import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    print("")
    sys.exit(0)
print(d.get(sys.argv[1],""))' "$1"
}

player_loop() {
  command -v afplay >/dev/null 2>&1 || { echo "需要 macOS afplay"; exit 1; }
  command -v python3 >/dev/null 2>&1 || { echo "需要 python3"; exit 1; }

  # 单实例：两个播放器会各自从队列取不同的语音，同时 afplay 就是「叠音」
  local pidfile="${TMPDIR:-/tmp}/douyin-live-ai-player.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile" 2>/dev/null)" 2>/dev/null; then
    echo "播放器已在运行 (pid $(cat "$pidfile"))，本进程退出，避免两条语音同时播放"
    exit 0
  fi
  echo $$ > "$pidfile"
  trap 'rm -f "$pidfile"' EXIT

  echo "AI 语音播放器已启动 → $BASE_URL"
  echo "轮询间隔 ${POLL}s，丢弃 ${MAX_AGE}s 以前的过时语音，Ctrl+C 停止"
  echo "提示：在 OBS/直播伴侣里开启「桌面音频」采集，AI 语音即可进入直播间"

  local body url text who enqueued age tmp
  while true; do
    body="$(curl -s --max-time 5 "$BASE_URL/audio/queue/next")"
    if [ -z "$body" ]; then
      sleep "$POLL"
      continue
    fi

    url="$(printf '%s' "$body" | json_field url)"
    [ -n "$url" ] || { sleep "$POLL"; continue; }

    text="$(printf '%s' "$body" | json_field text)"
    who="$(printf '%s' "$body" | json_field toNickname)"
    enqueued="$(printf '%s' "$body" | json_field enqueueAt)"

    # 过时语音直接跳过（例如 AI 被限流后积压了一批旧回复）
    if [ -n "$enqueued" ]; then
      age=$(( ( $(date +%s) * 1000 - enqueued ) / 1000 ))
      if [ "$age" -gt "$MAX_AGE" ]; then
        echo "[skip] ${age}s 前的过时语音: $text"
        continue
      fi
    fi

    echo "[play] ${who:+对 $who }说: $text"
    # afplay 只能播本地文件，HTTP URL 要先下载
    tmp="${TMPDIR:-/tmp}/live-ai-audio-$$.mp3"
    if curl -s --max-time 10 -o "$tmp" "$BASE_URL$url" && [ -s "$tmp" ]; then
      afplay "$tmp" || echo "[warn] 播放失败: $url"
    else
      echo "[warn] 下载失败: $BASE_URL$url"
    fi
    rm -f "$tmp"
    sleep "$GAP"
  done
}

# ---------------- 点歌播放器（独立于语音，两个队列互不抢） ----------------
music_loop() {
  command -v afplay >/dev/null 2>&1 || { echo "需要 macOS afplay"; exit 1; }
  command -v python3 >/dev/null 2>&1 || { echo "需要 python3"; exit 1; }

  local pidfile="${TMPDIR:-/tmp}/douyin-live-ai-music.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile" 2>/dev/null)" 2>/dev/null; then
    echo "音乐播放器已在运行 (pid $(cat "$pidfile"))，本进程退出"
    exit 0
  fi
  echo $$ > "$pidfile"
  trap 'rm -f "$pidfile"' EXIT

  local vol="${MUSIC_VOLUME:-0.35}"
  local resp tmp url title status ap
  resp="${TMPDIR:-/tmp}/live-ai-music-resp.$$"
  tmp="${TMPDIR:-/tmp}/live-ai-music-$$.mp3"
  echo "音乐播放器已启动 → ${BASE_URL}（音量 ${vol}）"

  while true; do
    status="$(curl -s -o "$resp" -w '%{http_code}' --max-time 5 "$BASE_URL/music/next")"
    if [ "$status" != "200" ]; then
      sleep 2
      continue
    fi
    url="$(json_field url < "$resp")"
    title="$(json_field title < "$resp")"
    [ -n "$url" ] || { sleep 1; continue; }
    # 本地曲库返回的是相对地址（/music-library/xxx.mp3），补上后端前缀
    case "$url" in /*) url="$BASE_URL$url" ;; esac

    echo "[music] 播放: $title"
    # 网易云直链需要 UA，且只能播本地文件
    if curl -s --max-time 20 -A 'Mozilla/5.0' -o "$tmp" "$url" && [ -s "$tmp" ]; then
      afplay -v "$vol" "$tmp" &
      ap=$!
      while kill -0 "$ap" 2>/dev/null; do
        if curl -s --max-time 2 "$BASE_URL/music/skip" | grep -q '"skip":true'; then
          kill "$ap" 2>/dev/null
          echo "[music] 切歌"
          break
        fi
        sleep 1
      done
      wait "$ap" 2>/dev/null
    else
      echo "[warn] 音乐下载失败: $url"
    fi
    rm -f "$tmp"
    curl -s -X POST "$BASE_URL/music/finish" >/dev/null 2>&1
  done
}

# ---------------- 参数 ----------------
TARGET=""
AUDIO_MODE="keep"
ACTION="start"
for arg in "$@"; do
  case "$arg" in
    --speakers) AUDIO_MODE="speakers" ;;
    --virtual)  AUDIO_MODE="virtual" ;;
    --multi)    AUDIO_MODE="multi" ;;
    --status)   ACTION="status" ;;
    --stop)     ACTION="stop" ;;
    --simulate) ACTION="simulate" ;;
    --player)   ACTION="player" ;;
    --music)    ACTION="music" ;;
    -h|--help)  usage; exit 0 ;;
    -*)         fail "未知参数：${arg}（--help 查看用法）" ;;
    *)          TARGET="$arg" ;;
  esac
done

if [ "$ACTION" = "player" ]; then
  player_loop
  exit 0
fi

if [ "$ACTION" = "music" ]; then
  music_loop
  exit 0
fi

backend_pid() { pgrep -f "com.example.douyinlive.DouyinLiveAiApplication" | head -1; }
# 旧版 audio-player.sh 的进程也要算进来：它同样会抢 /audio/queue/next
player_pid()  { pgrep -f "start-live.sh --player|audio-player.sh" | head -1; }
music_pid()   { pgrep -f "start-live.sh --music" | head -1; }

# ---------------- 状态 / 停止 ----------------
restore_audio() {
  local dev
  dev="$(sed -n 's/^ORIG_OUT=//p' "$STATE_FILE" 2>/dev/null)"
  if [ -n "${dev:-}" ]; then
    SwitchAudioSource -s "$dev" -t output >/dev/null 2>&1 && ok "系统输出已恢复：$dev"
  fi
}

do_stop() {
  local bp pp
  local mp
  bp="$(backend_pid)"; pp="$(player_pid)"; mp="$(music_pid)"
  if [ -n "$bp" ]; then kill "$bp" 2>/dev/null; info "已停止后端 (pid $bp)"; else info "后端未运行"; fi
  if [ -n "$mp" ]; then pkill -f "start-live.sh --music" 2>/dev/null; info "已停止音乐播放器 (pid $mp)"; fi
  if [ -n "$pp" ]; then
    pkill -f "start-live.sh --player" 2>/dev/null
    pkill -f "audio-player.sh" 2>/dev/null
    info "已停止播放器 (pid $pp)"
  else
    info "播放器未运行"
  fi
  sleep 2
  pkill -f "spring-boot:run" 2>/dev/null
  restore_audio
  rm -f "$STATE_FILE"
  ok "已全部停止"
}

do_status() {
  local bp pp health dev
  bp="$(backend_pid)"; pp="$(player_pid)"
  echo "────────────── 运行状态 ──────────────"
  if [ -n "$bp" ]; then echo "后端      : 运行中 (pid $bp)"; else echo "后端      : 未运行"; fi
  if [ -n "$pp" ]; then echo "播放器    : 运行中 (pid $pp)"; else echo "播放器    : 未运行"; fi
  health="$(curl -s --max-time 3 "$BASE_URL/health" 2>/dev/null)"
  if [ -n "$health" ]; then echo "健康检查  : $health"; else echo "健康检查  : 不可达"; fi
  curl -s --max-time 3 "$BASE_URL/audio/queue" 2>/dev/null | python3 -c \
    'import sys,json;d=json.load(sys.stdin);print("语音队列  : 积压 %s 条 / 累计 %s 条 / 丢弃 %s 条" % (d["size"],d["totalEnqueued"],d["totalDropped"]))' 2>/dev/null
  curl -s --max-time 3 "$BASE_URL/memory/pipeline" 2>/dev/null | python3 -c \
    'import sys,json;d=json.load(sys.stdin);print("事件队列  : 积压 %s 条 / 已处理 %s 条 / 丢弃 %s 条" % (d["queueSize"],d["handled"],d["dropped"]))' 2>/dev/null
  curl -s --max-time 3 "$BASE_URL/memory/stats" 2>/dev/null | python3 -c \
    'import sys,json;d=json.load(sys.stdin);print("观众记忆  : %s 位观众 / 累计 %s 次访问" % (d["profiles"],d["totalVisits"]))' 2>/dev/null
  curl -s --max-time 3 "$BASE_URL/music/state" 2>/dev/null | python3 -c \
    'import sys,json;d=json.load(sys.stdin);q=d.get("queue") or [];print("点歌队列  : %s 首 / 在放 %s" % (len(q), d.get("current") or "无"))' 2>/dev/null
  dev="$(SwitchAudioSource -c -t output 2>/dev/null)"
  echo "系统输出  : $dev"
  if [ -f "$STATE_FILE" ]; then echo "开播记录  : $(tr '\n' ' ' < "$STATE_FILE")"; fi
  echo "后端日志  : $BACKEND_LOG"
  echo "播放器日志: $PLAYER_LOG"
  echo "──────────────────────────────────────"
}

case "$ACTION" in
  status) do_status; exit 0 ;;
  stop)   do_stop; exit 0 ;;
esac

# ---------------- 直播间解析 ----------------
resolve_room() {
  local raw="$1" loc num
  if printf '%s' "$raw" | grep -qE '^https?://v\.douyin\.com/'; then
    info "解析抖音分享短链 ..." >&2
    loc="$(curl -sIL --max-time 20 \
      -A 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148' \
      "$raw" 2>/dev/null | tr -d '\r' | grep -i '^location:' | head -1)"
    num="$(printf '%s' "$loc" | grep -oE '(reflow|live)/[0-9]{10,}' | grep -oE '[0-9]{10,}' | head -1)"
    [ -n "$num" ] || fail "短链解析失败，请直接给房间号或 roomId"
    printf '%s' "$num"
    return
  fi
  if printf '%s' "$raw" | grep -qE 'live\.douyin\.com/'; then
    printf '%s' "$raw" | grep -oE '[0-9]{6,}' | head -1
    return
  fi
  printf '%s' "$raw" | tr -cd '0-9'
}

# ---------------- 前置检查：只读 .env 密钥 ----------------
preflight() {
  command -v afplay >/dev/null 2>&1 || fail "需要 macOS afplay"
  command -v SwitchAudioSource >/dev/null 2>&1 || fail "缺 SwitchAudioSource：brew install switchaudio-osx"
  command -v python3 >/dev/null 2>&1 || fail "需要 python3"

  local rapid api workshop tts_provider
  rapid=""; api=""; workshop=""; tts_provider=""
  if [ -f .env ]; then
    rapid="$(env_get RAPIDAPI_KEY)"
    api="$(env_get ARK_API_KEY)"
    [ -n "$api" ] || api="$(env_get DEEPSEEK_API_KEY)"      # 兼容旧键名
    workshop="$(env_get SEEDTTS_API_KEY)"
    tts_provider="$(env_get TTS_PROVIDER)"
  else
    warn "未找到 .env，所有密钥需由外部环境提供"
  fi

  # 密钥（和少量覆盖项）透传给 Spring；业务配置一律在 application.yml
  [ -n "$rapid" ] && export RAPIDAPI_KEY="$rapid"
  [ -n "$api" ] && export ARK_API_KEY="$api"
  [ -n "$workshop" ] && export SEEDTTS_API_KEY="$workshop"
  [ -n "$tts_provider" ] && export TTS_PROVIDER="$tts_provider"

  if [ "$ACTION" = "start" ]; then
    [ -n "${RAPIDAPI_KEY:-}" ] || fail "缺 RAPIDAPI_KEY（连直播间必需，写在 .env）"
    [ -f "$VIDEO" ] || warn "找不到循环画面 ${VIDEO}（推流需自备画面源）"
  fi

  if [ -n "${ARK_API_KEY:-}" ]; then ok "LLM Key：已配置"; else warn "未配 ARK_API_KEY（兼容 DEEPSEEK_API_KEY），AI 回复走兜底话术"; fi
  if [ -n "${SEEDTTS_API_KEY:-}" ]; then ok "TTS Key：已配置"; else warn "未配 SEEDTTS_API_KEY，云端语音不可用（会回落 edge-tts）"; fi
  ok "业务配置：src/main/resources/application.yml"
}

# ---------------- 音频路由 ----------------
apply_audio_mode() {
  local cur multi
  cur="$(SwitchAudioSource -c -t output 2>/dev/null)"
  case "$AUDIO_MODE" in
    speakers)
      SwitchAudioSource -s "MacBook Pro扬声器" -t output >/dev/null 2>&1 \
        && ok "音频 → 扬声器（自己听，不推流）" ;;
    virtual)
      if SwitchAudioSource -a -t output 2>/dev/null | grep -Fxq "$VIRTUAL_DEV"; then
        # 系统输出一旦切走，本机所有声音都会进虚拟声卡（自己听不到），
        # 所以只记 ORIG_OUT，--stop 时由 restore_audio 切回来
        SwitchAudioSource -s "$VIRTUAL_DEV" -t output >/dev/null 2>&1 \
          && ok "音频 → ${VIRTUAL_DEV}（推流用，本机听不到）" \
          || fail "切到 ${VIRTUAL_DEV} 失败"
      else
        fail "没有可用的虚拟声卡 ${VIRTUAL_DEV}；装一个：brew install --cask blackhole-2ch，或改用 --speakers / --multi"
      fi ;;
    multi)
      multi="$(SwitchAudioSource -a -t output 2>/dev/null | grep -iE 'multi|多输出' | head -1)"
      if [ -n "$multi" ]; then
        SwitchAudioSource -s "$multi" -t output >/dev/null 2>&1 && ok "音频 → ${multi}（自己听 + 推流）"
      else
        warn "未找到多输出设备；先在「音频 MIDI 设置」创建（扬声器 + ${VIRTUAL_DEV}），本次保持：$cur"
      fi ;;
    keep)
      info "音频输出保持：$cur" ;;
  esac
  printf 'ORIG_OUT=%s\nAUDIO_MODE=%s\nTARGET=%s\n' "$cur" "$AUDIO_MODE" "${TARGET:-simulate}" > "$STATE_FILE"
}

# ---------------- 启动 ----------------
start_backend() {
  local room="$1" i
  info "启动后端（弹幕采集 + 方舟 LLM + TTS）"
  # 采集模式是运行时行为，不是配置：由命令行决定
  if [ "$ACTION" = "simulate" ]; then
    export COLLECTOR_MODE=simulate
    info "模式：本地模拟事件（不连直播间）"
  else
    export COLLECTOR_MODE=live
    if [ "${#room}" -ge 15 ]; then
      export COLLECTOR_ROOM_ID="$room"
      info "用 roomId 直连：$room"
    else
      export COLLECTOR_LIVE_ID="$room"
      info "用 liveId（web_rid）：$room"
    fi
  fi
  nohup mvn -q spring-boot:run > "$BACKEND_LOG" 2>&1 &
  for i in $(seq 1 60); do
    if curl -s --max-time 2 "$BASE_URL/health" >/dev/null 2>&1; then
      ok "后端就绪（${i}s）"
      return 0
    fi
    sleep 1
  done
  fail "后端启动超时，查看 $BACKEND_LOG"
}

start_player() {
  local existing
  existing="$(player_pid)"
  if [ -n "$existing" ]; then
    warn "已有播放器在运行 (pid $existing)，跳过重复启动"
    return 0
  fi
  info "启动语音播放器（轮询队列 → afplay）"
  nohup "$0" --player > "$PLAYER_LOG" 2>&1 &
  sleep 2
  if [ -n "$(player_pid)" ]; then
    ok "播放器已启动"
  else
    warn "播放器启动异常，查看 $PLAYER_LOG"
  fi
}

start_music() {
  local existing
  existing="$(music_pid)"
  if [ -n "$existing" ]; then
    warn "已有音乐播放器在运行 (pid $existing)，跳过重复启动"
    return 0
  fi
  info "启动音乐播放器（点歌队列 → afplay）"
  nohup "$0" --music > "$MUSIC_LOG" 2>&1 &
  sleep 1
  if [ -n "$(music_pid)" ]; then
    ok "音乐播放器已启动"
  else
    warn "音乐播放器启动异常，查看 $MUSIC_LOG"
  fi
}

banner() {
  local cur audio_hint
  cur="$(SwitchAudioSource -c -t output 2>/dev/null)"
  if [ "$AUDIO_MODE" = "virtual" ]; then
    audio_hint="麦克风/音频来源 → 选 ${VIRTUAL_DEV}"
  else
    audio_hint="麦克风/音频来源 → 选「桌面音频」（抓本机输出：${cur:-未知}）"
  fi
  cat <<BANNER

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  AI 直播间已就绪 ✅

  直播伴侣里 3 步：
   1. 画面：来源 →「浏览器」填 ${BASE_URL}/stage（透明舞台，推荐）
      或「媒体/视频」选 ${VIDEO}（勾选「循环播放」）
   2. 声音：${audio_hint}
   3. 点「开始直播」

  观众发评论 → AI 生成回复 → 语音进直播音轨

  ./scripts/start-live.sh --status   看队列/记忆/音频状态
  ./scripts/start-live.sh --stop     停止并恢复音频
  日志：$BACKEND_LOG
        $PLAYER_LOG
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
BANNER
}

# ---------------- 主流程 ----------------
preflight

[ -n "$(backend_pid)" ] && fail "已有后端在运行，先：./scripts/start-live.sh --stop"

ROOM=""
if [ "$ACTION" != "simulate" ]; then
  [ -n "$TARGET" ] || fail "缺少直播间：./scripts/start-live.sh <短链|房间号>（--help 看用法）"
  ROOM="$(resolve_room "$TARGET")"
  [ -n "$ROOM" ] || fail "解析不出直播间号，请直接给 live.douyin.com/ 后面的数字"
  ok "直播间：${ROOM}（${#ROOM} 位）"
fi

apply_audio_mode
start_backend "$ROOM"
start_player
start_music
banner
