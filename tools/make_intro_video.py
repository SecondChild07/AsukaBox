"""生成「明日箱 2.1」介绍视频（1920×1080 / 30fps / 约 62 秒）。

素材只用仓库里的真实截图（docs/shots-2.1.png 拆出来的两张手机画面 + docs/icon.png），
不掺合成 UI，也不加背景音乐（B 站上传容易撞版权，需要的话自己配）。

    python tools/make_intro_video.py 输出.mp4

脚本会把每一帧直接喂给 ffmpeg 的 stdin，不落中间 PNG。
"""
import io
import math
import os
import subprocess
import sys

import numpy as np
import qrcode
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H, FPS = 1920, 1080, 30
DUR = 62.0

INK = (27, 30, 40)
SUB = (92, 98, 115)
MUTED = (140, 146, 162)
BG_TOP = (247, 248, 253)
BG_BOTTOM = (232, 236, 248)

FONT_BD = r"C:\Windows\Fonts\msyhbd.ttc"
FONT_RG = r"C:\Windows\Fonts\msyh.ttc"

PHONE = (1180, 92, 1580, 981)  # x0, y0, x1, y1
PHONE_R = 34


def font(path, size):
    return ImageFont.truetype(path, size)


def rounded(img, r):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], r, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def make_bg():
    """竖向渐变 + 三个柔光斑，一次算好复用。"""
    small = Image.new("RGB", (48, 27))
    d = ImageDraw.Draw(small)
    for y in range(27):
        k = y / 26
        d.line([(0, y), (48, y)],
               fill=tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * k) for i in range(3)))
    bg = small.resize((W, H), Image.BICUBIC)

    bg = bg.convert("RGBA")
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    blobs = [(-260, -320, 980, (120, 126, 240, 70)), (1400, -260, 940, (110, 196, 232, 60)),
             (960, 740, 1000, (188, 154, 246, 64))]
    for x, y, r, c in blobs:
        gd.ellipse([x, y, x + r, y + r], fill=c)
    glow = glow.filter(ImageFilter.GaussianBlur(140))
    return Image.alpha_composite(bg, glow).convert("RGB")


def make_phone(path, shadow=True):
    shot = Image.open(path).convert("RGB")
    w = PHONE[2] - PHONE[0]
    shot = shot.resize((w, round(shot.height * w / shot.width)), Image.LANCZOS)
    body = rounded(shot, PHONE_R)
    if not shadow:
        return body
    layer = Image.new("RGBA", (w + 120, body.height + 120), (0, 0, 0, 0))
    sh = Image.new("L", layer.size, 0)
    ImageDraw.Draw(sh).rounded_rectangle([60, 66, 60 + w, 66 + body.height], PHONE_R, fill=120)
    sh = sh.filter(ImageFilter.GaussianBlur(26))
    layer.paste(Image.new("RGB", layer.size, (176, 184, 214)), (0, 0), sh)
    layer.alpha_composite(body, (60, 60))
    return layer, (PHONE[0] - 60, PHONE[1] - 60)


def ring(size, r, color):
    """给卡片画一圈描边，外面再带一点发光。"""
    pad = 26
    layer = Image.new("RGBA", (size[0] + pad * 2, size[1] + pad * 2), (0, 0, 0, 0))
    glow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).rounded_rectangle(
        [pad, pad, pad + size[0], pad + size[1]], r, outline=color + (150,), width=10)
    glow = glow.filter(ImageFilter.GaussianBlur(9))
    layer.alpha_composite(glow)
    ImageDraw.Draw(layer).rounded_rectangle(
        [pad, pad, pad + size[0], pad + size[1]], r, outline=color + (255,), width=6)
    return layer, pad


