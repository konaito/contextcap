#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""『Her』型コンパニオンが「いつ話しかけるか」を実データで再現する第1段階フィルター。

    ./scripts/companion-sim.py --candidates > /tmp/cand.json
    ./scripts/companion-sim.py --report

LLM に投げる前の**決定論フィルター**だけをここに置く。理由は 2 つ。
1. LLM 判断はコストがかかるので、候補を数十件まで落としてから渡す
2. 決定論部分だけならデータを変えて何度でも回せる（閾値を実測で決められる）

セッション定義は実測から: フレーム間隔は 10 秒固定で、24,398/25,399 個の間隔が 15 秒未満。
1 分を超える空きは 457 回しかない（= 画面 OFF）。なので gap > 180s をセッション境界に取る。

**アプリ帰属は `pkg_eff`（補間後）を使う。** 生の `pkg` は 37% が `com.android.systemui` で、
中身を読むと実際は YouTube や X の画面。通知シェードやステータスバーが一瞬前面ウィンドウに
なった時に TYPE_WINDOW_STATE_CHANGED が飛び、元アプリに戻る時はイベントが来ないため、
systemui に張り付いたまま戻らない。詳細は README の「前面アプリの誤帰属」。
"""

import argparse
import json
import sqlite3
import sys
from datetime import datetime
from pathlib import Path

ANALYSIS = Path.home() / "ContextCap-analysis"
DB = ANALYSIS / "android.sqlite"

SESSION_GAP = 180.0      # これ以上空いたら画面 OFF とみなす
RETURN_GAP = 1200.0      # 20 分以上空けて戻ってきたら「久しぶり」
LONG_DWELL = 1500.0      # 同一アプリ 25 分で「沼っている」
STUCK_FRAMES = 8         # 同じ画面が 80 秒（詰まり・熟読）
COOLDOWN = 2700.0        # 前回発話から 45 分
DAILY_CAP = 6
HOURLY_CAP = 2

SHINGLE = 4

# 興味シグナル。実データに当ててヒット数を見てから足し引きする（--signals で確認できる）
SIGNALS = {
    "買い物": ["カートに追加", "カートに入れる", "ご注文", "お届け", "レジに進む", "購入手続き",
               "在庫あり", "セール", "クーポン", "送料無料"],
    "予約・移動": ["予約する", "空席", "出発", "到着", "経路", "運賃", "チェックイン",
                   "分後", "乗換"],
    "エラー・詰まり": ["エラー", "失敗しました", "接続できません", "もう一度お試し",
                       "問題が発生", "読み込めません", "タイムアウト"],
    "健康": ["体重", "体脂肪", "BMI", "kg", "睡眠", "歩数", "kcal", "トレーニング"],
    "お金": ["請求", "支払い", "残高", "入金", "振込", "料金", "円/月", "解約"],
    "仕事・開発": ["エラーが発生", "ビルド", "commit", "pull request", "デプロイ",
                   "issue", "TODO", "締切", "納期"],
    "人間関係": ["ありがとう", "ごめん", "おめでとう", "お疲れ", "既読", "返信"],
}


def shingles(text: str) -> set[str]:
    s = "".join(text.split())
    if len(s) < SHINGLE:
        return {s} if s else set()
    return {s[i:i + SHINGLE] for i in range(len(s) - SHINGLE + 1)}


def jaccard(a: set[str], b: set[str]) -> float:
    if not a or not b:
        return 0.0
    inter = len(a & b)
    return inter / (len(a) + len(b) - inter)


def hhmm(ts: float) -> str:
    return datetime.fromtimestamp(ts).strftime("%H:%M")


def load(db: Path) -> list[dict]:
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    rows = [
        {"day": d, "base": b, "ts": t, "text": x or "", "lines": ln or 0,
         "status": st, "pkg": p or "?"}
        for d, b, t, x, ln, st, p in con.execute(
            "SELECT day, base, ts, text, lines, status, pkg_eff FROM shots ORDER BY ts")
    ]
    con.close()
    return rows


def sessions(rows: list[dict]) -> list[list[dict]]:
    out, cur = [], []
    for r in rows:
        if cur and r["ts"] - cur[-1]["ts"] > SESSION_GAP:
            out.append(cur)
            cur = []
        cur.append(r)
    if cur:
        out.append(cur)
    return out


def hit_signals(text: str) -> list[str]:
    return [k for k, words in SIGNALS.items() if any(w in text for w in words)]


def find_candidates(rows: list[dict]) -> list[dict]:
    """トリガーを全部立てる。クールダウンはこの後段で掛ける（何が捨てられたか見たいため）。"""
    cands: list[dict] = []
    sess = sessions(rows)

    for si, s in enumerate(sess):
        prev_end = sess[si - 1][-1]["ts"] if si > 0 else None
        gap = (s[0]["ts"] - prev_end) if prev_end else None
        dur = s[-1]["ts"] - s[0]["ts"]

        # 1) 長く離れて戻ってきた直後（セッション頭。ただし中身が分かるまで少し待つ）
        if gap and gap >= RETURN_GAP and len(s) >= 6:
            anchor = s[min(5, len(s) - 1)]
            cands.append(dict(kind="return", ts=anchor["ts"], si=si,
                              detail=f"{gap / 60:.0f} 分ぶりに画面を点けた", score=2))

        # 2) セッションが終わる直前（使い終わり）。長いセッションだけ
        if dur >= 900:
            cands.append(dict(kind="session_end", ts=s[-1]["ts"], si=si,
                              detail=f"{dur / 60:.0f} 分のセッションが終わる直前", score=1))

        # 3) アプリ滞在の区切りと長時間滞在
        run_pkg, run_start = s[0]["pkg"], s[0]["ts"]
        for r in s[1:] + [None]:  # type: ignore[list-item]
            if r is not None and r["pkg"] == run_pkg:
                if r["ts"] - run_start >= LONG_DWELL and not any(
                        c["kind"] == "long_dwell" and abs(c["ts"] - r["ts"]) < LONG_DWELL
                        for c in cands):
                    cands.append(dict(kind="long_dwell", ts=r["ts"], si=si,
                                      detail=f"{run_pkg} に {(r['ts'] - run_start) / 60:.0f} 分",
                                      score=3))
                continue
            span = (r["ts"] if r else s[-1]["ts"]) - run_start
            if span >= 600:
                cands.append(dict(kind="app_done", ts=(r["ts"] if r else s[-1]["ts"]), si=si,
                                  detail=f"{run_pkg} を {span / 60:.0f} 分見て切り替えた",
                                  score=2))
            if r is None:
                break
            run_pkg, run_start = r["pkg"], r["ts"]

        # 4) 同じ画面のまま止まっている（熟読・詰まり）
        last_sh, run = None, 0
        for r in s:
            sh = shingles(r["text"])
            if last_sh is not None and jaccard(last_sh, sh) >= 0.90:
                run += 1
                if run == STUCK_FRAMES:
                    cands.append(dict(kind="stuck", ts=r["ts"], si=si,
                                      detail=f"同じ画面のまま {run * 10} 秒", score=2))
            else:
                run = 0
                last_sh = sh

        # 5) 内容シグナル（セッション内で初出のものだけ）
        seen: set[str] = set()
        for r in s:
            for k in hit_signals(r["text"]):
                if k in seen:
                    continue
                seen.add(k)
                cands.append(dict(kind=f"signal:{k}", ts=r["ts"], si=si,
                                  detail=f"「{k}」の画面", score=4))

        # 6) 深夜に使い続けている
        h = datetime.fromtimestamp(s[-1]["ts"]).hour
        if 1 <= h <= 5 and dur >= 1800:
            cands.append(dict(kind="late_night", ts=s[-1]["ts"], si=si,
                              detail=f"深夜 {h} 時台に {dur / 60:.0f} 分連続", score=3))

    cands.sort(key=lambda c: c["ts"])
    return cands


def apply_cooldown(cands: list[dict], cooldown: float) -> tuple[list[dict], list[dict]]:
    """候補が近すぎる分だけ間引く。**1 日 / 1 時間の上限はここでは掛けない。**

    上限は「実際に話しかけた回数」に掛かるもので、LLM が SKIP を返した候補は
    消費しない。ここで先に上限を掛けると、その日の早い時間の候補だけで枠が埋まり、
    後半の良いタイミングが機械的に消える（実測: 1 日 6 件の上限だと 7 日とも
    深夜帯で埋まり切って夕方以降が 0 件になった）。
    """
    kept, dropped = [], []
    last_ts = -1e18
    for c in sorted(cands, key=lambda c: (c["ts"], -c["score"])):
        if c["ts"] - last_ts < cooldown:
            dropped.append(dict(c, why="直前の候補と近すぎる"))
            continue
        kept.append(c)
        last_ts = c["ts"]
    return kept, dropped


def context_for(rows: list[dict], ts: float, back: float = 900.0, limit: int = 22) -> list[dict]:
    """候補時刻から遡って back 秒ぶんを近似重複除去して返す。LLM に渡す材料。"""
    window = [r for r in rows if ts - back <= r["ts"] <= ts]
    kept: list[dict] = []
    last_sh = None
    for r in window:
        sh = shingles(r["text"])
        if last_sh is not None and jaccard(last_sh, sh) >= 0.88:
            continue
        last_sh = sh
        kept.append({"t": hhmm(r["ts"]), "pkg": r["pkg"],
                     "text": r["text"][:400]})
    return kept[-limit:]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DB)
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--signals", action="store_true", help="シグナル語のヒット数だけ出す")
    ap.add_argument("--candidates", action="store_true", help="候補＋文脈を JSON で吐く")
    ap.add_argument("--cooldown", type=float, default=1200.0, help="候補間の最小間隔（秒）")
    ap.add_argument("--summary", action="store_true", help="候補の一覧だけを人が読む形で出す")
    ap.add_argument("--detail", type=int, nargs="*", help="指定 id の候補だけ全文脈を出す")
    a = ap.parse_args()

    rows = load(a.db)
    print(f"# {len(rows):,} フレーム / {rows[0]['day']} 〜 {rows[-1]['day']}", file=sys.stderr)

    if a.signals:
        for k, words in SIGNALS.items():
            n = sum(1 for r in rows if any(w in r["text"] for w in words))
            print(f"{k:<12} {n:>6} フレーム")
        return 0

    cands = find_candidates(rows)
    kept, dropped = apply_cooldown(cands, a.cooldown)
    seen_ids: list[dict] = []

    if a.report:
        print(f"生候補 {len(cands)} → 通過 {len(kept)} / 却下 {len(dropped)}")
        by_kind: dict[str, int] = {}
        for c in kept:
            by_kind[c["kind"]] = by_kind.get(c["kind"], 0) + 1
        print("\n通過した候補の内訳")
        for k, v in sorted(by_kind.items(), key=lambda x: -x[1]):
            print(f"  {k:<20} {v}")
        print("\n日別")
        for day in sorted({datetime.fromtimestamp(c['ts']).strftime('%Y-%m-%d') for c in kept}):
            n = sum(1 for c in kept if datetime.fromtimestamp(c["ts"]).strftime("%Y-%m-%d") == day)
            print(f"  {day}  {n} 件")
        return 0

    if a.summary:
        # 232 件ぶんの全文脈は 1.8MB あって一度に読めない。まず「いつ・なぜ・直前に何が
        # 映っていたか」だけ一覧にして、話す価値がありそうなものを選んでから精読する
        cur_day = None
        for c in kept:
            day = datetime.fromtimestamp(c["ts"]).strftime("%Y-%m-%d")
            if day != cur_day:
                print(f"\n===== {day} =====")
                cur_day = day
            ctx = context_for(rows, c["ts"], back=300, limit=2)
            head = " ⏐ ".join(
                x["text"].replace("\n", " ")[:110] for x in ctx) or "(文字なし)"
            app = ctx[-1]["pkg"].split(".")[-1] if ctx else "?"
            print(f"[{len(seen_ids)}] {hhmm(c['ts'])} {c['kind']:<16} {app:<14} {c['detail']}")
            print(f"      {head}")
            seen_ids.append(c)
        return 0

    if a.detail is not None:
        want = set(a.detail)
        for i, c in enumerate(kept):
            if i not in want:
                continue
            print(f"\n########## [{i}] {c['day'] if 'day' in c else datetime.fromtimestamp(c['ts']).strftime('%Y-%m-%d')} "
                  f"{hhmm(c['ts'])} {c['kind']} / {c['detail']}")
            for x in context_for(rows, c["ts"], back=1200, limit=16):
                t = x["text"].replace("\n", " / ")[:260]
                print(f"  {x['t']} [{x['pkg'].split('.')[-1]}] {t}")
        return 0

    if a.candidates:
        out = []
        for c in kept:
            out.append({**c,
                        "day": datetime.fromtimestamp(c["ts"]).strftime("%Y-%m-%d"),
                        "time": hhmm(c["ts"]),
                        "context": context_for(rows, c["ts"])})
        json.dump(out, sys.stdout, ensure_ascii=False, indent=1)
        return 0

    ap.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
