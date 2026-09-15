#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Android 端末から回収した jsonl を SQLite(FTS5) に取り込む。

    adb -s <serial> pull .../ContextCap/<day>/ocr.jsonl ~/ContextCap-analysis/android-device/<day>/
    ./scripts/android-ingest.py            # → ~/ContextCap-analysis/android.sqlite

macOS 側の `ocr.sqlite` とは**別ファイルにする**。突き合わせは ATTACH で足りるし、
既に 69k 行入っている資産を取り込みミスで壊す理由がない。列名は `shots` に寄せてあるので
`sessionize.py` 系のクエリはほぼそのまま通る（`conf` と `dark_fraction` は Android 側に無い）。

同一性のキーは (day, base)。base は `HHmmss_SSS`、つまり保存形式の契約でいう時刻ステム。
撮影時刻はファイル属性ではなくここから解釈する（画像が消えても行は残るため、
`shots` 側と違って画像の実在は前提にしない）。

前面アプリは `apps.jsonl` の変化点ログを AppUsageIndex と同じ規則で解決する
（その時刻**以前**で最も新しい記録。最近傍ではない）。日付をまたいで引き継ぐので、
全日をまとめて 1 本の系列にしてから二分探索する。

壊れた行は黙って捨てない。件数と最初の数件を必ず報告する
（OcrLog の追記は途中で落ちると最終行が壊れ得る、と実装側に明記されている）。
"""

import argparse
import bisect
import json
import sqlite3
import sys
from datetime import datetime
from pathlib import Path

ANALYSIS = Path.home() / "ContextCap-analysis"
DEFAULT_SRC = ANALYSIS / "android-device"
DEFAULT_DB = ANALYSIS / "android.sqlite"

SCHEMA = """
CREATE TABLE IF NOT EXISTS shots (
  day TEXT NOT NULL, base TEXT NOT NULL,
  ts REAL NOT NULL,
  gen INTEGER, width INTEGER, height INTEGER, bytes INTEGER,
  text TEXT, lines INTEGER, ocr_ms INTEGER,
  status TEXT NOT NULL, err TEXT,
  pkg TEXT,
  PRIMARY KEY (day, base)
);
CREATE INDEX IF NOT EXISTS shots_ts ON shots(ts);
CREATE INDEX IF NOT EXISTS shots_status ON shots(status);
CREATE INDEX IF NOT EXISTS shots_pkg ON shots(pkg);

CREATE VIRTUAL TABLE IF NOT EXISTS shots_fts
USING fts5(text, day UNINDEXED, base UNINDEXED, tokenize='trigram');

CREATE TABLE IF NOT EXISTS apps (
  day TEXT NOT NULL, base TEXT NOT NULL, ts REAL NOT NULL, pkg TEXT NOT NULL,
  PRIMARY KEY (day, base)
);
CREATE INDEX IF NOT EXISTS apps_ts ON apps(ts);

