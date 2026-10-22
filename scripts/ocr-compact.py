#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""本文を持つ旧形式の ocr.sqlite を、行の辞書で持つ形式（schema 2）に作り直す。

    ./scripts/ocr-compact.py                  # ocr.sqlite.new を作って全件で検算する（読むだけ）
    ./scripts/ocr-compact.py --swap           # 検算が通ったら差し替える（旧は .bak-<日時> に退避）

形式の説明は macos/Sources/ContextCap/OCRStore.swift。DDL もそこから読む（1 か所だけで持つ）。
**先に新しいアプリを入れてから流す。** 新しいアプリは旧形式の DB に書かないので、撮影を続けたまま
DB が動かない。作成と検算はアプリが動いたまま行い、差し替えの瞬間だけアプリを止めて起動し直す
（止まるのは数秒）。OCR していない画像は、起動し直したアプリが backlog として読む
（Retention は未 OCR の画像を消さない）。古いアプリが書き込んでいたら、差し替えずに止まる。

**検算で 1 件でも食い違ったら差し替えない。** 比べるのは全行の本文と列、検索の結果、索引の整合性。
旧ファイルは消さない。新しい形式で数日使って問題がなければ手で消す。
"""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_DB = Path.home() / "ContextCap-analysis" / "ocr.sqlite"
STORE_SWIFT = REPO / "macos" / "Sources" / "ContextCap" / "OCRStore.swift"
SCHEMA_VERSION = 2
COLUMNS = ("day, base, gen, ts, path, width, height, bytes, dark_fraction, mean_lum, "
           "text, lines, conf, ocr_ms, status, err")
# 旧形式の text には NULL が混ざりうる。新しいビューは空文字を返す
OLD_COLUMNS = COLUMNS.replace("text,", "coalesce(text, ''),")
FRAME_COLUMNS = COLUMNS.replace("text,", "text_id,")
# 検算に使う語。3 文字以上は索引、2 文字以下は辞書の走査の経路を通る
WORDS = ["TypeError", "ContextCap", "スリープ", "konaito/contextcap", "エラー", "請求書",
         "Claude Code", "会議", "OK"]


def key(text: str) -> int:
    """OCRStore.key と同じ。SHA-256 の先頭 8 バイトを big-endian の符号付き整数にする"""
    return int.from_bytes(hashlib.sha256(text.encode()).digest()[:8], "big", signed=True)


def schema() -> str:
    src = STORE_SWIFT.read_text()
    m = re.search(r'static let schema = """\n(.*?)\n\s*"""', src, re.S)
    if not m:
        sys.exit(f"DDL が見つからない: {STORE_SWIFT}")
    return "\n".join(line[4:] if line.startswith("    ") else line for line in m.group(1).split("\n"))


def app_running() -> bool:
    return subprocess.run(["pgrep", "-x", "ContextCap"], capture_output=True).returncode == 0


def fingerprint(db: Path) -> tuple:
    """移行中に元の DB が書き換わっていないかを見るための大きさと更新時刻"""
    out = []
    for suffix in ("", "-wal"):
        p = Path(str(db) + suffix)
        out.append((p.stat().st_size, p.stat().st_mtime_ns) if p.exists() else None)
    return tuple(out)


def build(src: sqlite3.Connection, dst: sqlite3.Connection) -> None:
    dst.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;")
    dst.executescript(schema())
    lines: dict[str, int] = {}
    texts: dict[bytes, int] = {}
    runs: dict[int, list[int]] = {}  # 行 → 今延ばしている範囲 [first, last]
    n = 0
    t0 = time.time()
    dst.execute("BEGIN")
    # 撮影順に本文の連番を振る。続けて撮った画面ほど同じ行が出るので、範囲が長く続く
    for row in src.execute(f"SELECT {COLUMNS} FROM shots ORDER BY ts, day, base"):
        text = row[10] or ""
        text_id = None
        if text:
            digest = hashlib.sha256(text.encode()).digest()
            text_id = texts.get(digest)
            if text_id is None:
                ids = []
                for line in text.split("\n"):
                    line_id = lines.get(line)
                    if line_id is None:
                        line_id = len(lines) + 1
                        lines[line] = line_id
                        dst.execute("INSERT INTO lines (id, key, text) VALUES (?,?,?)", (line_id, key(line), line))
                    ids.append(line_id)
                text_id = len(texts) + 1
                texts[digest] = text_id
                dst.execute("INSERT INTO texts (id, key, line_ids) VALUES (?,?,?)",
                            (text_id, key(text), json.dumps(ids, separators=(",", ":"))))
                for line_id in set(ids):
                    run = runs.get(line_id)
                    if run and run[1] == text_id - 1:
                        run[1] = text_id
                    else:
                        if run:
                            dst.execute("INSERT INTO line_runs VALUES (?,?,?)", (line_id, run[0], run[1]))
                        runs[line_id] = [text_id, text_id]
        dst.execute(f"INSERT INTO frames ({FRAME_COLUMNS}) "
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row[:10] + (text_id,) + row[11:])
        n += 1
        if n % 50000 == 0:
            print(f"  {n:,} 枚 / 行 {len(lines):,} 種類 / 本文 {len(texts):,} 種類 ({time.time() - t0:.0f}s)", flush=True)
    dst.executemany("INSERT INTO line_runs VALUES (?,?,?)", ((k, v[0], v[1]) for k, v in runs.items()))
    dst.execute("COMMIT")
    dst.execute("INSERT INTO lines_fts(lines_fts) VALUES ('optimize')")
    dst.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
    print(f"作成: {n:,} 枚 / 行 {len(lines):,} 種類 / 本文 {len(texts):,} 種類 ({time.time() - t0:.0f}s)")


def verify(src: sqlite3.Connection, dst: sqlite3.Connection) -> list[str]:
    problems = []
    t0 = time.time()
    old = src.execute(f"SELECT {OLD_COLUMNS} FROM shots ORDER BY day, base")
    new = dst.execute(f"SELECT {COLUMNS} FROM shots ORDER BY day, base")
    n = 0
    while True:
        a, b = old.fetchone(), new.fetchone()
        if a is None and b is None:
            break
        n += 1
        if a != b:
            problems.append(f"行が違う: {a[:2] if a else None} / {b[:2] if b else None}")
            if len(problems) >= 5:
                break
    print(f"検算 本文と列: {n:,} 行 ({time.time() - t0:.0f}s)")

    for word in WORDS:
        if len(word) >= 3:
            phrase = '"' + word.replace('"', '""') + '"'
            want = {r for r in src.execute(
                "SELECT s.day, s.base FROM shots_fts f JOIN shots s ON s.day = f.day AND s.base = f.base "
                "WHERE shots_fts MATCH ? AND s.status = 'ok'", (phrase,))}
            got = {r for r in dst.execute(
                "SELECT day, base FROM frames WHERE status = 'ok' AND text_id IN ("
                "SELECT t.id FROM line_runs r JOIN texts t ON t.id BETWEEN r.first_text AND r.last_text "
                "WHERE r.line_id IN (SELECT rowid FROM lines_fts WHERE lines_fts MATCH ?))", (phrase,))}
        else:
            want = {r for r in src.execute(
                "SELECT day, base FROM shots WHERE status = 'ok' AND instr(text, ?) > 0", (word,))}
            got = {r for r in dst.execute(
                "SELECT day, base FROM frames WHERE status = 'ok' AND text_id IN ("
                "SELECT t.id FROM line_runs r JOIN texts t ON t.id BETWEEN r.first_text AND r.last_text "
                "WHERE r.line_id IN (SELECT id FROM lines WHERE instr(text, ?) > 0))", (word,))}
        print(f"検算 検索「{word}」: 旧 {len(want):,} 件 / 新 {len(got):,} 件 {'一致' if want == got else '不一致'}")
        if want != got:
            problems.append(f"検索「{word}」が違う: 旧 {len(want)} 件 / 新 {len(got)} 件")

    try:
        dst.execute("INSERT INTO lines_fts(lines_fts, rank) VALUES ('integrity-check', 1)")
        print("検算 索引: integrity-check ok")
    except sqlite3.DatabaseError as e:
        problems.append(f"索引の integrity-check: {e}")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--swap", action="store_true", help="検算が通ったら差し替える")
    args = ap.parse_args()
    db: Path = args.db
    new_path = Path(str(db) + ".new")

    src = sqlite3.connect(f"file:{db}?mode=ro", uri=True, isolation_level=None)
    version = src.execute("PRAGMA user_version").fetchone()[0]
    if version != 0 or not src.execute("SELECT 1 FROM sqlite_master WHERE name = 'shots' AND type = 'table'").fetchone():
        sys.exit(f"旧形式ではない（user_version={version}）。移行済みなら何もしなくていい")
    before = fingerprint(db)
    # 作成と検算を同じ時点の中身で行う。アプリが動いたままでも（--swap なし）検算が狂わない
    src.execute("BEGIN")
    src.execute("SELECT count(*) FROM shots").fetchone()

    for suffix in ("", "-wal", "-shm", "-journal"):
        Path(str(new_path) + suffix).unlink(missing_ok=True)
    dst = sqlite3.connect(new_path, isolation_level=None)
    build(src, dst)
    dst.execute("VACUUM")
    dst.execute("PRAGMA journal_mode=WAL")

    problems = verify(src, dst)
    dst.close()
    src.close()
    size_old = sum(os.path.getsize(str(db) + s) for s in ("", "-wal") if os.path.exists(str(db) + s))
    size_new = os.path.getsize(new_path)
    print(f"大きさ: {size_old / 1e9:.2f}GB → {size_new / 1e9:.2f}GB ({size_new / size_old:.1%})")
    if problems:
        print("\n検算で食い違いが出た。差し替えない:")
        for p in problems:
            print("  " + p)
        return 1
    if not args.swap:
        print(f"\n検算は全部通った。差し替えるなら --swap を付けて流し直す（{new_path} は作り直す）")
        return 0

    if fingerprint(db) != before:
        print("作成中に元の DB が書き換わった（古いアプリが動いている？）。差し替えない")
        return 1
    was_running = app_running()
    if was_running:
        subprocess.run(["pkill", "-x", "ContextCap"])
        for _ in range(100):
            if not app_running():
                break
            time.sleep(0.1)
        else:
            print("ContextCap が止まらない。差し替えない")
            return 1
    if fingerprint(db) != before:
        print("止める直前に元の DB が書き換わった。差し替えない")
        return 1
    # 元の WAL を本体に書き戻してから動かす。WAL だけ取り残すと、退避した側が読めなくなる
    rw = sqlite3.connect(db)
    busy, frames, done = rw.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()
    rw.close()
    if busy or frames != done:
        print(f"WAL を書き戻せなかった（busy={busy} {done}/{frames}）。差し替えない")
        return 1
    stamp = time.strftime("%Y%m%d-%H%M%S")
    backup = Path(f"{db}.bak-{stamp}")
    os.rename(db, backup)
    for suffix in ("-wal", "-shm"):
        Path(str(db) + suffix).unlink(missing_ok=True)
    os.rename(new_path, db)
    for suffix in ("-wal", "-shm"):
        Path(str(new_path) + suffix).unlink(missing_ok=True)
    print(f"差し替えた: {db}（旧は {backup}）")
    if was_running:
        subprocess.run(["open", "/Applications/ContextCap.app"])
        print("ContextCap を起動し直した")
    return 0


if __name__ == "__main__":
    sys.exit(main())
