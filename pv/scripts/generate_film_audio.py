"""ContextCapFilm の音を合成する。外部の素材は使わない。尺と拍は src/film/plan.json を読む。

public/film/ に書き出す:
- music.wav   55 秒。120BPM の拍に合わせた音楽（低い拍・ハイハット・和音・低音・アルペジオ・ヒット）
              区切りは plan.json の acts / silence / hits / drop
- key.wav     打鍵
- sweep.wav   走査
- whoosh.wav  収束

  uv run --with numpy python scripts/generate_film_audio.py
"""

import json
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
PLAN = json.loads((ROOT / "src" / "film" / "plan.json").read_text())
OUT = ROOT / "public" / "film"
RATE = 48_000
FPS = PLAN["fps"]
BEAT_S = 60 / PLAN["bpm"]
TOTAL_S = PLAN["acts"]["close"]["to"] / FPS
rng = np.random.default_rng(20261022)


def sec(frame: float) -> float:
    return frame / FPS


def act(name: str) -> tuple[float, float]:
    a = PLAN["acts"][name]
    return sec(a["from"]), sec(a["to"])


def db(v: float) -> float:
    return 10 ** (v / 20)


def lowpass(x: np.ndarray, cutoff: float) -> np.ndarray:
    a = np.exp(-2 * np.pi * cutoff / RATE)
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def highpass(x: np.ndarray, cutoff: float) -> np.ndarray:
    return x - lowpass(x, cutoff)


