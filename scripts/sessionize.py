#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""OCR テキストを近似重複除去してセッションに切り、LLM に渡る実トークン量を測る。

なぜ必要か: 完全一致での重複除去は 10% しか効かない（08-04 実測 1,098 行 → 983 ユニーク）。
OCR のノイズで同じ画面でも 1〜2 文字ずれるため。文字 n-gram の Jaccard で近似一致を取る。

    ./scripts/sessionize.py --report
    ./scripts/sessionize.py --day 2026-08-04 --dump-session 3

判定は「直前に採用したフレーム」との比較。直前フレームと比べると、
入力途中の画面のように少しずつ変化する系列で差分が永遠に閾値未満になり、
最初の 1 枚しか残らない（本文が育つ過程を丸ごと落とす）。
"""

import argparse
import sqlite3
import sys
from pathlib import Path

ANALYSIS = Path.home() / "ContextCap-analysis"
SHINGLE = 4


def shingles(text: str) -> set[str]:
    """空白を潰した上での文字 n-gram。日本語は語分割できないので文字単位で取る。"""
    s = "".join(text.split())
    if len(s) < SHINGLE:
        return {s} if s else set()
    return {s[i : i + SHINGLE] for i in range(len(s) - SHINGLE + 1)}


def jaccard(a: set[str], b: set[str]) -> float:
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    inter = len(a & b)
    return inter / (len(a) + len(b) - inter)


def load(db: Path, day: str | None) -> list[dict]:
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    q = "SELECT day, base, ts, text, lines, gen, status FROM shots"
    args: tuple = ()
    if day:
        q += " WHERE day = ?"
        args = (day,)
    q += " ORDER BY ts"
    rows = [
        {"day": d, "base": b, "ts": t, "text": x or "", "lines": ln, "gen": g, "status": st}
        for d, b, t, x, ln, g, st in con.execute(q, args)
    ]
    con.close()
    return rows


def dedupe(rows: list[dict], thr: float) -> list[dict]:
    """直前に採用したフレームとの Jaccard が thr 以上なら畳む。run 長を dwell として持つ。"""
    kept: list[dict] = []
    last_sh: set[str] | None = None
    for r in rows:
        sh = shingles(r["text"])
        if last_sh is not None and jaccard(last_sh, sh) >= thr:
            kept[-1]["dwell"] += 1
            kept[-1]["until"] = r["ts"]
            continue
        r = dict(r, dwell=1, until=r["ts"])
        kept.append(r)
        last_sh = sh
    return kept


def validate(rows: list[dict], thr: float) -> None:
    """畳んだフレームの本文が、採用フレーム側に残っているかを全ドロップ分で測る。

    削減率だけ見て「効いた」と言わないための検算。畳まれた側にしか無い 4-gram が
    多いなら、それは重複ではなく取りこぼしている本文。
    """
    last_sh: set[str] | None = None
    last_ref: dict | None = None
    dropped = 0
    loss_ratios: list[float] = []
    worst: list[tuple[float, dict, dict]] = []

    for r in rows:
        sh = shingles(r["text"])
        if last_sh is not None and jaccard(last_sh, sh) >= thr:
            dropped += 1
            # 畳まれた側にしか無い 4-gram の割合 = 失った本文の量
            only_here = len(sh - last_sh) / len(sh) if sh else 0.0
            loss_ratios.append(only_here)
            assert last_ref is not None
            worst.append((only_here, r, last_ref))
            continue
        last_sh = sh
        last_ref = r

    if not loss_ratios:
        print("畳まれたフレームなし")
        return

    loss_ratios.sort()

    def pct(p: float) -> float:
        return loss_ratios[min(len(loss_ratios) - 1, int(len(loss_ratios) * p))]

    print(f"畳んだフレーム {dropped:,} 枚について、"
          f"「採用側に無い 4-gram の割合」= 失った本文の量")
    for p in (0.5, 0.75, 0.9, 0.95, 0.99, 1.0):
        print(f"  p{p * 100:>4.0f}  {100 * pct(min(p, 0.999)):>5.1f}%")
    over = sum(1 for x in loss_ratios if x > 0.10)
    print(f"  10% 超を失った枚数: {over:,} / {dropped:,} ({100 * over / dropped:.1f}%)")

    worst.sort(key=lambda t: -t[0])
    print("\n最も失っている 3 件（畳まれた側にしか無い本文の冒頭）")
    for ratio, r, ref in worst[:3]:
        sh_r, sh_ref = shingles(r["text"]), shingles(ref["text"])
        uniq = "".join(sorted(sh_r - sh_ref))[:160]
        print(f"\n  {r['day']} {r['base']} (失 {100 * ratio:.1f}%) "
              f"← 採用 {ref['base']}")
        print(f"    畳まれた側だけの断片: {uniq}")


def sessionize(frames: list[dict], gap_sec: float) -> list[list[dict]]:
    """時間ギャップでセッションに切る。撮影は 5 秒間隔なので、
    大きな空きはスリープ・アプリ停止・離席を意味する。"""
    out: list[list[dict]] = []
    cur: list[dict] = []
    for f in frames:
        if cur and f["ts"] - cur[-1]["until"] > gap_sec:
            out.append(cur)
            cur = []
        cur.append(f)
    if cur:
        out.append(cur)
    return out


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--db", type=Path, default=ANALYSIS / "ocr.sqlite")
    p.add_argument("--day", help="対象日 (省略で全日)")
    # 既定 0.90 は --validate で実測して決めた（08-03/08-04 の 1,650 枚）:
    #   thr 0.90 → 畳んだ 702 枚の本文ロス最大 6.3%、10% 超は 0 件。
    #              失っている中身は "4.4kt" "1m321s" "33s" 等の時計・カウンタ。
    #   thr 0.80 → 10% 超が 66/877 (7.5%)。
    #   thr 0.70 → 10% 超が 413/1,001 (41.3%)。断片に「実際に」「翻訳」「#64に」など
    #              本文が現れ始める。ここから先は重複除去ではなく取りこぼし。
    # 緩める場合は必ず --validate を通してから決めること。削減率だけ見ない。
    p.add_argument("--thr", type=float, default=0.90, help="近似一致の閾値 (Jaccard)")
    p.add_argument("--gap", type=float, default=300, help="セッション分割のギャップ秒")
    p.add_argument("--report", action="store_true")
    p.add_argument("--sweep", action="store_true", help="閾値を振って削減率を出す")
    p.add_argument("--validate", action="store_true", help="畳んだ分の本文が失われていないか検算")
    p.add_argument("--dump-session", type=int, help="指定インデックスのセッション本文を出す")
    p.add_argument("--timeline", type=int, metavar="CHARS",
                   help="重複除去後の各フレームを時刻＋先頭 CHARS 字で出す（人が読む用）")
    a = p.parse_args()

    if not a.db.exists():
        print(f"DB がない: {a.db}", file=sys.stderr)
        return 1

    rows = load(a.db, a.day)
    if not rows:
        print("データなし", file=sys.stderr)
        return 1

    raw_chars = sum(len(r["text"]) for r in rows)
    print(f"対象 {len(rows):,} 枚 / 生テキスト {raw_chars:,} 字")
    days = sorted({r["day"] for r in rows})
    print(f"日付: {days[0]} 〜 {days[-1]} ({len(days)} 日)\n")

    if a.sweep:
        print("閾値ごとの削減（thr = これ以上似ていたら畳む）")
        print(f"{'thr':>6} {'残枚数':>8} {'残率':>7} {'残文字':>12} {'文字削減':>8}")
        for thr in (0.99, 0.97, 0.95, 0.90, 0.85, 0.80, 0.70):
            kept = dedupe(rows, thr)
            ch = sum(len(k["text"]) for k in kept)
            print(
                f"{thr:>6.2f} {len(kept):>8,} {100 * len(kept) / len(rows):>6.1f}% "
                f"{ch:>12,} {100 * (1 - ch / raw_chars):>7.1f}%"
            )
        return 0

    if a.validate:
        validate(rows, a.thr)
        return 0

    kept = dedupe(rows, a.thr)
    kept_chars = sum(len(k["text"]) for k in kept)
    sessions = sessionize(kept, a.gap)

    if a.timeline:
        import datetime as _dt

        for si, s in enumerate(sessions):
            t0 = _dt.datetime.fromtimestamp(s[0]["ts"]).strftime("%H:%M")
            t1 = _dt.datetime.fromtimestamp(s[-1]["until"]).strftime("%H:%M")
            print(f"\n########## セッション {si}  {s[0]['day']} {t0}-{t1}  "
                  f"{len(s)} 枚 ##########")
            for f in s:
                hhmm = _dt.datetime.fromtimestamp(f["ts"]).strftime("%H:%M")
                body = " ".join(f["text"].split())[: a.timeline]
                dw = f"x{f['dwell']}" if f["dwell"] > 1 else "  "
                print(f"[{hhmm} {dw}] {body}")
        return 0

    print(f"近似重複除去 (thr={a.thr}): {len(rows):,} → {len(kept):,} 枚 "
          f"({100 * len(kept) / len(rows):.1f}%)")
    print(f"文字数: {raw_chars:,} → {kept_chars:,} "
          f"({100 * (1 - kept_chars / raw_chars):.1f}% 削減)")
    print(f"セッション: {len(sessions)} 本 (ギャップ {a.gap:.0f} 秒で分割)\n")

    # 日本語はおおよそ 1 字 1 トークン。Flash-Lite 入力 $0.10/M で概算する。
    tok = kept_chars
    print(f"LLM 入力トークン概算: {tok:,} (日本語 ≒ 1 字 1 トークン)")
    print(f"  Flash-Lite $0.10/M → ${tok / 1e6 * 0.10:.2f}")
    print(f"  Sonnet 級 $3/M     → ${tok / 1e6 * 3:.2f}")
    print(f"  ※ 1 日あたり: ${tok / 1e6 * 0.10 / max(len(days), 1):.3f} (Flash-Lite)\n")

    if a.dump_session is not None:
        i = a.dump_session
        if not 0 <= i < len(sessions):
            print(f"セッション {i} は無い (0〜{len(sessions) - 1})", file=sys.stderr)
            return 1
        s = sessions[i]
        print(f"=== セッション {i}: {len(s)} 枚 ===")
        for f in s[:20]:
            print(f"\n--- {f['day']} {f['base']} (dwell {f['dwell']}) ---")
            print(f["text"][:400])
        return 0

    print("セッション一覧 (長い順に 15 本)")
    ranked = sorted(sessions, key=lambda s: -sum(len(f["text"]) for f in s))[:15]
    for s in ranked:
        ch = sum(len(f["text"]) for f in s)
        span = (s[-1]["until"] - s[0]["ts"]) / 60
        print(f"  {s[0]['day']} {s[0]['base'][:6]}  {len(s):>4} 枚  "
              f"{span:>6.1f} 分  {ch:>8,} 字")
    return 0


if __name__ == "__main__":
    sys.exit(main())
