#!/usr/bin/env python3
"""把 live-assets/pet 的黑底素材烘焙成**带真 alpha 通道**的 VP9 webm。

为什么不继续用运行时亮度抠像：源素材是「角色叠加在纯黑上」（C = α·F），边缘像素
的颜色本身就被黑色冲淡了。亮度抠像只估得出 α，颜色仍是那个被染黑的 C，合成时
又乘一次不透明度，于是整圈轮廓暗一道——在白底/浅背景上就是黑边（实测偏橄榄绿）。

这里把 α 直接写进视频：
  1. 背景 = 与画布四边连通的近黑区域；角色内部的黑线稿（眉毛/睫毛/描边）
     不与边缘连通，所以不会被当成背景抠掉；
  2. 边缘带按「本像素亮度 / 邻域最亮」估覆盖度，天然带抗锯齿；
  3. 去污染：半透明像素按 F = C / α 反解真实颜色，把混进去的黑去掉；
  4. 裁到人物、按预乘空间放大 3 倍 + 轻度锐化（放大必须在预乘空间做，否则又会糊出黑边）；
  5. 剥掉原画自带的白色外描边——只删与背景连通的那一圈，绝不往角色内部打孔。

浏览器拿到真 alpha 视频，不需要任何滤镜，边缘天然干净。

用法：
    python3 scripts/key-black.py --force            # 默认参数就是线上那套（裁 + 3 倍，输出 1164×1056）
    python3 scripts/key-black.py --scale 1          # 不放大（文件小一半以上）
    python3 scripts/key-black.py --only idle,wave
"""

import argparse
import json
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

LUMA = np.array((0.2126, 0.7152, 0.0722), np.float32)


def probe(path):
    out = subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=width,height,r_frame_rate", "-of", "json", path,
    ])
    s = json.loads(out)["streams"][0]
    return s["width"], s["height"], s["r_frame_rate"]


def decode(path, w, h):
    p = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
        stdout=subprocess.PIPE,
    )
    size = w * h * 3
    try:
        while True:
            buf = p.stdout.read(size)
            if len(buf) < size:
                break
            yield np.frombuffer(buf, np.uint8).reshape(h, w, 3).astype(np.float32) / 255.0
    finally:
        p.stdout.close()
        p.wait()


