#!/usr/bin/env python3
"""把「深深 2.0」逐帧 WebP 素材包烘成前端能播的 webm（带真 alpha）。

素材包是 lossless-frame-v1：每个动作一个目录，里面是 f_0001.webp ... 共 241 帧
（640x360 @ 24fps，自带 alpha，不需要抠像）。

两件事是这个脚本存在的理由：

1. **必须在预乘空间放大**。源帧全透明区域的 RGB 并不是黑的（实测均值 0.5/255，
   范围 0~255 全是压缩噪声），直接对 RGBA 做 LANCZOS 会把透明区的噪声混进边缘，
   深色舞台上就是一圈脏边。所以先 premultiply、再缩放、再 unpremultiply。
2. **动作名要英文化**。后端只认 idle/wave/happy/shy/think/bubble/sing 这些短名，
   而素材包的动作名是「点击回应-元气挥手」这种中文长名，得有个稳定的映射表。

用法：
    python3 scripts/shenshen-bake.py                     # 全量烘（约 106 个动作）
    python3 scripts/shenshen-bake.py --only idle,wave    # 只烘几个
    python3 scripts/shenshen-bake.py --list              # 只看会产出什么，不编码
"""

import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

# 素材包各分卷：core 是基础动作，extra1~6 是随机小动作与事件
PARTS = ["core"] + [f"extra{i}" for i in range(1, 7)]

# 后端会主动请求的动作名 → 素材包里的中文 clip 名。
# 没列在这里的 clip 一律按 random 处理，生成 rNN 短名，供待机轮换使用。
NAMED = {
    "idle": "待机呼吸休闲",
    "wave": "点击回应-元气挥手",
    "happy": "点击回应-开心跃动",
    "shy": "点击回应-害羞惊讶",
    "angry": "点击回应-傲娇生气",
    "giggle": "点击回应-挠痒咯咯笑",
    "think": "工作状态-思考冒泡",
    "bubble": "碎碎念-发呆碎碎念",
    "sing": "悠闲哼歌",
    "doze": "原地小憩沉眠",
    "tail": "用鲸鱼尾巴拍打地面",
    "tea": "摇扇纳凉",
    "drag": "被鼠标拖拽悬空反馈",
    "turn": "东张西望",
    "move-run": "左转奔跑",
    "move-float": "漂浮踏步",
    "move-crab": "螃蟹走路",
    "coin-empty": "余额-分文不剩",
    "coin-count": "余额-数金皱眉",
    "coin-broke": "余额-袋空如洗",
    "coin-jingle": "余额-金袋叮当",
    "coin-normal": "余额-钱袋如常",
    "coin-full": "余额-钱袋满溢",
}

# 预乘放大：alpha 单独缩放，颜色在预乘空间缩放，最后合成回去。
# scale 的宽高由 --size 决定，这里用占位符拼。
FILTER = (
    "[0:v]format=rgba,split=2[c0][a0];"
    "[a0]alphaextract,split=2[a1][a2];"
    "[c0]format=rgb24[c1];"
    "[c1][a1]premultiply[pm];"
    "[pm]scale={w}:{h}:flags=lanczos[pmu];"
    "[a2]scale={w}:{h}:flags=lanczos,format=gray,split=2[au1][au2];"
    "[pmu][au1]unpremultiply[col];"
    "[col][au2]alphamerge[out]"
)


def collect(src_root):
    """扫描所有分卷，返回 [(slug, clip_name, category, part, dir, frames)]。"""
    named_rev = {v: k for k, v in NAMED.items()}
    found, random_clips = [], []

    for part in PARTS:
        part_dir = os.path.join(src_root, f"shenshen-2.0.0-base-640x360.{part}")
        if not os.path.isdir(part_dir):
            print(f"跳过（不存在）: {part_dir}", file=sys.stderr)
            continue
        meta = json.load(open(os.path.join(part_dir, "part.json")))
        for c in meta["clips"]:
            name = c["name"]
            clip_dir = os.path.join(part_dir, c["dir"])
            if not os.path.isdir(clip_dir):
                print(f"跳过（缺目录）: {clip_dir}", file=sys.stderr)
                continue
            frames = len(glob.glob(os.path.join(clip_dir, "f_*.webp")))
            if frames == 0:
                print(f"跳过（无帧）: {clip_dir}", file=sys.stderr)
                continue
            slug = named_rev.get(name)
            item = (slug, name, c.get("category", ""), part, clip_dir, frames)
            if slug:
                found.append(item)
            else:
                random_clips.append(item)

    # random 按名字排序后编号，保证每次烘出来的短名稳定
    random_clips.sort(key=lambda x: (x[2], x[1]))
    for i, (_, name, cat, part, d, frames) in enumerate(random_clips, 1):
        found.append((f"r{i:02d}", name, cat, part, d, frames))
    return found


