"""Generates the Tickmark icon as 32x32 pixel art, in the Ghost Mason / Ortho / Gauge icon style.

Style: dark rounded-square badge (#10161C) with 4-px stepped corners, soft radial glow, a block
character, cream 4-point sparkles. Subject: a stopwatch (the game-tick clock) with
tick marks around its rim, whose two hands form a green tick mark: both meanings of the name.

Usage: python tools/make_icon.py  -> media/icon.png (512), media/icon-128.png (128),
       and src/main/resources/assets/tickmark/icon.png (128)
"""
import math
import os

from PIL import Image

N = 32
BG = (16, 22, 28, 255)
GLOW = (30, 52, 40)
RIM = (150, 160, 172, 255)
RIM_HI = (214, 222, 230, 255)
RIM_DARK = (84, 92, 104, 255)
FACE = (250, 242, 222, 255)
FACE_SHADE = (226, 214, 188, 255)
TICK = (70, 64, 58, 255)
TICK_MAJOR = (40, 36, 32, 255)
HAND = (70, 214, 96, 255)
HAND_DARK = (36, 150, 62, 255)
HUB = (30, 90, 44, 255)
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
            d = math.hypot(x + 0.5 - 16, y + 0.5 - 17) / 15
            t = max(0.0, 1 - d) ** 1.6
            px[y][x] = tuple(round(BG[i] + (GLOW[i] - BG[i]) * t) for i in range(3)) + (255,)

    cx, cy = 16.0, 18.0
    # crown of the stopwatch
    for x in range(14, 18):
        for y in range(3, 6):
            put(x, y, RIM_HI if y == 3 else RIM)
    for x in range(15, 17):
        put(x, 6, RIM_DARK)
    # case and face
    for y in range(N):
        for x in range(N):
            fx, fy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(fx, fy)
            if d <= 11.6:
                if d > 10.0:
                    # rim: lit from the top left
                    put(x, y, RIM_HI if fx + fy < -6 else RIM_DARK if fx + fy > 6 else RIM)
                else:
                    put(x, y, FACE_SHADE if fx + fy > 9 else FACE)

    # tick marks around the rim: 12, the four quarters longer
    for i in range(12):
        a = math.radians(i * 30)
        major = i % 3 == 0
        r_in = 7.4 if major else 8.6
        for k in range(3):
            r = r_in + k * 0.8
            if r > 9.6:
                break
            put(int(cx + math.sin(a) * r), int(cy - math.cos(a) * r), TICK_MAJOR if major else TICK)

    # the hands form a tick mark: short stroke down from the left, long stroke up to the right
    pivot = (14.5, 22.5)

    def stroke(a, b, w):
        steps = 40
        for i in range(steps + 1):
            t = i / steps
            x = a[0] + (b[0] - a[0]) * t
            y = a[1] + (b[1] - a[1]) * t
            for dx in range(w):
                for dy in range(w):
                    put(int(x + dx - w / 2 + 0.5), int(y + dy - w / 2 + 0.5), HAND)

    stroke((10.5, 18.5), pivot, 2)
    stroke(pivot, (22.0, 11.0), 2)
    # shade under the long stroke
    for i in range(16):
        t = i / 15
        x = pivot[0] + (22.0 - pivot[0]) * t
        y = pivot[1] + (11.0 - pivot[1]) * t
        put(int(x + 1), int(y + 1), HAND_DARK)
    put(int(pivot[0]), int(pivot[1]), HUB)

    def sparkle(x, y, strong=True):
        put(x, y, SPARK + (255,))
        a = 200 if strong else 140
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            put(x + dx, y + dy, SPARK + (a,))

    sparkle(4, 6, strong=False)
    sparkle(27, 27)

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