def build_alpha(rgb, dark, band_px, min_blob=400, dewhite_px=6):
    luma = rgb @ LUMA

    mask = luma < dark
    lab, count = ndimage.label(mask)
    if count == 0:
        return np.ones_like(luma), np.zeros(mask.shape, bool)

    border = np.unique(np.concatenate([lab[0, :], lab[-1, :], lab[:, 0], lab[:, -1]]))
    border = border[border != 0]
    bg = np.isin(lab, border) if border.size else np.zeros(mask.shape, bool)

    # 被角色围住的纯黑区也是背景。上面的连通域只认「和画布四边连通」，
    # 这些封闭口袋进不去——呆毛弧线的内圈、臂弯、发丝之间的缝隙——
    # 会被当成角色内部保留成不透明黑，放到舞台上就是头顶一块黑斑。
    # 判据用绝对亮度：实测这类口袋均值 <0.008，而角色最暗的线稿 ≥0.035，
    # 差一个量级，所以不会误伤眼睛、描边这些真正的深色墨线。
    enclosed = mask & ~bg
    if enclosed.any():
        el, n_el = ndimage.label(enclosed)
        if n_el:
            means = ndimage.mean(luma, el, range(1, n_el + 1))
            sizes = ndimage.sum(enclosed, el, range(1, n_el + 1))
            pocket = [i + 1 for i in range(n_el) if sizes[i] >= 20 and means[i] < 0.015]
            if pocket:
                bg = bg | np.isin(el, pocket)

    alpha = np.ones_like(luma)
    if band_px > 0:
        band = (~bg) & ndimage.binary_dilation(bg, iterations=band_px)
        if band.any():
            local_max = ndimage.maximum_filter(luma, size=5)
            alpha[band] = np.clip(luma[band] / np.maximum(local_max[band], 1e-4), 0.0, 1.0)
    alpha[bg] = 0.0

    # 去掉原画自带的白色外描边。Q 版立绘常给角色描一圈白边，好让它在任意背景上
    # 都凸显；抠成透明后这圈就变成一圈白光晕，深色舞台上尤其扎眼。
    # 判据是「贴边 + 中灰亮度 + 低饱和」。亮度窗收在 0.30~0.58 这一窄带：
    # 描边在纯黑底上抗锯齿混色后就落在这里；白蕾丝的亮部 (>0.78) 和蓝发的
    # 高饱和都落在窗外，不会被误伤（放宽到 0.88 时实测会啃掉蕾丝边缘）。
    if dewhite_px > 0:
        depth = ndimage.distance_transform_edt(~bg)
        mx = rgb.max(axis=2)
        mn = rgb.min(axis=2)
        sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0.0)
        outline = (~bg) & (depth <= dewhite_px) & (sat < 0.28) & (luma > 0.30) & (luma < 0.58)
        # 只删与背景连通的那一圈。头顶发饰内部的黑色被很薄的蓝边包着，欧氏距离
        # 同样落在 dewhite_px 内，不加这道连通性检查就会把它打成一片白孔。
        if outline.any():
            comp, _ = ndimage.label(outline)
            touch = np.unique(comp[ndimage.binary_dilation(bg, iterations=2) & outline])
            touch = touch[touch != 0]
            # 找不到与背景连通的成分就整个跳过：宁可留着白边，也不能往角色内部打孔
            outline = np.isin(comp, touch) if touch.size else np.zeros_like(outline)
        if outline.any():
            alpha[outline] = 0.0
            # 描边去掉后，紧里侧可能露出一圈半透明过渡，一起压掉免得留毛边
            inner = ndimage.binary_dilation(outline, iterations=1) & ~outline & (alpha < 0.85)
            alpha[inner] = 0.0

    # 去掉零散孤立碎块：抠像后人物外围常残留几像素的小斑点（压缩噪点、
    # 被误判的极小高光），放到舞台上就是漂浮的黑点/白点。只保留够大的连通块。
    # 判定阈值必须低（0.1）：碎块多数是半透明的，用 0.5 会漏掉一大半。
    # 主角色自身连通，低阈值不会把它误删。
    if min_blob > 0:
        solid = alpha > 0.1
        lab2, n2 = ndimage.label(solid)
        if n2 > 0:
            sizes = ndimage.sum(solid, lab2, range(1, n2 + 1))
            keep = [i + 1 for i, s in enumerate(sizes) if s >= min_blob]
            drop = solid & ~np.isin(lab2, keep)
            if drop.any():
                alpha[drop] = 0.0
                # 碎块周围残留的半透明晕也一并清掉，否则边缘还有一圈毛刺
                halo = ndimage.binary_dilation(drop, iterations=3) & ~solid
                alpha[halo] = 0.0

    return alpha, bg


def bake_frame(rgb, dark, band_px, scale, sharpen, crop=None, outline_px=0.0,
               outline_rgb=(0.030, 0.045, 0.085)):
    alpha, bg = build_alpha(rgb, dark, band_px)

    color = rgb.copy()
    # 只对「确认为半透明」的像素去污染，并且限制增益上限：
    # α 很小时 C/α 会把压缩噪声放大成白色噪点（眼睫毛附近最明显）
    semi = (alpha > 0.12) & (alpha < 0.995) & (~bg)
    if semi.any():
        gain = np.clip(1.0 / alpha[semi], 1.0, 2.2)
        color[semi] = np.clip(rgb[semi] * gain[:, None], 0.0, 1.0)

    if crop is not None:
        x0, y0, x1, y1 = crop
        color = color[y0:y1, x0:x1]
        alpha = alpha[y0:y1, x0:x1]

    if scale > 1:
        # 放大必须走预乘空间
        hs, ws = color.shape[0] * scale, color.shape[1] * scale
        pm = color * alpha[..., None]
        pm_img = Image.fromarray((np.clip(pm, 0, 1) * 255).astype(np.uint8), "RGB")
        a_img = Image.fromarray((alpha * 255).astype(np.uint8), "L")
        pm_up = np.asarray(pm_img.resize((ws, hs), Image.LANCZOS), np.float32) / 255.0
        a_up = np.asarray(a_img.resize((ws, hs), Image.LANCZOS), np.float32) / 255.0
        alpha = np.clip(a_up, 0.0, 1.0)
        color = np.clip(pm_up / np.maximum(alpha[..., None], 1e-3), 0.0, 1.0)

    # 描边：沿轮廓向外画一圈墨线。源素材本来带黑描边，抠成真 alpha 后边缘变成
    # 半透明过渡，深色舞台上轮廓就"化"掉了；旧的黑底+亮度滤镜方案那圈黑边反而是
    # 人物最清晰的边界，所以在这里显式补回来。向外长而不是向内吃：
    # 呆毛弧线、发梢透镜这些本来就细，向内会直接把它们抹掉。
    if outline_px > 0:
        solid = alpha > 0.35
        # 用距离变换取等宽外圈：binary_dilation 按菱形长，斜边会比正边薄一半
        ring = (ndimage.distance_transform_edt(~solid) <= outline_px) & ~solid
        if ring.any():
            color[ring] = outline_rgb
            alpha[ring] = 1.0

    if sharpen:
        # 只锐化颜色；锐化 alpha 会让边缘出硬锯齿。
        # 阈值给高一些：低阈值会在睫毛这种「极暗 + 极亮」交界处造出孤立亮点
        img = Image.fromarray((np.clip(color, 0, 1) * 255).astype(np.uint8), "RGB")
        img = img.filter(ImageFilter.UnsharpMask(radius=0.8, percent=30, threshold=18))
        color = np.asarray(img, np.float32) / 255.0

    rgba = np.empty((color.shape[0], color.shape[1], 4), np.uint8)
    rgba[..., :3] = (np.clip(color, 0, 1) * 255).astype(np.uint8)
    rgba[..., 3] = (np.clip(alpha, 0, 1) * 255).astype(np.uint8)
    return rgba