def text_block(label, title, body, accent, width=780):
    """左侧那一段：小标签 + 大标题 + 两三行说明，返回 RGBA 图。"""
    lines_t, lines_b = [], []
    f_label, f_title, f_body = font(FONT_BD, 30), font(FONT_BD, 62), font(FONT_RG, 31)
    for ln in body:
        # 按宽度手动折行（PIL 没有自动换行）
        cur = ""
        for ch in ln:
            if f_body.getlength(cur + ch) > width:
                lines_b.append(cur)
                cur = ch
            else:
                cur += ch
        if cur:
            lines_b.append(cur)

    gap_title = 22 if label else 0
    h = (46 if label else 0) + gap_title + 78 + (len(lines_b) * 48) + 10
    img = Image.new("RGBA", (width + 20, h + 20), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    y = 0
    if label:
        d.text((0, y), label, font=f_label, fill=accent)
        y += 46 + gap_title
    d.text((0, y - 14), title, font=f_title, fill=INK)
    y += 78
    for ln in lines_b:
        d.text((0, y), ln, font=f_body, fill=SUB)
        y += 48
    return img


def outro_block(qr_path):
    img = Image.new("RGBA", (1580, 560), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.text((0, 0), "明日箱 2.1", font=font(FONT_BD, 76), fill=INK)
    d.text((4, 116), "github.com/SecondChild07/AsukaBox", font=font(FONT_BD, 40), fill=INK)
    d.text((4, 190), "Release 里有：瘦包 52 MB / 完整包 281 MB（带语音模型）",
           font=font(FONT_RG, 30), fill=SUB)
    d.text((4, 244), "开源 MIT · Android 7.0+ · 音频在本机转写，不上传",
           font=font(FONT_RG, 30), fill=SUB)
    d.text((4, 316), "AI 答题只在 DeepSeek V4.1 Flash 上验证过", font=font(FONT_RG, 26), fill=MUTED)
    if os.path.exists(qr_path):
        qr = Image.open(qr_path).convert("RGB").resize((240, 240), Image.NEAREST)
        img.alpha_composite(rounded(qr, 12), (1240, 52))
        d.text((1256, 306), "扫码进仓库", font=font(FONT_RG, 24), fill=MUTED)
    return img


def make_qr(url, path):
    qr = qrcode.QRCode(box_size=8, border=1)
    qr.add_data(url)
    qr.make(fit=True)
    img = qr.make_image(fill_color="#1B1E28", back_color="white").convert("RGB")
    img.save(path)


SCENES = [
    dict(t0=0, t1=6, kind="intro"),
    dict(t0=6, t1=12, phone="home", label="", accent=(91, 91, 214),
         title="四件事，一个 App",
         body=["NFC 签到 · 课表 · 虚拟定位 · 学习通助手",
               "纯 Compose 手写，没引第三方 UI 库"]),
    dict(t0=12, t1=18, phone="home", hi=0, label="01 / NFC 签到", accent=(91, 91, 214),
         title="贴一下标签就签到",
         body=["读出标签里的链接，直接拉起支付宝签到页",
               "没有标签也能长按手输一次，之后点按钮就行"]),
    dict(t0=18, t1=24, phone="home", hi=1, label="02 / 课表", accent=(38, 166, 154),
         title="教务课表，自动同步",
         body=["支持正方 zfsoft / jwglxt，单双周、当前周都对",
               "桌面小组件按高度自动切成两行版或单行版"]),
    dict(t0=24, t1=30, phone="home", hi=2, label="03 / 虚拟定位", accent=(124, 108, 240),
         title="把定位钉在任意坐标",
         body=["地图选点、搜城市、粘贴坐标都行",
               "搜具体地点要填自己的高德 Key，免费申请"]),
    dict(t0=30, t1=38, phone="home", hi=3, label="04 / 学习通助手", accent=(240, 147, 43),
         title="作业自动填，只填不交",
         body=["打开就是清单，未完成 / 已完成分开列",
               "检测到答题页自动开填，也可以手动点开始"]),
    dict(t0=38, t1=46, phone="ai", label="听力题", accent=(91, 91, 214),
         title="音频不出这台手机",
         body=["本机 SenseVoice 转写，中英日韩粤一份模型",
               "首次下载 228 MB，之后断网也能用"]),
    dict(t0=46, t1=52, phone="ai", label="AI 配置", accent=(124, 108, 240),
         title="贴个 Key 就能用",
         body=["自动识别服务商和模型，优先挑能读图的",
               "开跑前自检接口，模型名过期会自动换"]),
    dict(t0=52, t1=DUR, kind="outro"),
]

LEFT = (150, 0)


def ensure_assets(asset_dir):
    """把 docs/shots-2.1.png 里的两块手机画面裁出来；素材和截图同源，不会走样。"""
    os.makedirs(asset_dir, exist_ok=True)
    names = ["phone_home.png", "phone_ai.png"]
    if all(os.path.exists(os.path.join(asset_dir, n)) for n in names):
        return
    src = os.path.join(os.path.dirname(__file__), "..", "docs", "shots-2.1.png")
    im = Image.open(src).convert("RGB")
    m, gap = 108, 60
    pw = (im.width - 2 * m - gap) // 2
    ph = round(pw * 2400 / 1080)
    top = (im.height - ph) // 2
    im.crop((m, top, m + pw, top + ph)).save(os.path.join(asset_dir, names[0]))
    x = m + pw + gap
    im.crop((x, top, x + pw, top + ph)).save(os.path.join(asset_dir, names[1]))


def build(out_path, asset_dir):
    bg = make_bg()
    home, home_pos = make_phone(os.path.join(asset_dir, "phone_home.png"))
    ai, ai_pos = make_phone(os.path.join(asset_dir, "phone_ai.png"))

    # 源图（858 宽）里的卡片位置，按画面上的手机宽度换算
    src = [(30, 350, 828, 500), (30, 520, 828, 670),
           (30, 690, 828, 840), (30, 860, 828, 1010)]
    k = (PHONE[2] - PHONE[0]) / 858
    cards = [tuple(int(v * k) for v in b) for b in src]
    rings = [ring((c[2] - c[0], c[3] - c[1]), int(36 * k), (91, 91, 214)) for c in cards]

    icon = Image.open("docs/icon.png").convert("RGB").resize((176, 176), Image.LANCZOS)
    icon = rounded(icon, 40)

    # 逐场景预渲染文字块，帧循环里只做贴图
    for s in SCENES:
        if s.get("kind") == "intro":
            layer = Image.new("RGBA", (1100, 340), (0, 0, 0, 0))
            d = ImageDraw.Draw(layer)
            d.text((0, 0), "明日箱", font=font(FONT_BD, 104), fill=INK)
            d.text((4, 136), "把零碎的小事收进一个箱子", font=font(FONT_RG, 38), fill=SUB)
            d.text((4, 208), "NFC 签到 · 课表 · 虚拟定位 · 学习通作业助手",
                   font=font(FONT_RG, 30), fill=MUTED)
            d.text((4, 262), "Android 7.0+ · 开源 MIT · 2.1", font=font(FONT_RG, 28), fill=MUTED)
            s["layer"] = layer
        elif s.get("kind") == "outro":
            s["layer"] = outro_block(os.path.join(asset_dir, "qr.png"))
        else:
            b = text_block(s["label"], s["title"], s["body"], s["accent"])
            s["layer"] = b

    # 文字块统一垂直居中
    for s in SCENES:
        lay = s["layer"]
        if s.get("kind") == "intro":
            # 每行文字宽度不同，按最宽那行对中
            widest = max(font(FONT_BD, 104).getlength("明日箱"),
                         font(FONT_RG, 38).getlength("把零碎的小事收进一个箱子"),
                         font(FONT_RG, 30).getlength("NFC 签到 · 课表 · 虚拟定位 · 学习通作业助手"))
            s["pos"] = (int((W - widest) // 2), 430)
        elif s.get("kind") == "outro":
            s["pos"] = (170, 300)
        else:
            s["pos"] = (LEFT[0], (H - lay.height) // 2 - 20)

    ff = subprocess.Popen(
        [_ffmpeg(), "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
         "-r", str(FPS), "-i", "-", "-an",
         "-c:v", "libx264", "-preset", "medium", "-crf", "19",
         "-pix_fmt", "yuv420p", "-movflags", "+faststart", out_path],
        stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    total = int(DUR * FPS)
    for i in range(total):
        t = i / FPS
        frame = bg.copy()

        # 手机：5.4 秒淡入，38 秒处从首页切到对话框，52 秒淡出
        if 5.4 <= t < 52.6:
            fade = min(1.0, (t - 5.4) / 0.8, (52.6 - t) / 0.8)
            if t < 37.6:
                layer, pos = home, home_pos
            elif t < 38.4:
                layer, pos = home, home_pos
            else:
                layer, pos = ai, ai_pos
            img = layer
            if fade < 0.999:
                img = layer.copy()
                img.putalpha(layer.getchannel("A").point(lambda v: int(v * fade)))
            frame.paste(img, pos, img)
            if 37.6 <= t < 38.4:
                ghost = ai.copy()
                ghost.putalpha(ghost.getchannel("A").point(
                    lambda v: int(v * fade * (t - 37.6) / 0.8)))
                frame.paste(ghost, ai_pos, ghost)

        # 高亮圈
        for s in SCENES:
            if "hi" in s and s["t0"] <= t < s["t1"]:
                x0, y0, x1, y1 = cards[s["hi"]]
                rl, pad = rings[s["hi"]]
                a = 0.55 + 0.45 * math.sin((t - s["t0"]) * 3.4)
                a *= min(1.0, (t - s["t0"]) / 0.4)
                px = PHONE[0] + x0 - pad
                py = PHONE[1] + y0 - pad
                tint = rl.copy()
                tint.putalpha(tint.getchannel("A").point(lambda v: int(v * a)))
                frame.paste(tint, (px, py), tint)

        # 文字
        for s in SCENES:
            if not (s["t0"] <= t < s["t1"]):
                continue
            u = t - s["t0"]
            v = s["t1"] - t
            a = min(1.0, u / 0.5) * min(1.0, v / 0.4)
            if a <= 0:
                continue
            lay = s["layer"]
            dy = int((1 - min(1.0, u / 0.5)) * 26)
            if a < 0.999:
                lay = lay.copy()
                lay.putalpha(lay.getchannel("A").point(lambda p: int(p * a)))
            frame.paste(lay, (s["pos"][0], s["pos"][1] + dy), lay)

        # 片头片尾的图标
        if 0.0 <= t < 6.0:
            a = min(1.0, (t - 0) / 0.6) * min(1.0, (6.0 - t) / 0.5)
            ic = icon.copy()
            ic.putalpha(ic.getchannel("A").point(lambda p: int(p * a)))
            frame.paste(ic, (W // 2 - 88, 210), ic)
        if t >= 52:
            a = min(1.0, (t - 52) / 0.6)
            ic = icon.copy()
            ic.putalpha(ic.getchannel("A").point(lambda p: int(p * a)))
            frame.paste(ic, (170, 120), ic)

        ff.stdin.write(frame.convert("RGB").tobytes())
        if i % 150 == 0:
            print(f"  {i}/{total} frames  {t:.1f}s", flush=True)

    ff.stdin.close()
    ff.wait()
    print("done:", out_path, os.path.getsize(out_path) // 1024 // 1024, "MB")


def _ffmpeg():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "intro.mp4"
    assets = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "..", ".vidbuild")
    ensure_assets(assets)
    make_qr("https://github.com/SecondChild07/AsukaBox", os.path.join(assets, "qr.png"))
    os.makedirs(os.path.dirname(os.path.abspath(out)) or ".", exist_ok=True)
    build(out, assets)
