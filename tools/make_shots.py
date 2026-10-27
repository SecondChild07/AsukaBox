"""把两张手机截图拼成 README 用的 docs/shots.png。

用法：
    python tools/make_shots.py 首页.png "AI 答题设置.png"

两张图按 1:1 并排，底色 #F7F8FC，圆角 + 淡阴影。截图请用同一分钟内拍的，
否则状态栏时间会不一样（这个脚本不会修时间）。
"""
import sys

from PIL import Image, ImageDraw, ImageFilter

SCALE = 2          # 画布按 2 倍出图，README 里缩到 50% 显示更锐利
CANVAS_W, CANVAS_H = 996 * SCALE, 1068 * SCALE
MARGIN_X = 54 * SCALE
GAP = 30 * SCALE
PHONE_W = (CANVAS_W - 2 * MARGIN_X - GAP) // 2
RADIUS = 40 * SCALE
BG = (247, 248, 252)
SHADOW = (206, 212, 230)


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius, fill=255)
    return mask


def phone(path, width):
    shot = Image.open(path).convert("RGB")
    height = round(shot.height * width / shot.width)
    shot = shot.resize((width, height), Image.LANCZOS)
    shot.putalpha(rounded_mask(shot.size, RADIUS))
    return shot


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    left = phone(sys.argv[1], PHONE_W)
    right = phone(sys.argv[2], PHONE_W)

    canvas = Image.new("RGB", (CANVAS_W, CANVAS_H), BG)
    top = (CANVAS_H - left.height) // 2
    positions = [(MARGIN_X, top), (MARGIN_X + PHONE_W + GAP, top)]

    # 先画阴影：把两块圆角矩形糊一层再高斯模糊
    shadow = Image.new("L", (CANVAS_W, CANVAS_H), 0)
    sd = ImageDraw.Draw(shadow)
    for (x, y), im in zip(positions, (left, right)):
        sd.rounded_rectangle([x, y + 6 * SCALE, x + im.width, y + im.height + 6 * SCALE],
                             RADIUS, fill=70)
    shadow = shadow.filter(ImageFilter.GaussianBlur(14 * SCALE))
    canvas.paste(Image.new("RGB", canvas.size, SHADOW), (0, 0), shadow)

    for (x, y), im in zip(positions, (left, right)):
        canvas.paste(im, (x, y), im)

    canvas.save("docs/shots.png", optimize=True)
    print("docs/shots.png", canvas.size)
    return 0


if __name__ == "__main__":
    sys.exit(main())