def encode(frames, out_path, w, h, fps, crf):
    # VP9 的 alpha 是独立平面：必须 yuva420p + auto-alt-ref 0，否则静默丢 alpha
    p = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y",
         "-f", "rawvideo", "-pix_fmt", "rgba", "-s", f"{w}x{h}", "-r", fps, "-i", "-",
         "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-auto-alt-ref", "0",
         "-crf", str(crf), "-b:v", "0", "-row-mt", "1", "-speed", "2",
         "-metadata:s:v:0", "alpha_mode=1", out_path],
        stdin=subprocess.PIPE,
    )
    for f in frames:
        p.stdin.write(f.tobytes())
    p.stdin.close()
    if p.wait() != 0:
        raise RuntimeError(f"编码失败: {out_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="live-assets/pet")
    ap.add_argument("--out", default="live-assets/pet-alpha")
    ap.add_argument("--only", default="", help="只处理这些动作，逗号分隔")
    ap.add_argument("--force", action="store_true", help="覆盖已有输出")
    ap.add_argument("--dark", type=float, default=0.035, help="判黑阈值（亮度）")
    ap.add_argument("--band", type=int, default=2, help="边缘带宽（像素）")
    ap.add_argument("--scale", type=int, default=3, help="放大倍数（1=不放大）")
    ap.add_argument("--crop", default="104,0,492,352",
                    help="裁到人物：x0,y0,x1,y1；留空=保持原构图（默认值是这 25 个动作的包围盒并集）")
    ap.add_argument("--no-sharpen", action="store_true")
    ap.add_argument("--crf", type=int, default=32)
    ap.add_argument("--outline", type=float, default=0,
                    help="轮廓墨线宽度（输出像素，0=不画）；深色舞台上没这圈边人物会发虚")
    args = ap.parse_args()

    only = {a.strip() for a in args.only.split(",") if a.strip()}
    names = sorted(f[:-5] for f in os.listdir(args.src) if f.endswith(".webm"))
    if only:
        names = [n for n in names if n in only]
    if not names:
        sys.exit(f"没找到素材: {args.src}")

    os.makedirs(args.out, exist_ok=True)
    for i, name in enumerate(names, 1):
        src = os.path.join(args.src, name + ".webm")
        dst = os.path.join(args.out, name + ".webm")
        if os.path.exists(dst) and not args.force:
            print(f"[{i}/{len(names)}] 跳过 {name}（已存在，--force 覆盖）")
            continue
        w, h, fps = probe(src)
        crop = None
        if args.crop:
            crop = tuple(int(v) for v in args.crop.split(","))
            if len(crop) != 4:
                sys.exit("--crop 需要 4 个整数：x0,y0,x1,y1")
            crop = (max(0, crop[0]), max(0, crop[1]), min(w, crop[2]), min(h, crop[3]))
        frames = (bake_frame(f, args.dark, args.band, args.scale,
                             not args.no_sharpen, crop, args.outline)
                  for f in decode(src, w, h))
        ow = ((crop[2] - crop[0]) if crop else w) * args.scale
        oh = ((crop[3] - crop[1]) if crop else h) * args.scale
        encode(frames, dst, ow, oh, fps, args.crf)
        print(f"[{i}/{len(names)}] {name:10s} {os.path.getsize(src)//1024:6d}K -> "
              f"{os.path.getsize(dst)//1024:7d}K  ({ow}x{oh})")


if __name__ == "__main__":
    main()
