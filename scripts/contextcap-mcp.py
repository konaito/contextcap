#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp>=2.3,<3"]
# ///
"""macOS の ocr.sqlite を読むだけの MCP サーバー（stdio）。エージェントが SQL を書かずに引ける。

    claude mcp add contextcap -- /path/to/contextcap/scripts/contextcap-mcp.py

ツールは 3 つ。どれも書き込まない（DB は mode=ro で開く）。

- search: 語で引き、10 分ごとの窓にまとめて、当たった行だけを返す（画面全文は返さない）
- timeline: 期間を指定して、10 分ごとに重複しない行を初出順で返す
- status: 記録の範囲と最新の撮影時刻。生きたデータを読んでいるかの確認に使う

Android の文字は対象外（端末の RemoteServer から curl で読む。SKILL.md 参照）。

検索は SKILL.md と同じ経路（lines_fts の trigram 句 → line_runs → texts → frames）。
"..." で囲んだ句（または空白のない 1 語）が 6 文字以上で 1 件も当たらなければ、
前半と後半に割って引き直し、partial として返す。
2026-10-23 の評価（1 日分・25 窓）で、1 文字だけ誤読された句の hit@5 が 0% → 100% になった。
割るのは 1 回だけ（2 回以上割る形は測っていない）。詳細は docs/results/2026-10-23-search-eval.md。

DB の場所は CONTEXTCAP_OCR_DB で変えられる（既定 ~/ContextCap-analysis/ocr.sqlite）。
"""

import datetime as dt
import json
import os
import re
import sqlite3
from contextlib import closing
from pathlib import Path

DB = Path(os.environ.get("CONTEXTCAP_OCR_DB", Path.home() / "ContextCap-analysis" / "ocr.sqlite"))
WINDOW_SEC = 600
SPLIT_MIN_CHARS = 6
MAX_LINES_PER_WINDOW = 20
MAX_LINE_CHARS = 200


def connect() -> sqlite3.Connection:
    # アプリが WAL で書き続けているので、読むだけで開く。ファイルを作らない
    con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True, timeout=10)
    con.execute("PRAGMA query_only = 1")
    return con


def parse_time(s: str | None, now: dt.datetime | None = None) -> float | None:
    """'2026-10-21' / '2026-10-21 14:30' / '-10m' '-2h' '-1d'（今から遡る）を epoch 秒にする。"""
    if not s:
        return None
    s = s.strip()
    m = re.fullmatch(r"-(\d+)([mhd])", s)
    if m:
        unit = {"m": 60, "h": 3600, "d": 86400}[m.group(2)]
        return (now or dt.datetime.now()).timestamp() - int(m.group(1)) * unit
    return dt.datetime.fromisoformat(s).timestamp()


def fmt(ts: float) -> str:
    return dt.datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M")


def term_hits(con, term: str, since: float, until: float) -> dict[int, dict[int, float]]:
    """語を含む行が出たフレームを 窓 → {行 id: 最新 ts} で返す。3 文字以上は FTS、未満は instr。"""
    if len(term) >= 3:
        sub = "SELECT rowid FROM lines_fts WHERE lines_fts MATCH ?"
        arg = '"' + term.replace('"', '""') + '"'
    else:
        sub = "SELECT id FROM lines WHERE instr(text, ?) > 0"
        arg = term
    # 当たった行 → line_runs → frames_text の範囲、の順に固定する。CROSS JOIN と単項 + を外すと、
    # SQLite は frames_status で全フレームを舐める計画を選ぶ。"TypeError" 1 語の全期間検索が
    # 14.6 秒 → 0.14 秒になった（2026-10-23・48.9 万フレームで実測）
    rows = con.execute(
        f"""SELECT CAST(f.ts / {WINDOW_SEC} AS INTEGER) AS w, r.line_id, max(f.ts)
            FROM line_runs r CROSS JOIN frames f ON f.text_id BETWEEN r.first_text AND r.last_text
            WHERE r.line_id IN ({sub}) AND +f.status = 'ok' AND +f.ts BETWEEN ? AND ?
            GROUP BY w, r.line_id""",
        (arg, since, until),
    ).fetchall()
    out: dict[int, dict[int, float]] = {}
    for w, lid, ts in rows:
        out.setdefault(w, {})[lid] = ts
    return out


