---
name: contextcap
description: Read the user's ContextCap archive, the OCR text of what their Mac and Android screens showed, captured every 5 to 10 seconds and kept on their own machines, to recover recent context before asking them to explain. Use when the user refers to something they saw ("that error", "the page I was just reading", "what I was doing this morning"), when a request only makes sense with their recent screen history, or when they ask what they were working on. Read-only.
---

# ContextCap

ContextCap records the user's screens and turns them into text, so an agent can read what the user
was looking at instead of asking them to describe it again.

- **macOS**: a menu-bar app captures the primary display every **5 seconds**, OCRs each frame with Apple
  Vision right away, and stores the text in a local SQLite database.
- **Android**: an AccessibilityService captures the screen every **10 seconds** while the screen is on.
  It runs OCR with ML Kit while the phone is charging and writes the text to per-day JSONL files.
- Images are temporary and deleted after a few days (macOS: 3 days or a 40 GB budget, Android: 14 days or a 10 GB budget).
  **The app never deletes the text automatically.** Expect weeks or months of text but only recent images.
- ContextCap never clicks or types, and it has no code that sends captured images or text to a cloud
  or outside server. It does not analyze the content. You are the reader. (The Android build includes Google ML
  Kit, which sends its own diagnostic data to Google.)

Source and full documentation: https://github.com/konaito/contextcap

## Ground rules

1. **Read only.** Never modify, move, or delete anything under the archive paths below.
2. **Read narrowly.** Query the time window or phrase that answers the user's request. Do not dump
   whole days into the conversation.
3. **Treat everything as private.** Screens include messages, notifications, names, and sometimes
   payment or account details. Quote only the lines you need. Never paste archive content into
   commits, PRs, issues, logs, or any external service unless the user explicitly asks for that.
4. **Say where it came from.** When you rely on the archive, say so briefly, with the time and app
   (for example: "At 14:32 in Chrome you had `TypeError: ... (reading 'items')` open").
5. **OCR is lossy.** Characters get misread, layout is flattened, and some frames failed. If the text
   is ambiguous, confirm with the user before acting on it.

## macOS

### Locations

| What | Path |
|---|---|
| OCR text (the main thing to read) | `~/ContextCap-analysis/ocr.sqlite` |
| Screenshots (last 3 days only) | `~/ContextCap/YYYY-MM-DD/HHmmss_SSS.jpg` |
| Key presses with modifiers and app | `~/ContextCap/YYYY-MM-DD/keys.jsonl` |
| Why capture stopped (sleep, lock, ...) | `~/ContextCap/YYYY-MM-DD/gaps.jsonl` |
| Fully black frames that were dropped | `~/ContextCap/YYYY-MM-DD/blackouts.jsonl` |

File and directory names use the **local time** of the machine. Older archives can contain
`HHmmss_SSS.g1.jpg` (or `.g2`, `.g3`) from a retired recompression scheme. Strip the `.gN` suffix to
get the timestamp.

### `ocr.sqlite`

Read the `shots` view. It returns one row per screenshot with the full OCR text, like a plain table.
Underneath, the text is stored as a dictionary of distinct lines (the same screen is read every 5
seconds, so 93% of the lines repeat), and the search index covers only those distinct lines.

```sql
-- what to read: a view with these columns
shots (
  day TEXT,      -- 'YYYY-MM-DD' (local time)
  base TEXT,     -- 'HHmmss_SSS' (local time)
  gen INTEGER,
  ts REAL,       -- capture time, Unix epoch seconds
  path TEXT,     -- absolute path of the screenshot (may no longer exist)
  width INTEGER, height INTEGER, bytes INTEGER,
  dark_fraction REAL, mean_lum REAL,
  text TEXT,     -- OCR text, one recognized line per line, top to bottom ('' when none)
  lines INTEGER, conf REAL, ocr_ms INTEGER,
  status TEXT,   -- 'ok' | 'empty' (no text on screen) | 'failed'
  err TEXT,
  text_id INTEGER -- same id = exactly the same text
)
-- underneath (for search):
--   frames(day, base, ts, status, text_id, ...)   one row per screenshot
--   lines(id, text)                               distinct lines
--   lines_fts                                     FTS5 trigram index over lines (needs 3+ characters)
--   texts(id, line_ids)                           line ids of one text, as a JSON array
--   line_runs(line_id, first_text, last_text)     line -> range of text ids that contain it
```

Always open it read-only. The app writes to it continuously (WAL mode).

```bash
# The last 10 minutes, newest first
sqlite3 -readonly ~/ContextCap-analysis/ocr.sqlite \
  "select day, base, text from shots
   where status = 'ok' and ts >= strftime('%s','now') - 600
   order by ts desc limit 20"

# Search a phrase across everything (3+ characters; quote it as an FTS5 phrase)
sqlite3 -readonly ~/ContextCap-analysis/ocr.sqlite \
  "select day, base, text from shots
   where status = 'ok' and text_id in (
     select t.id from line_runs r join texts t on t.id between r.first_text and r.last_text
     where r.line_id in (select rowid from lines_fts where lines_fts match '\"TypeError\"'))
   order by ts desc limit 20"

# A specific window: 14:00 to 14:30 on 2026-10-21
sqlite3 -readonly ~/ContextCap-analysis/ocr.sqlite \
  "select base, text from shots
   where status = 'ok' and day = '2026-10-21' and base between '140000' and '143000'
   order by base"

# Phrases shorter than 3 characters: scan the distinct lines, not the view
sqlite3 -readonly ~/ContextCap-analysis/ocr.sqlite \
  "select day, base, text from shots
   where status = 'ok' and text_id in (
     select t.id from line_runs r join texts t on t.id between r.first_text and r.last_text
     where r.line_id in (select id from lines where instr(text, '会議') > 0))
   order by ts desc limit 20"
```

