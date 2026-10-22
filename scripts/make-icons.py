#!/usr/bin/env python3
"""ContextCap のアイコンを全部ここから生成する。形の定義はこのファイルだけに置く。

図柄は「撮影枠の中で、画面が行にほどける」。四隅のブラケットが撮影枠、
上の塊が撮った画面、下の 3 本が OCR で残る文字。画像は消え、文字が残る。

    python3 scripts/make-icons.py

書き出すもの:
- assets/icon.svg                          macOS アプリアイコンのマスター（build-app.sh が sips で .icns にする）
- assets/menubar.svg                       メニューバー用のテンプレート図形（build-app.sh が PNG にする）
- android/.../drawable/ic_launcher_foreground.xml   アダプティブアイコンの前景（ベクタ）
- android/.../drawable/ic_launcher_monochrome.xml   テーマアイコン用（前景と同じ図形）
- lp/site/icon.svg                         LP の favicon とヘッダーのロゴ

標準ライブラリだけで動く。PNG への変換は macOS 標準の sips に任せる。
"""
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

INK = "#EDEDED"   # 図柄の色（PV・LP の文字色）
GROUND = "#0A0A0A"  # 地の色（PV・LP の黒）

# 1024 の座標系で、中心 (512, 512) に対する図柄の寸法
FRAME = 520        # ブラケットの外形（線の中心）
ARM = 120          # ブラケットの腕の長さ
CORNER = 40        # ブラケットの角の半径
STROKE = 40        # ブラケットの線幅
BLOCK = (382, 372, 260, 98, 22)  # 画面の塊: x, y, 幅, 高さ, 角の半径
LINES = [(520, 260), (576, 210), (632, 140)]  # 行: y, 長さ（x は塊の左端から）
LINE_W = 28

# macOS の地。Apple の 1024 テンプレートどおり 824 角を中央に置く
BODY = 824
BODY_RADIUS = 185


def brackets(frame=FRAME, arm=ARM, r=CORNER, c=512):
    h = frame / 2
    x0, y0, x1, y1 = c - h, c - h, c + h, c + h
    return [
        f"M{x0} {y0 + arm} V{y0 + r} A{r} {r} 0 0 1 {x0 + r} {y0} H{x0 + arm}",
        f"M{x1 - arm} {y0} H{x1 - r} A{r} {r} 0 0 1 {x1} {y0 + r} V{y0 + arm}",
        f"M{x1} {y1 - arm} V{y1 - r} A{r} {r} 0 0 1 {x1 - r} {y1} H{x1 - arm}",
        f"M{x0 + arm} {y1} H{x0 + r} A{r} {r} 0 0 1 {x0} {y1 - r} V{y1 - arm}",
    ]


def rounded_rect(x, y, w, h, r):
    return (
        f"M{x + r} {y} H{x + w - r} A{r} {r} 0 0 1 {x + w} {y + r} V{y + h - r} "
        f"A{r} {r} 0 0 1 {x + w - r} {y + h} H{x + r} A{r} {r} 0 0 1 {x} {y + h - r} "
        f"V{y + r} A{r} {r} 0 0 1 {x + r} {y} Z"
    )


def lines(line_w=LINE_W):
    x = BLOCK[0]
    return [f"M{x + line_w / 2} {y} H{x + length - line_w / 2}" for y, length in LINES]


def mark_svg(color, stroke=STROKE, line_w=LINE_W):
    br = "".join(f'<path d="{d}"/>' for d in brackets())
    ln = "".join(f'<path d="{d}"/>' for d in lines(line_w))
    return (
        f'<g fill="none" stroke="{color}" stroke-width="{stroke}" stroke-linecap="round" '
        f'stroke-linejoin="round">{br}</g>'
        f'<path d="{rounded_rect(*BLOCK)}" fill="{color}"/>'
        f'<g fill="none" stroke="{color}" stroke-width="{line_w}" stroke-linecap="round">{ln}</g>'
    )


def svg(body, view="0 0 1024 1024", size=1024):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="{view}">{body}</svg>\n'
    )


def app_icon():
    off = (1024 - BODY) / 2
    ground = f'<rect x="{off}" y="{off}" width="{BODY}" height="{BODY}" rx="{BODY_RADIUS}" fill="{GROUND}"/>'
    return svg(ground + mark_svg(INK))