def search(con, query: str, since: str | None = None, until: str | None = None, limit: int = 10) -> dict:
    q = query.strip()
    # "..." で囲んだら空白ごと 1 つの句として引く（英語のエラー文など）
    terms = [q[1:-1]] if len(q) >= 2 and q[0] == q[-1] == '"' else q.split()
    if not terms or not terms[0]:
        return {"windows": [], "note": "query が空"}
    t0 = parse_time(since) or 0.0
    t1 = parse_time(until) or 1e12
    per_term: list[dict[int, dict[int, float]]] = []
    partial: list[str] = []
    for term in terms:
        hits = term_hits(con, term, t0, t1)
        # 割るのは句 1 つのときだけ。キーワードを並べた問いで割ると、
        # 関係ない窓が混ざって言い換え検索の hit@5 が 44% → 40% に下がった（同じ評価）
        if not hits and len(terms) == 1 and len(term) >= SPLIT_MIN_CHARS:
            # OCR の 1 文字誤読を想定して、前半と後半のどちらかに当たれば拾う
            h = len(term) // 2
            for half in (term[:h], term[h:]):
                for w, lines in term_hits(con, half, t0, t1).items():
                    hits.setdefault(w, {}).update(lines)
            if hits:
                partial.append(term)
        per_term.append(hits)

    # 当たった語の数が多い窓から。同数なら新しい窓から（SKILL.md の ts 降順と同じ向き）
    score: dict[int, int] = {}
    latest: dict[int, float] = {}
    lines_of: dict[int, dict[int, float]] = {}
    for hits in per_term:
        for w, lines in hits.items():
            score[w] = score.get(w, 0) + 1
            latest[w] = max(latest.get(w, 0.0), max(lines.values()))
            lines_of.setdefault(w, {}).update(lines)
    ranked = sorted(score, key=lambda w: (-score[w], -latest[w]))[:limit]

    windows = []
    for w in ranked:
        ids = sorted(lines_of[w], key=lambda lid: lines_of[w][lid])[:MAX_LINES_PER_WINDOW]
        qs = ",".join("?" * len(ids))
        text = dict(con.execute(f"SELECT id, text FROM lines WHERE id IN ({qs})", ids).fetchall())
        windows.append({
            "start": fmt(w * WINDOW_SEC),
            "end": fmt((w + 1) * WINDOW_SEC)[-5:],
            "matched_terms": score[w],
            "lines": [text[lid][:MAX_LINE_CHARS] for lid in ids],
        })
    return {
        "terms": terms,
        "partial": partial,  # 割って引き直した語。誤読の可能性があるので本文で確かめる
        "windows": windows,
    }


