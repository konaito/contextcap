#!/usr/bin/env python3
"""contextcap-sub のアダプティブアイコン前景を assets/icon.png の色反転から生成する。

make-android-icon.py と同じ配置（108dp キャンバスに 64dp の元絵、不透明の地）だが、
RGB を反転して ContextCap と色違いにする。地も反転色の縦グラデにする。

    uv run --with pillow python scripts/make-sub-icon.py
"""
from PIL import Image, ImageOps

MASTER = "assets/icon.png"
OUT_DIR = "android/sub/src/main/res"

# make-android-icon.py の地（反転前）。ここを反転して sub の地にする
TOP = (255 - 22, 255 - 32, 255 - 70)      # (233, 223, 185)
BOTTOM = (255 - 14, 255 - 26, 255 - 59)   # (241, 229, 196)

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


def inverted_master() -> Image.Image:
    master = Image.open(MASTER).convert("RGBA")
    # 縁を少し落とす（make-android-icon.py と同じ理由）
    edge = round(master.size[0] * 0.015)
    master = master.crop((edge, edge, master.size[0] - edge, master.size[1] - edge))
    # RGB だけ反転し、アルファは保つ
    rgb = master.convert("RGB")
    inv = ImageOps.invert(rgb)
    inv.putalpha(master.getchannel("A"))
    return inv


def main() -> None:
    master = inverted_master()
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
