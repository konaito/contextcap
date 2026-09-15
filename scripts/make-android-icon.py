#!/usr/bin/env python3
"""assets/icon.png から Android のアダプティブアイコン前景を生成する。

108dp キャンバスの中央に、元絵の角丸矩形を 72dp で置く。元絵は内側に約 8.8% の
余白を持っているので、実際の絵柄は 60dp 角に収まり、円マスクで欠けるのは
四隅のブラケットの先だけになる（中央のクラスタは 66dp の安全圏に入る）。

前景は不透明。地の紺を 108dp 全面に敷いてから元絵を重ねるので、
どのマスク形状でも紺が途切れない。地は元絵の上下のグラデーションに合わせている。

    python3 scripts/make-android-icon.py
"""
from PIL import Image

MASTER = "assets/icon.png"
OUT_DIR = "android/app/src/main/res"

# 元絵の上端・下端の地の色（実測）。単色で敷くと継ぎ目が出るので縦グラデにする
TOP = (22, 32, 70)
BOTTOM = (14, 26, 59)

# 108dp のうち元絵に割り当てる dp
ARTWORK_DP = 64
CANVAS_DP = 108

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def background(size: int) -> Image.Image:
    bg = Image.new("RGB", (1, size))
    px = bg.load()
    for y in range(size):
        t = y / max(size - 1, 1)
        px[0, y] = tuple(round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
    return bg.resize((size, size), Image.NEAREST)


def main() -> None:
    master = Image.open(MASTER).convert("RGBA")
    # 元絵は角丸の縁が明るい。そのまま貼ると平坦な地の上に輪郭線として浮くので、
    # 縁を 1.5% 落としてから使う（落とした分は同じ紺で埋まるだけ）
    edge = round(master.size[0] * 0.015)
    master = master.crop((edge, edge, master.size[0] - edge, master.size[1] - edge))

    for name, scale in DENSITIES.items():
        canvas_px = round(CANVAS_DP * scale)
        art_px = round(ARTWORK_DP * scale)
        out = background(canvas_px).convert("RGBA")
        art = master.resize((art_px, art_px), Image.LANCZOS)
        off = (canvas_px - art_px) // 2
        out.alpha_composite(art, (off, off))

        path = f"{OUT_DIR}/mipmap-{name}/ic_launcher_foreground.webp"
        out.convert("RGB").save(path, "WEBP", quality=92, method=6)
        print(f"wrote {path} ({canvas_px}px)")


if __name__ == "__main__":
    main()