def timeline(con, since: str, until: str | None = None, max_chars: int = 20000) -> dict:
    t0 = parse_time(since)
    t1 = parse_time(until) or dt.datetime.now().timestamp()
    if t0 is None:
        return {"windows": [], "note": "since が必要"}
    line_ids: dict[int, list[int]] = {}
    seen: dict[int, set[int]] = {}
    frames: dict[int, int] = {}
    for ts, raw in con.execute(
        """SELECT f.ts, t.line_ids FROM frames f JOIN texts t ON t.id = f.text_id
           WHERE f.status = 'ok' AND f.ts BETWEEN ? AND ? ORDER BY f.ts""",
        (t0, t1),
    ):
        w = int(ts // WINDOW_SEC)
        frames[w] = frames.get(w, 0) + 1
        order, s = line_ids.setdefault(w, []), seen.setdefault(w, set())
        for lid in json.loads(raw):
            if lid not in s:
                s.add(lid)
                order.append(lid)

    # 10 分窓 1 つで 3 万字ほどになるので、上限は窓の数で均等に割る。
    # 先頭から詰めると、最初の窓だけで使い切って残りの時間が見えなくなる
    budget = max_chars // max(len(line_ids), 1)
    cache: dict[int, str] = {}
    windows, truncated = [], False
    for w in sorted(line_ids):
        body, used = [], 0
        for i in range(0, len(line_ids[w]), 200):
            part = [lid for lid in line_ids[w][i:i + 200] if lid not in cache]
            if part:
                qs = ",".join("?" * len(part))
                cache.update(con.execute(f"SELECT id, text FROM lines WHERE id IN ({qs})", part).fetchall())
            for lid in line_ids[w][i:i + 200]:
                if used + len(cache[lid]) + 1 > budget:
                    truncated = True
                    break
                body.append(cache[lid])
                used += len(cache[lid]) + 1
            else:
                continue
            break
        windows.append({"start": fmt(w * WINDOW_SEC), "frames": frames[w],
                        "lines": body, "omitted_lines": len(line_ids[w]) - len(body)})
    return {"windows": windows, "truncated": truncated}


def status(con) -> dict:
    first, last, n = con.execute("SELECT min(day), max(day), count(*) FROM frames").fetchone()
    latest = con.execute("SELECT max(ts) FROM frames WHERE status = 'ok'").fetchone()[0]
    hour = dict(con.execute(
        "SELECT status, count(*) FROM frames WHERE ts >= ? GROUP BY status",
        (dt.datetime.now().timestamp() - 3600,),
    ).fetchall())
    return {
        "db": str(DB),
        "days": [first, last],
        "frames": n,
        "latest_ok": fmt(latest) if latest else None,
        "last_hour": hour,
    }


def main() -> None:
    from mcp.server.mcpserver import MCPServer

    server = MCPServer(
        "contextcap",
        instructions=(
            "ContextCap が記録した macOS の画面の OCR 文字を読む。読み取り専用。"
            "結果は個人情報を含む前提で、必要な行だけを引用する。OCR は誤読するので、"
            "partial の語は本文で確かめる。"
        ),
    )

    @server.tool()
    def contextcap_search(query: str, since: str | None = None, until: str | None = None, limit: int = 10) -> dict:
        """画面の OCR 文字を語で引く。空白区切りの語ごとに部分一致（3 文字以上は索引、未満は走査）で探し、
        10 分ごとの窓にまとめて、当たった語の多い窓・新しい窓から返す。窓ごとに当たった行だけを最大 20 行返す。
        since / until は '2026-10-21' '2026-10-21 14:30' または '-30m' '-2h' '-1d'（今から遡る）。
        画面にあった文をそのまま探すときは、query 全体を "..." で囲むと空白ごと 1 つの句として引く。
        句 1 つ（または空白のない 1 語）で 6 文字以上なのに 1 件も当たらなければ、前半と後半に割って引き直し、
        partial に入れる（OCR の 1 文字誤読を拾うため）。"""
        with closing(connect()) as con:
            return search(con, query, since, until, limit)

    @server.tool()
    def contextcap_timeline(since: str, until: str | None = None, max_chars: int = 20000) -> dict:
        """期間内の画面の文字を 10 分ごとに返す。窓の中で同じ行は 1 回だけ、初出順。
        since / until の書き方は contextcap_search と同じ。max_chars を超えたら打ち切り、truncated を立てる。"""
        with closing(connect()) as con:
            return timeline(con, since, until, max_chars)

    @server.tool()
    def contextcap_status() -> dict:
        """記録の範囲（日付）、フレーム数、最新の OCR 済みフレームの時刻、直近 1 時間の status 別件数を返す。"""
        with closing(connect()) as con:
            return status(con)

    server.run("stdio")


if __name__ == "__main__":
    main()
