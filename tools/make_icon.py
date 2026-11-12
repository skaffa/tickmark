"""Generates the Tickmark icon as 32x32 pixel art, in the Ghost Mason / Ortho / Gauge icon style.

Style: dark rounded-square badge (#10161C) with 4-px stepped corners, soft radial glow, a block
character with a cute face, cream 4-point sparkles. Subject: an output chest with a cute face under a
cream tally of tick marks (four strokes and a slash): the name, and the count Tickmark keeps.

Usage: python tools/make_icon.py  -> media/icon.png (512), media/icon-128.png (128),
       and src/main/resources/assets/tickmark/icon.png (128)
"""
import math
import os
import sys

from PIL import Image

N = 32
BG = (16, 22, 28, 255)
GLOW = (52, 40, 22)
LID = (196, 132, 62, 255)
LID_HI = (226, 168, 92, 255)
BODY = (160, 100, 44, 255)
BODY_DARK = (118, 72, 30, 255)
BAND = (70, 44, 20, 255)
LATCH = (222, 222, 230, 255)
EYE = (40, 22, 8, 255)
EYE_HI = (255, 240, 220, 255)
MARK = (255, 236, 170, 255)
MARK_SHADE = (190, 160, 100, 255)
SPARK = (255, 236, 140)


def blend(dst, src):
    """Alpha-composite src (r,g,b,a) over dst (r,g,b,a)."""
    sa = src[3] / 255
    da = dst[3] / 255
    oa = sa + da * (1 - sa)
    if oa == 0:
        return (0, 0, 0, 0)
    c = tuple(round((src[i] * sa + dst[i] * da * (1 - sa)) / oa) for i in range(3))
    return c + (round(oa * 255),)


def corner_cut(x, y):
    """Stepped rounded corners: 4,3,2,1 transparent pixels on rows 0..3 (mirrored)."""
    steps = [4, 3, 2, 1]
    for yy, xx in ((y, x), (N - 1 - y, x), (y, N - 1 - x), (N - 1 - y, N - 1 - x)):
        if yy < 4 and xx < steps[yy]:
            return True
    return False



def main(out_dir, res_dir):
    px = [[(0, 0, 0, 0)] * N for _ in range(N)]

    def put(x, y, c):
        if 0 <= x < N and 0 <= y < N and not corner_cut(x, y):
            px[y][x] = blend(px[y][x], c) if c[3] < 255 else c

    for y in range(N):
        for x in range(N):
            if corner_cut(x, y):
                continue
            d = math.hypot(x + 0.5 - 16, y + 0.5 - 16) / 15
            t = max(0.0, 1 - d) ** 1.6
            px[y][x] = tuple(round(BG[i] + (GLOW[i] - BG[i]) * t) for i in range(3)) + (255,)

    # chest: x 7..24, lid y 17..19, body y 20..27
    for x in range(7, 25):
        for y in range(17, 28):
            if y <= 19:
                c = LID_HI if y == 17 else LID
            else:
                c = BODY_DARK if x >= 20 else BODY
            if x in (7, 24) or y == 27:
                c = BAND
            put(x, y, c)
    for x in range(7, 25):
        put(x, 20, BAND)
    for y in range(19, 22):
        put(15, y, LATCH)
        put(16, y, LATCH)
    # face on the body
    for ex in (11, 20):
        for dy in range(2):
            put(ex, 23 + dy, EYE)
        put(ex, 23, EYE_HI)
    put(15, 25, EYE)
    put(16, 25, EYE)

    # tally mark: four strokes and a slash, above the chest
    for i, x in enumerate((9, 13, 17, 21)):
        for y in range(5, 14):
            put(x, y, MARK)
            put(x + 1, y, MARK_SHADE)
    for i in range(20):
        t = i / 19
        x = round(6 + t * 19)
        y = round(12 - t * 6)
        put(x, y, MARK)
        put(x, y + 1, MARK)

    def sparkle(x, y, strong=True):
        put(x, y, SPARK + (255,))
        a = 200 if strong else 140
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            put(x + dx, y + dy, SPARK + (a,))

    sparkle(4, 25, strong=False)
    sparkle(27, 9)

    img = Image.new("RGBA", (N, N))
    img.putdata([c for row in px for c in row])
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(res_dir, exist_ok=True)
    img.resize((512, 512), Image.NEAREST).save(os.path.join(out_dir, "icon.png"))
    img.resize((128, 128), Image.NEAREST).save(os.path.join(out_dir, "icon-128.png"))
    img.resize((128, 128), Image.NEAREST).save(os.path.join(res_dir, "icon.png"))
    print("wrote", out_dir, res_dir)


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    main(os.path.join(root, "media"), os.path.join(root, "src", "main", "resources", "assets", "tickmark"))
