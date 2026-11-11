#!/usr/bin/env bash
# 把「深深 2.0」逐帧素材包接进 live-assets/，并生成前端用的清单。
#
# 素材包是 lossless-frame-v1：每个动作一个目录、241 帧自带 alpha 的 webp
# （640x360 @24fps）。前端直接读这些原始帧，不烘 webm——换动作就是换一组图片，
# 素材本身不用重编码。整套约 1.4GB，所以用符号链接挂进来，不复制、也不入库。
#
# 用法：
#   ./scripts/prepare-shenshen.sh                 # 自动找上一级的 shenshen-2.0.0-* 分卷
#   ./scripts/prepare-shenshen.sh /path/to/packs  # 指定素材包所在目录
#
# 素材包自带 7 个分卷：core（基础动作）+ extra1~6（随机小动作与事件）。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

ROOT="$(pwd)"
SRC_ROOT="${1:-$(dirname "$ROOT")}"
LINK_ROOT="$ROOT/live-assets/shenshen"
MANIFEST="$ROOT/live-assets/stage/manifest-shenshen.json"
PARTS="core extra1 extra2 extra3 extra4 extra5 extra6"

info() { printf '\033[0;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[0;32m✓ %s\033[0m\n' "$*"; }
fail() { printf '\033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[ -d "$SRC_ROOT" ] || fail "素材目录不存在：$SRC_ROOT"

info "挂接素材：$SRC_ROOT → live-assets/shenshen/"
mkdir -p "$LINK_ROOT"
found=0
for p in $PARTS; do
  src="$SRC_ROOT/shenshen-2.0.0-base-640x360.$p"
  if [ -d "$src" ]; then
    ln -sfn "$src" "$LINK_ROOT/$p"
    found=$((found + 1))
  else
    printf '\033[0;33m⚠ 缺分卷 %s（跳过）\033[0m\n' "$p" >&2
  fi
done
[ "$found" -gt 0 ] || fail "一个分卷都没找到，确认路径是否指到了放 shenshen-2.0.0-* 的目录"
ok "已挂接 $found 个分卷"

info "生成前端清单：live-assets/stage/manifest-shenshen.json"
python3 - "$SRC_ROOT" "$MANIFEST" <<'PY'
import json, os, sys
sys.path.insert(0, "scripts")
import importlib.util

spec = importlib.util.spec_from_file_location("sb", "scripts/shenshen-bake.py")
sb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sb)

src_root, out_path = sys.argv[1], sys.argv[2]
clips = sb.collect(os.path.abspath(src_root))
if not clips:
    sys.exit("没找到任何动作，检查素材包结构")

out = {"fps": 24, "canvas": [640, 360], "clips": {}}
for slug, name, cat, part, d, frames in clips:
    rel = os.path.relpath(d, os.path.abspath(src_root))
    part_dir, sub = rel.split(os.sep, 1)
    out["clips"][slug] = {
        "dir": f"{part_dir.replace('shenshen-2.0.0-base-640x360.', '')}/{sub}",
        "frames": frames, "name": name, "category": cat,
        "loop": slug == "idle",
    }
os.makedirs(os.path.dirname(out_path), exist_ok=True)
with open(out_path, "w") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
print(f"    {len(out['clips'])} 个动作（{sum(c['frames'] for c in out['clips'].values())} 帧）")
PY

ok "就绪：在 OBS 里刷新浏览器源（http://127.0.0.1:${SERVER_PORT:-8080}/stage）即可"
