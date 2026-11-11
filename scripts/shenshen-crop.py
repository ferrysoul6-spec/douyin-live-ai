#!/usr/bin/env python3
"""给逐帧素材清单补一个 crop 窗口：每个动作按自己的人物包围盒裁到人物。

素材是完整 640x360 画布，人物只占中间约 235x266（宽 37%、高 74%），
直接铺到舞台上会显得又小又空。这里扫描每个动作的采样帧，取 alpha 包围盒
的并集（保住动作幅度），四周留一点余量，写进 manifest 的 crop 字段。

前端 drawImage 时用这个窗口做源矩形裁剪 + 目标缩放，等价于「裁到人物再放大」，
但**不需要烘焙**：原始 webp 一个字节都不改，换素材也只是重算一次 crop。

用法：
    python3 scripts/shenshen-crop.py                  # 原地更新 manifest
    python3 scripts/shenshen-crop.py --pad 0.10       # 多留边
    python3 scripts/shenshen-crop.py --samples 48     # 采得更密（默认 24）
"""

import argparse
import json
import os
import sys

import numpy as np
from PIL import Image

MANIFEST = "live-assets/stage/manifest-shenshen.json"
SRC_ROOT = ".."
ALPHA_FLOOR = 8      # alpha ≤ 8 视为透明，滤掉压缩噪点
CANVAS = (640, 360)
# 所有动作的裁剪窗口统一这个高度：高度一致，前端算出来的缩放比才一致，
# 切换动作时人物不会忽大忽小。个别动作的飘散特效会被裁掉边角，人物本体保留。
TARGET_H = 330


def frame_path(src_root, clip_dir, i):
    part, sub = clip_dir.split("/", 1)
    return os.path.join(src_root, f"shenshen-2.0.0-base-640x360.{part}", sub, f"f_{i:04d}.webp")


def measure(src_root, clip, samples):
    n = clip["frames"]
    # 首尾必采，中间等距；集合去重，短动作（118 帧）也不会重复采同一帧
    idxs = sorted({1, n} | {min(n, int(n * k / samples) + 1) for k in range(samples)})
    minx = miny = 10 ** 9
    maxx = maxy = -1
    feet = []          # 每帧「脚底中心」的 x，用来对齐人物本体
    for i in idxs:
        p = frame_path(src_root, clip["dir"], i)
        if not os.path.exists(p):
            continue
        a = np.asarray(Image.open(p).convert("RGBA"))[..., 3]
        ys, xs = np.nonzero(a > ALPHA_FLOOR)
        if not len(xs):
            continue
        minx = min(minx, int(xs.min()))
        maxx = max(maxx, int(xs.max()) + 1)
        miny = min(miny, int(ys.min()))
        maxy = max(maxy, int(ys.max()) + 1)
        # 脚下 18% 的行里求 alpha 加权重心：比全图重心稳，不会被右上角的
        # 音符、头顶的道具这些特效把中心拉偏
        y_top = int(maxy - (maxy - miny) * 0.18)
        foot = (ys >= y_top)
        if foot.any():
            w = a[ys[foot], xs[foot]].astype(np.float64)
            if w.sum() > 0:
                feet.append(float((xs[foot] * w).sum() / w.sum()))
    if maxx < 0:
        return [0, 0, *CANVAS], CANVAS[0] / 2, CANVAS[1]
    w, h = maxx - minx, maxy - miny
    px, py = int(w * clip_pad), int(h * clip_pad)
    x0, y0 = max(0, minx - px), max(0, miny - py)
    x1, y1 = min(CANVAS[0], maxx + px), min(CANVAS[1], maxy + py)

    # 高度统一到 TARGET_H，底边（人物脚底）不动。动作幅度大的（跃起、飘散特效）
    # 会把窗口往上撑，这里截掉多余的上方空白，让所有动作的缩放比一致。
    hh = y1 - y0
    if hh > TARGET_H:
        y0 = y1 - TARGET_H
        hh = TARGET_H
    elif hh < TARGET_H:
        y0 = max(0, y1 - TARGET_H)
        hh = y1 - y0

    # 人物本体中心（相对裁剪窗口左边缘）。前端按它对中，
    # 免得含特效的宽窗口把人物推到一边。
    foot_cx = float(np.median(feet)) if feet else (x0 + x1) / 2
    return [x0, y0, x1 - x0, hh], foot_cx - x0, y1 - y0


def main():
    global clip_pad
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default=MANIFEST)
    ap.add_argument("--src-root", default=SRC_ROOT)
    ap.add_argument("--pad", type=float, default=0.06, help="四周留白比例（相对人物宽高）")
    ap.add_argument("--samples", type=int, default=24, help="每个动作采多少帧")
    args = ap.parse_args()
    clip_pad = args.pad

    with open(args.manifest) as f:
        manifest = json.load(f)

    total = len(manifest["clips"])
    for n, (slug, clip) in enumerate(manifest["clips"].items(), 1):
        crop, foot_cx, foot_y = measure(args.src_root, clip, args.samples)
        clip["crop"] = crop
        # 前端按 foot 对齐：水平用脚底中心，垂直用脚底，特效可以不对称地伸出去
        clip["foot"] = [round(foot_cx, 1), int(foot_y)]
        if n % 10 == 0:
            print(f"  {n}/{total} …", file=sys.stderr)

    # 原子替换：中途崩了也不会留下半个 JSON（前面就踩过这个坑）
    tmp = args.manifest + ".tmp"
    with open(tmp, "w") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    os.replace(tmp, args.manifest)

    crops = [c["crop"] for c in manifest["clips"].values()]
    ws = [c[2] for c in crops]
    hs = [c[3] for c in crops]
    print(f"写入 {total} 个 crop：宽 {min(ws)}~{max(ws)}  高 {min(hs)}~{max(hs)}（画布 640x360）")


if __name__ == "__main__":
    main()