-- 情報のない画面（DRM / AOD）の区間。画像は残っていないが観測できなかった事実は残る
CREATE TABLE IF NOT EXISTS blackouts (
  day TEXT NOT NULL, base TEXT NOT NULL, ts REAL NOT NULL, state TEXT NOT NULL,
  PRIMARY KEY (day, base)
);
CREATE INDEX IF NOT EXISTS blackouts_ts ON blackouts(ts);
"""


def stem_to_ts(day: str, base: str) -> float:
    """`2026-08-19` + `013024_995` → epoch 秒（端末のローカル時刻＝ここでは実行機と同じ JST 前提）。"""
    hh, mm, ss, ms = base[0:2], base[2:4], base[4:6], base[7:10]
    dt = datetime.strptime(f"{day} {hh}:{mm}:{ss}.{ms}", "%Y-%m-%d %H:%M:%S.%f")
    return dt.timestamp()


def read_jsonl(path: Path, bad: list[tuple[Path, int, str]]) -> list[dict]:
    out = []
    if not path.is_file():
        return out
    with path.open(encoding="utf-8", errors="replace") as fh:
        for i, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except Exception as e:  # noqa: BLE001 - 行を落とすが必ず数える
                bad.append((path, i, f"{type(e).__name__}: {line[:80]}"))
                continue
            if not isinstance(obj, dict) or "t" not in obj:
                bad.append((path, i, f"no t: {line[:80]}"))
                continue
            out.append(obj)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", type=Path, default=DEFAULT_SRC)
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    args = ap.parse_args()

    days = sorted(d.name for d in args.src.iterdir() if d.is_dir() and len(d.name) == 10)
    if not days:
        print(f"日付ディレクトリが無い: {args.src}", file=sys.stderr)
        return 1

    bad: list[tuple[Path, int, str]] = []

    # 1) apps を全日ぶん 1 本の系列にする（日をまたいで引き継ぐため）
    app_rows = []
    for day in days:
        for obj in read_jsonl(args.src / day / "apps.jsonl", bad):
            app_rows.append((day, obj["t"], stem_to_ts(day, obj["t"]), obj.get("pkg", "")))
    app_rows.sort(key=lambda r: r[2])
    app_ts = [r[2] for r in app_rows]

    def pkg_at(ts: float) -> str | None:
        """その時刻以前で最も新しい記録。無ければ None（apps.jsonl が始まる前の日）。"""
        i = bisect.bisect_right(app_ts, ts) - 1
        return app_rows[i][3] if i >= 0 else None

    # 2) shots と blackouts
    shot_rows, black_rows = [], []
    for day in days:
        for obj in read_jsonl(args.src / day / "ocr.jsonl", bad):
            ts = stem_to_ts(day, obj["t"])
            shot_rows.append((
                day, obj["t"], ts,
                obj.get("gen"), obj.get("w"), obj.get("h"), obj.get("bytes"),
                obj.get("text", ""), obj.get("lines"), obj.get("ms"),
                obj.get("status", "?"), obj.get("err"), pkg_at(ts),
            ))
        for obj in read_jsonl(args.src / day / "blackouts.jsonl", bad):
            black_rows.append((day, obj["t"], stem_to_ts(day, obj["t"]), obj.get("state", "?")))

    con = sqlite3.connect(args.db)
    con.executescript(SCHEMA)
    con.executemany(
        "INSERT OR REPLACE INTO shots (day,base,ts,gen,width,height,bytes,"
        "text,lines,ocr_ms,status,err,pkg) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", shot_rows)
    con.executemany("INSERT OR REPLACE INTO apps (day,base,ts,pkg) VALUES (?,?,?,?)", app_rows)
    con.executemany(
        "INSERT OR REPLACE INTO blackouts (day,base,ts,state) VALUES (?,?,?,?)", black_rows)

    # FTS は毎回作り直す。差分更新のトリガを持つより、この規模(数万行)なら全張り替えが安い
    con.execute("DELETE FROM shots_fts")
    con.execute(
        "INSERT INTO shots_fts (text, day, base) "
        "SELECT text, day, base FROM shots WHERE text IS NOT NULL AND text != ''")
    con.commit()

    n_shots = con.execute("SELECT count(*) FROM shots").fetchone()[0]
    n_pkg = con.execute("SELECT count(*) FROM shots WHERE pkg IS NULL").fetchone()[0]
    con.close()

    print(f"days       : {len(days)} ({days[0]} 〜 {days[-1]})")
    print(f"shots      : {len(shot_rows)} 行を投入 / DB 合計 {n_shots}")
    print(f"apps       : {len(app_rows)}  blackouts: {len(black_rows)}")
    print(f"pkg 未解決 : {n_pkg} 行（apps.jsonl が始まる前の撮影）")
    if bad:
        print(f"\n壊れた行  : {len(bad)} 件（捨てた）")
        for p, i, msg in bad[:5]:
            print(f"  {p.parent.name}/{p.name}:{i}  {msg}")
    else:
        print("壊れた行  : 0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