def write(name: str, x: np.ndarray) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(str(OUT / name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
    peak = 20 * np.log10(np.max(np.abs(x)) + 1e-12)
    rms = 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-12)
    print(f"{name:12s} {len(x) / RATE:6.2f}s  peak {peak:6.1f} dBFS  rms {rms:6.1f} dBFS")


N = int(RATE * TOTAL_S)
t_all = np.arange(N) / RATE


def place(buf: np.ndarray, at_s: float, sound: np.ndarray, gain: float = 1.0) -> None:
    i = int(at_s * RATE)
    if i >= len(buf):
        return
    j = min(len(buf), i + len(sound))
    buf[i:j] += sound[: j - i] * gain


# --- 素材 -------------------------------------------------------------------

def kick() -> np.ndarray:
    n = int(RATE * 0.35)
    t = np.arange(n) / RATE
    f = 45 + 75 * np.exp(-t / 0.03)
    x = np.sin(2 * np.pi * np.cumsum(f) / RATE) * np.exp(-t / 0.16)
    x[: int(RATE * 0.002)] *= np.linspace(0, 1, int(RATE * 0.002))
    return x


def hat() -> np.ndarray:
    n = int(RATE * 0.05)
    t = np.arange(n) / RATE
    return highpass(rng.standard_normal(n), 6000.0) * np.exp(-t / 0.012)


def pluck(freq: float, dur: float = 0.22) -> np.ndarray:
    n = int(RATE * dur)
    t = np.arange(n) / RATE
    x = np.sin(2 * np.pi * freq * t) + 0.3 * np.sin(2 * np.pi * freq * 2 * t)
    return x * np.exp(-t / 0.07)


def hit() -> np.ndarray:
    n = int(RATE * 2.4)
    t = np.arange(n) / RATE
    f = 40 + 55 * np.exp(-t / 0.1)
    body = np.sin(2 * np.pi * np.cumsum(f) / RATE) * np.exp(-t / 0.8)
    click = lowpass(rng.standard_normal(n), 2200.0) * np.exp(-t / 0.01) * 0.7
    tail = lowpass(rng.standard_normal(n), 500.0) * np.exp(-t / 0.6) * 0.3
    return body + click + tail


# 4 小節で 1 周する和音（Am・F・C・G）。各音は Hz
CHORDS = [
    (110.00, 130.81, 164.81),
    (87.31, 110.00, 130.81),
    (130.81, 164.81, 196.00),
    (98.00, 123.47, 146.83),
]
BASS = [55.00, 43.65, 65.41, 49.00]


def pad_layer() -> np.ndarray:
    x = np.zeros(N)
    bar_s = BEAT_S * 4
    for b in range(int(TOTAL_S / bar_s) + 1):
        start = b * bar_s
        chord = CHORDS[b % 4]
        n = int(RATE * (bar_s + 0.6))
        t = np.arange(n) / RATE
        env = np.minimum(1, t / 0.25) * np.minimum(1, np.maximum(0, (bar_s + 0.6 - t) / 0.6))
        s = sum(np.sin(2 * np.pi * f * t + k) + 0.25 * np.sin(2 * np.pi * f * 2 * t) for k, f in enumerate(chord))
        place(x, start, s * env)
    return lowpass(x, 1400.0)


def bass_layer() -> np.ndarray:
    x = np.zeros(N)
    bar_s = BEAT_S * 4
    for b in range(int(TOTAL_S / bar_s) + 1):
        f = BASS[b % 4]
        for k in range(8):
            n = int(RATE * BEAT_S * 0.45)
            t = np.arange(n) / RATE
            note = np.sin(2 * np.pi * f * t) * np.exp(-t / 0.18)
            place(x, b * bar_s + k * BEAT_S / 2, note)
    return lowpass(x, 300.0)


def section_gain(t: np.ndarray, points: list[tuple[float, float]]) -> np.ndarray:
    xs, ys = zip(*points)
    return np.interp(t, xs, ys)


def music() -> np.ndarray:
    p0, p1 = act("pain")
    _, turn1 = act("turn")
    s0, s1 = act("short")
    l0, l1 = act("long")
    c0, c1 = act("close")
    sil0, sil1 = sec(PLAN["silence"]["from"]), sec(PLAN["silence"]["to"])
    drop = sec(PLAN["drop"])

    pad = pad_layer()
    bass = bass_layer()
    drums = np.zeros(N)
    hats = np.zeros(N)
    arp = np.zeros(N)
    k = kick()
    h = hat()

    # 拍: 転換のあとから、締めの前の 1 小節まで。長期の幕の後半で 8 分に詰める
    beat = 0
    while beat * BEAT_S < TOTAL_S:
        tb = beat * BEAT_S
        if turn1 <= tb < c0:
            place(drums, tb, k)
        if p0 + BEAT_S * 2 <= tb < sil0 or turn1 <= tb < c0:
            place(hats, tb + BEAT_S / 2, h, 0.8)
        if l0 + (l1 - l0) / 2 <= tb < c0:
            place(hats, tb, h, 0.5)
        beat += 1

    # アルペジオ: 長期の幕で 16 分。和音の音を 2 オクターブ上で回す
    bar_s = BEAT_S * 4
    step = BEAT_S / 4
    i = 0
    tt = l0
    while tt < c0:
        b = int(tt / bar_s)
        chord = CHORDS[b % 4]
        f = chord[i % 3] * (4 if (i // 3) % 2 == 0 else 2)
        place(arp, tt, pluck(f))
        i += 1
        tt += step

    # 区切りごとの音量（0〜1）
    pad_g = section_gain(t_all, [(0, 0), (1, 0.55), (sil0 - 0.05, 0.6), (sil0, 0), (sil1, 0), (sil1 + 0.02, 0.9), (c0, 0.9), (drop - 0.3, 0.5), (drop, 1.0), (c1 - 3, 0.8), (c1, 0)])
    bass_g = section_gain(t_all, [(0, 0), (turn1, 0), (turn1 + 0.01, 1), (c0, 1), (c0 + 0.01, 0), (drop, 0), (drop + 0.01, 0.8), (c1 - 3, 0.6), (c1, 0)])
    arp_g = section_gain(t_all, [(l0, 0.2), (l1, 0.9), (c0, 0.9), (c0 + 0.5, 0)])
    # 締めの前の 1 小節で上がっていくノイズ（ドロップへの助走）
    riser = highpass(rng.standard_normal(N), 2500.0)
    riser_g = section_gain(t_all, [(c0, 0), (drop - 0.05, 1), (drop, 0)]) ** 2

    hits = np.zeros(N)
    for f in PLAN["hits"]:
        place(hits, sec(f), hit())

    mix = (
        pad * db(-20) * pad_g / np.max(np.abs(pad))
        + bass * db(-14) * bass_g / np.max(np.abs(bass))
        + drums * db(-9)
        + hats * db(-30)
        + arp * db(-24) * arp_g / max(1e-9, np.max(np.abs(arp)))
        + riser * db(-36) * riser_g
        + hits * db(-5) / np.max(np.abs(hit()))
    )
    # 無音の区間は完全に抜く（ヒットの頭は残す）
    mute = (t_all >= sil0) & (t_all < sil1)
    mix[mute] = 0
    # 全体のフェードアウト
    mix *= section_gain(t_all, [(0, 1), (c1 - 1.2, 1), (c1, 0)])
    peak = np.max(np.abs(mix))
    return np.tanh(mix / peak * 1.2) * db(-1.5) / np.tanh(1.2)


def key() -> np.ndarray:
    n = int(RATE * 0.06)
    t = np.arange(n) / RATE
    x = highpass(rng.standard_normal(n), 1200.0) * np.exp(-t / 0.006)
    x += 0.4 * np.sin(2 * np.pi * 2400 * t) * np.exp(-t / 0.004)
    x = lowpass(x, 6000.0)
    return x * (db(-24) / np.max(np.abs(x)))


def sweep() -> np.ndarray:
    n = int(RATE * 1.2)
    t = np.arange(n) / RATE
    noise = rng.standard_normal(n)
    x = np.zeros(n)
    seg = n // 20
    for i in range(20):
        cut = 400 + 4000 * (i / 19) ** 2
        s = slice(i * seg, (i + 1) * seg if i < 19 else n)
        x[s] = highpass(lowpass(noise[s], cut), cut * 0.4)
    env = np.sin(np.pi * np.clip(t / 1.2, 0, 1)) ** 2
    x *= env
    return x * (db(-20) / np.max(np.abs(x)))


def whoosh() -> np.ndarray:
    n = int(RATE * 0.7)
    t = np.arange(n) / RATE
    x = lowpass(rng.standard_normal(n), 1100.0)
    env = (1 - np.exp(-t / 0.04)) * np.exp(-t / 0.16)
    x *= env
    return x * (db(-14) / np.max(np.abs(x)))


def main() -> None:
    write("music.wav", music())
    write("key.wav", key())
    write("sweep.wav", sweep())
    write("whoosh.wav", whoosh())
    for old in ("drone.wav", "hit.wav", "shutter.wav"):
        (OUT / old).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