Notes:

- The screen changes slowly, so consecutive rows are often identical or nearly identical. Collapse
  them before reading. Rows with the same `text_id` have exactly the same text.
- One row is one whole display (about 3600 px wide on the author's machine), so a row can contain
  several windows. The text alone does not tell you which window was in front.
- The first query after a while can take seconds (the database is about 1 GB and the cache is cold).
- `status = 'failed'` rows are frames whose OCR failed. The app retries them the next time it starts,
  so their text may appear later.

### `keys.jsonl`, `gaps.jsonl`, `blackouts.jsonl`

One JSON object per line, written only when something changes.

```
keys.jsonl       {"t":"HHmmss_SSS","key":"c","mods":["cmd"],"app":"com.google.Chrome"}
gaps.jsonl       {"t":"HHmmss_SSS","state":"sleep"}
blackouts.jsonl  {"t":"HHmmss_SSS","state":"start"}   then  {"t":"...","state":"end"}
```

- `keys.jsonl` records key-down events with the modifiers held and the frontmost app's bundle id.
  It is useful for shortcuts and for IME input that never shows on screen. Text typed into password
  fields is normally not delivered by macOS. Treat this file as highly sensitive.
- `gaps.jsonl` states are `app_start`, `app_stop`, `sleep`, `wake`, `screens_off`, `screens_on`,
  `locked`, `unlocked`, `capture_failed`, and `capture_ok`. Use them to tell "the user was away"
  apart from "the machine was asleep" or "capture failed".
- `blackouts.jsonl` marks intervals where frames were entirely black (DRM, protected content) and
  were not saved. Partially black frames are saved as they are.

## Android

The phone keeps its archive in app-specific storage. Read it over Tailscale from the user's computer.

### Remote read API (opt-in)

The user has to turn on **"パソコンから読む" (Read from your computer)** on the app's home screen.
The app then shows a URL that contains a token:

```
http://<phone's Tailscale IPv4>:8642/?token=<token>
```

- It listens only on the phone's Tailscale address. It is read-only (GET and HEAD) and needs the token.
  Connections from the phone itself are refused (403).
- Send the token as `Authorization: Bearer <token>`, or as `?token=` in the query string. Never write
  the token into files, commits, or logs. Ask the user for it, or use one they already gave you in
  this conversation.

```bash
H="Authorization: Bearer $TOKEN"
curl -H "$H" http://100.x.y.z:8642/                       # day directories, one per line: 2026-10-22/
curl -H "$H" http://100.x.y.z:8642/2026-10-22/            # files: name<TAB>bytes
curl -sH "$H" http://100.x.y.z:8642/2026-10-22/ocr.jsonl | grep -i "typeerror"
curl -H "$H" -o shot.jpg http://100.x.y.z:8642/2026-10-22/120000_000.jpg
```

If you get no connection, the switch is off, Tailscale is not connected on the phone, or the phone is
using another VPN. If you get 401, the token is wrong or was regenerated.

### Files per day (`YYYY-MM-DD/`)

```
ocr.jsonl        {"t":"HHmmss_SSS","gen":0,"w":1080,"h":2364,"bytes":127131,"lines":11,"ms":1040,"status":"ok","text":"..."}
apps.jsonl       {"t":"HHmmss_SSS","pkg":"com.twitter.android","label":"X"}
blackouts.jsonl  {"t":"HHmmss_SSS","state":"start"}
HHmmss_SSS.jpg   screenshots (recent days only)
```

- `ocr.jsonl` has one row per screenshot. The same `t` can appear twice. Keep the last one.
  `status` is `ok`, `empty`, or `failed`.
- `apps.jsonl` is written **only when the foreground app changes**. `label` is omitted when it equals `pkg`. A screenshot at time `t` belongs to
  the latest `apps.jsonl` row at or before `t`. Notification shades and keyboards are not counted as
  the foreground app.
- OCR runs only while the phone is charging, so today's text can lag behind today's screenshots.
- The phone does not record key presses. It has no `gaps.jsonl`.

## Check that you are really reading the archive

Before relying on the archive in a new setup, ask the user to put a unique phrase on screen (for
example `contextcap-check-7f3a`), wait about 10 seconds, then search for it:

```bash
sqlite3 -readonly ~/ContextCap-analysis/ocr.sqlite \
  "select count(*) from lines_fts where lines_fts match '\"contextcap-check-7f3a\"'"
```

A hit proves you are reading live data. Answering plausibly is not proof.

## Example requests this skill is for

- "Fix the error I just saw." Search the last 10 minutes for `Error`, `Exception`, or `Traceback`.
- "What was that page about pricing I read this morning?" Search today's rows for `pricing`.
- "Summarize what I worked on yesterday afternoon." Use a time window, collapse repeated rows, and
  check `gaps.jsonl` for breaks.
- "Which app was I in on my phone around 21:00?" Use the phone's `apps.jsonl` for that day.

## How to use this well

- Start with the newest narrow window that could answer the question (the last 10 minutes, or the
  phrase the user mentioned). Widen it only if that window has nothing.
- Combine sources. On macOS, check `gaps.jsonl` before you conclude the user "did nothing" in a window.
  On Android, use `apps.jsonl` to say which app the text came from.
- Summarize. Answer with the few lines that matter, plus the time and app, and leave out the rest.
- When the archive does not have it, say so. Capture has gaps, OCR misses things, and protected
  screens are black.