def menubar_icon():
    # メニューバーは 18pt。線を細いまま縮めると 1x で消えるので、線を太らせて
    # 図柄の外形（線の外側まで）がちょうど枠に収まるよう viewBox を切る
    stroke, line_w = 64, 48
    pad = stroke / 2
    lo = 512 - FRAME / 2 - pad
    span = FRAME + stroke
    return svg(mark_svg("#000000", stroke, line_w), f"{lo} {lo} {span} {span}", 36)


# Android: 108dp の前景に、ブラケットの外形を 46dp で置く。46dp なのは、
# 角の外側（線幅の半分を含む）が中心から 33dp、つまり 66dp の安全圏の内側に入る上限だから
ANDROID_FRAME_DP = 46


def android_vector(comment):
    k = ANDROID_FRAME_DP / FRAME
    o = 54 - 512 * k

    def tx(d):
        # パスの数値をすべて 1024 系から dp 系へ写す。A コマンドの半径・フラグも含むので、
        # 座標だけでなく半径も同じ k で縮む。フラグ（0/1）は値が変わらないよう別扱いにする
        out, tokens = [], d.replace(",", " ").split()
        i = 0
        while i < len(tokens):
            t = tokens[i]
            cmd = t[0] if t[0].isalpha() else ""
            num = t[1:] if cmd else t
            if cmd == "A":
                rx, ry = float(num), float(tokens[i + 1])
                rot, large, sweep = tokens[i + 2], tokens[i + 3], tokens[i + 4]
                x, y = float(tokens[i + 5]), float(tokens[i + 6])
                out.append(f"A{rx * k:.3f},{ry * k:.3f} {rot} {large},{sweep} {x * k + o:.3f},{y * k + o:.3f}")
                i += 7
                continue
            if cmd == "Z":
                out.append("Z")
                i += 1
                continue
            if cmd in ("M",):
                x, y = float(num), float(tokens[i + 1])
                out.append(f"M{x * k + o:.3f},{y * k + o:.3f}")
                i += 2
                continue
            if cmd in ("H", "V"):
                out.append(f"{cmd}{float(num) * k + o:.3f}")
                i += 1
                continue
            raise ValueError(f"unexpected token {t!r} in {d!r}")
        return " ".join(out)

    paths = []
    for d in brackets():
        paths.append(
            f'    <path\n        android:pathData="{tx(d)}"\n        android:strokeColor="{INK}"\n'
            f'        android:strokeWidth="{STROKE * k:.3f}"\n        android:strokeLineCap="round"\n'
            f'        android:strokeLineJoin="round" />'
        )
    paths.append(f'    <path\n        android:pathData="{tx(rounded_rect(*BLOCK))}"\n        android:fillColor="{INK}" />')
    for d in lines():
        paths.append(
            f'    <path\n        android:pathData="{tx(d)}"\n        android:strokeColor="{INK}"\n'
            f'        android:strokeWidth="{LINE_W * k:.3f}"\n        android:strokeLineCap="round" />'
        )
    body = "\n\n".join(paths)
    return (
        f"<!--\n{comment}\n  scripts/make-icons.py が生成する。手で直さない。\n-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108">\n\n'
        f"{body}\n</vector>\n"
    )


def check_android_safe_zone():
    k = ANDROID_FRAME_DP / FRAME
    outer = (FRAME / 2 - CORNER) * math.sqrt(2) + CORNER + STROKE / 2
    assert outer * k <= 33, f"corner reaches {outer * k:.2f}dp, outside the 33dp safe radius"


def main():
    check_android_safe_zone()
    res = ROOT / "android/app/src/main/res/drawable"
    outputs = {
        ROOT / "assets/icon.svg": app_icon(),
        ROOT / "assets/menubar.svg": menubar_icon(),
        ROOT / "lp/site/icon.svg": app_icon(),
        res / "ic_launcher_foreground.xml": android_vector(
            "  アダプティブアイコンの前景。地は @color/ic_launcher_background（GROUND と同じ色）。"
        ),
        res / "ic_launcher_monochrome.xml": android_vector(
            "  テーマアイコン用。前景と同じ図形で、系統色で塗り直されるので色は形を描くためだけのもの。"
        ),
    }
    for path, text in outputs.items():
        if not path.parent.is_dir():
            # lp/ はまだ main に入っていないことがある。無ければ飛ばす
            print(f"skip {path.relative_to(ROOT)} (no {path.parent.relative_to(ROOT)}/)")
            continue
        path.write_text(text)
        print(f"wrote {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
