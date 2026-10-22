"""プロモ映像の音を合成する。外部の素材は使わない（ライセンスの確認が要らないように）。

- public/ambient.wav: 55 秒。ほとんど聞こえない低い帯域のノイズ。RMS は約 -47 dBFS、山は -36 dBFS で抑える
- public/click.wav: 柔らかいクリック。peak は -33 dBFS

クリックと環境音の山が重なっても、全体の最大が -28 dB を超えないようにしてある
（最初は click -30・ambient の山 -31 で、書き出した mp4 の最大が -26.9 dB になった）。

音量はファイル側で決め、Remotion では上げ下げしない。

  uv run --with numpy python scripts/generate_audio.py
"""

import wave
from pathlib import Path

import numpy as np

RATE = 48_000
DURATION = 55.0
OUT = Path(__file__).resolve().parent.parent / "public"
rng = np.random.default_rng(20261022)


def db(value: float) -> float:
    return 10 ** (value / 20)


def one_pole_lowpass(x: np.ndarray, cutoff: float) -> np.ndarray:
    """1 次のローパス。ループで書くと遅いので、係数を固定して累積で回す"""
    a = np.exp(-2 * np.pi * cutoff / RATE)
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def write(path: Path, x: np.ndarray) -> None:
    pcm = np.clip(x, -1, 1)
    pcm = (pcm * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def ambient() -> np.ndarray:
    n = int(RATE * DURATION)
    # 白色雑音を 2 回ローパスして、低い帯域だけ残す（120Hz 付近から下）
    x = rng.standard_normal(n)
    x = one_pole_lowpass(x, 180.0)
    x = one_pole_lowpass(x, 120.0)
    # ごくゆっくり揺らす。うねりに気づかない程度（周期 13 秒、±20%）
    t = np.arange(n) / RATE
    x *= 1 + 0.2 * np.sin(2 * np.pi * t / 13.0)
    # 頭と尻を 3 秒かけて出し入れする
    fade = int(RATE * 3)
    env = np.ones(n)
    env[:fade] = np.linspace(0, 1, fade) ** 2
    env[-fade:] = np.linspace(1, 0, fade) ** 2
    x *= env
    rms = np.sqrt(np.mean(x[fade:-fade] ** 2))
    x = x * (db(-46) / rms)
    # 山だけ柔らかく抑える（tanh）。RMS はほとんど変わらない
    ceiling = db(-36)
    return np.tanh(x / ceiling) * ceiling


def click() -> np.ndarray:
    n = int(RATE * 0.25)
    t = np.arange(n) / RATE
    # 高すぎない短い正弦と、ごく短いノイズの立ち上がり。指数で速く減衰させる
    tone = np.sin(2 * np.pi * 1450 * t) * np.exp(-t / 0.018)
    body = np.sin(2 * np.pi * 620 * t) * np.exp(-t / 0.035) * 0.5
    transient = rng.standard_normal(n) * np.exp(-t / 0.0025) * 0.25
    x = tone + body + transient
    x = one_pole_lowpass(x, 3500.0)
    # 立ち上がりの角を丸める（2ms）
    attack = int(RATE * 0.002)
    x[:attack] *= np.linspace(0, 1, attack)
    return x * (db(-33) / np.max(np.abs(x)))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    a = ambient()
    c = click()
    write(OUT / "ambient.wav", a)
    write(OUT / "click.wav", c)
    print(f"ambient: {len(a) / RATE:.1f}s rms={20 * np.log10(np.sqrt(np.mean(a ** 2))):.1f} dBFS "
          f"peak={20 * np.log10(np.max(np.abs(a))):.1f} dBFS")
    print(f"click:   {len(c) / RATE:.2f}s peak={20 * np.log10(np.max(np.abs(c))):.1f} dBFS")


if __name__ == "__main__":
    main()
