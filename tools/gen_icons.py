#!/usr/bin/env python3
"""
XDFHook 图标生成器（Pillow 版）。

极简设计：深蓝渐变底 + 一笔 J 形钩（白，圆头粗线）+ 钩弯内一颗琥珀圆点。
输出：app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher{,_round}.png
      （API 26+ 使用 mipmap-anydpi-v26 自适应图标，PNG 为低版本回退）
用法：python3 tools/gen_icons.py
"""
import os

from PIL import Image, ImageDraw

VIEW = 108.0
SS = 4
OUT_DPI = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]

# ---- 几何（108 视口，包围盒居中于 (54,54)）----
STEM_TOP = (67.0, 33.5)     # 钩杆顶
STEM_BOT = (67.0, 61.5)     # 杆底 = 弧起点
ARC_C = (54.0, 61.5)        # 底弧圆心
ARC_R = 13.0                # 弧半径（中心线）
TIP = (41.0, 49.5)          # 钩尖（弧终点向上）
DOT = (54.0, 63.5)          # 钩弯内圆点
DOT_R = 4.5

LINE_W = 9.0                # 线宽

BG0 = (0x12, 0x1B, 0x34)
BG1 = (0x2E, 0x3E, 0x86)
HOOK_COL = (0xF2, 0xF6, 0xFF)
DOT_COL = (0xFF, 0xC4, 0x2E)


def render(size, round_mask):
    s = size * SS
    k = s / VIEW

    # 背景：对角渐变（小图逐像素，平滑放大）
    g = Image.new("RGB", (256, 256))
    gp = g.load()
    for yy in range(256):
        for xx in range(256):
            t = (xx + yy) / 510.0
            gp[xx, yy] = tuple(int(BG0[i] * (1 - t) + BG1[i] * t + 0.5) for i in range(3))
    img = g.resize((s, s), Image.BILINEAR).convert("RGBA")

    d = ImageDraw.Draw(img)
    r = LINE_W * k / 2.0

    def cap(x, y, col):
        d.ellipse([x * k - r, y * k - r, x * k + r, y * k + r], fill=col)

    # J 形钩：杆 -> 底弧 -> 尖（一条连续路径，衔接处等宽无缝）
    d.line([STEM_TOP[0] * k, STEM_TOP[1] * k, STEM_BOT[0] * k, STEM_BOT[1] * k],
           fill=HOOK_COL, width=int(round(LINE_W * k)))
    ro = (ARC_R + LINE_W / 2.0) * k
    c = (ARC_C[0] * k, ARC_C[1] * k)
    d.arc([c[0] - ro, c[1] - ro, c[0] + ro, c[1] + ro],
          start=0, end=180, fill=HOOK_COL, width=int(round(LINE_W * k)))
    d.line([41.0 * k, 61.5 * k, TIP[0] * k, TIP[1] * k],
           fill=HOOK_COL, width=int(round(LINE_W * k)))
    cap(*STEM_TOP, HOOK_COL)
    cap(*TIP, HOOK_COL)

    # 琥珀圆点
    dot_r = DOT_R * k
    d.ellipse([DOT[0] * k - dot_r, DOT[1] * k - dot_r,
               DOT[0] * k + dot_r, DOT[1] * k + dot_r], fill=DOT_COL)

    img = img.resize((size, size), Image.LANCZOS)
    if round_mask:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        img.putalpha(mask)
    return img


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "..", "app", "src", "main", "res")
    for name, size in OUT_DPI:
        dd = os.path.join(root, "mipmap-%s" % name)
        os.makedirs(dd, exist_ok=True)
        render(size, False).save(os.path.join(dd, "ic_launcher.png"))
        render(size, True).save(os.path.join(dd, "ic_launcher_round.png"))
        print("mipmap-%s: %dx%d ok" % (name, size, size))
    render(512, False).save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "preview.png"))
    print("preview: tools/preview.png")


if __name__ == "__main__":
    main()