def encode_one(item, out_dir, size, fps, crf, overwrite):
    slug, name, _, _, clip_dir, frames = item
    dst = os.path.join(out_dir, slug + ".webm")
    if os.path.exists(dst) and not overwrite:
        return f"跳过 {slug}（已存在，--force 覆盖）"

    src = os.path.join(clip_dir, "f_%04d.webp")
    cmd = [
        "ffmpeg", "-v", "error", "-y",
        "-framerate", str(fps), "-i", src,
        "-filter_complex", FILTER.format(w=size[0], h=size[1]),
        "-map", "[out]",
        "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-auto-alt-ref", "0",
        "-crf", str(crf), "-b:v", "0", "-row-mt", "1", "-speed", "4", "-an",
        dst,
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        return f"失败 {slug}: {r.stderr.strip()[:200]}"
    mb = os.path.getsize(dst) / 1e6
    return f"[{slug:12s}] {name:24s} {frames:3d}帧 -> {mb:5.1f}MB"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src-root", default="..",
                    help="素材包所在目录（默认仓库的上一级，即放 shenshen-2.0.0-* 的地方）")
    ap.add_argument("--out", default="live-assets/shenshen-alpha")
    ap.add_argument("--only", default="", help="只处理这些 slug，逗号分隔")
    ap.add_argument("--force", action="store_true", help="覆盖已有产物")
    ap.add_argument("--scale", type=float, default=3.0, help="放大倍数（默认 3）")
    ap.add_argument("--crf", type=int, default=20)
    ap.add_argument("--fps", type=int, default=24)
    ap.add_argument("--jobs", type=int, default=3, help="并行编码数，默认 3")
    ap.add_argument("--list", action="store_true", help="只列出映射，不编码")
    args = ap.parse_args()

    root = os.path.abspath(args.src_root)
    clips = collect(root)
    if not clips:
        sys.exit(f"在 {root} 下没找到素材包")

    only = {s.strip() for s in args.only.split(",") if s.strip()}
    if only:
        clips = [c for c in clips if c[0] in only]
        missing = only - {c[0] for c in clips}
        if missing:
            print(f"未找到: {','.join(sorted(missing))}", file=sys.stderr)

    if args.list:
        for slug, name, cat, part, _, frames in clips:
            print(f"{slug:12s} [{cat:6s}] {part:7s} {name} ({frames}帧)")
        print(f"共 {len(clips)} 个")
        return

    size = (int(640 * args.scale), int(360 * args.scale))
    os.makedirs(args.out, exist_ok=True)
    print(f"输出 {args.out}  {size[0]}x{size[1]} @ {args.fps}fps crf={args.crf} jobs={args.jobs}")

    done = 0
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for msg in pool.map(
                lambda it: encode_one(it, args.out, size, args.fps, args.crf, args.force), clips):
            print(msg, flush=True)
            done += 1

    # 清单：前端不需要它，但排查「这个 slug 是哪个动作」时很有用
    manifest = {
        "pack": os.path.basename(root),
        "canvas": list(size),
        "fps": args.fps,
        "crf": args.crf,
        "clips": [
            {"slug": s, "name": n, "category": c, "part": p, "frames": f}
            for s, n, c, p, _, f in clips
        ],
    }
    with open(os.path.join(args.out, "manifest.json"), "w") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=1)
    print(f"完成 {done} 个，清单已写入 {args.out}/manifest.json")


if __name__ == "__main__":
    main()
