import CryptoKit
import Foundation
import SQLite3

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// `scripts/ocr-extract.swift` もこのファイルを一緒にコンパイルして同じ DB に書く。
struct OCRRow {
    let day: String, base: String
    let gen: Int, ts: Double, path: String
    let width: Int, height: Int, bytes: Int
    let text: String, lines: Int, conf: Double, ocrMS: Int
    let status: String
    var err: String?
    var darkFraction: Double? = nil
    var meanLum: Double? = nil
}

/// `ocr.sqlite` への書き込み。
///
/// **文字は 1 行ずつの辞書で持つ（schema 2）。** 5 秒おきに同じ画面を読むので、
/// 2026-10-23 の 486,622 枚・本文 1.54GB のうち、種類の違う行は 2,725,936 種類・118MB しかなかった。
///
/// - `lines` — 行の辞書。検索索引 `lines_fts`（trigram）はここにだけ張る
/// - `texts` — 1 枚分の本文。行番号の並びを JSON 配列で持つ。同じ本文は 1 つにまとめる
/// - `line_runs` — 行 → その行を含む本文の範囲。新しい本文には連番を振るので、
///   続けて撮った画面に同じ行が出る間は 1 行で済む（3,283 万組が 892 万行になった）
/// - `frames` — 1 枚ごとのメタデータと `text_id`
/// - `shots` — 旧形式と同じ列を返すビュー。`select text from shots` はそのまま通る
///
/// 検索は旧形式と同じ結果を返す。改行を含まない語は 1 行の中でしか当たらないので、
/// 行ごとに索引を張っても取りこぼしは出ない（10 語と全 486,622 枚の復元で一致を確かめた）。
/// 移行は `scripts/ocr-compact.py`。**アプリは旧形式の DB には書かない**（書かずに待つ。
/// 撮影は続き、Retention は未 OCR の画像を消さないので、移行後に backlog として読む）。
final class OCRStore {
    static let schemaVersion: Int32 = 2

    /// `scripts/ocr-compact.py` はこの文字列を読んで同じ DDL を流す。**1 か所だけで持つ。**
    static let schema = """
    CREATE TABLE IF NOT EXISTS frames (
      day TEXT NOT NULL, base TEXT NOT NULL, gen INTEGER NOT NULL,
      ts REAL NOT NULL, path TEXT NOT NULL,
      width INTEGER, height INTEGER, bytes INTEGER,
      dark_fraction REAL, mean_lum REAL,
      text_id INTEGER, lines INTEGER, conf REAL, ocr_ms INTEGER,
      status TEXT NOT NULL, err TEXT,
      PRIMARY KEY (day, base)
    );
    CREATE INDEX IF NOT EXISTS frames_ts ON frames(ts);
    CREATE INDEX IF NOT EXISTS frames_status ON frames(status);
    CREATE INDEX IF NOT EXISTS frames_text ON frames(text_id);
    CREATE TABLE IF NOT EXISTS lines (id INTEGER PRIMARY KEY, key INTEGER NOT NULL, text TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS lines_key ON lines(key);
    CREATE VIRTUAL TABLE IF NOT EXISTS lines_fts
    USING fts5(text, content='lines', content_rowid='id', tokenize='trigram');
    CREATE TRIGGER IF NOT EXISTS lines_fts_ai AFTER INSERT ON lines BEGIN
      INSERT INTO lines_fts(rowid, text) VALUES (new.id, new.text);
    END;
    CREATE TABLE IF NOT EXISTS texts (id INTEGER PRIMARY KEY, key INTEGER NOT NULL, line_ids TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS texts_key ON texts(key);
    CREATE TABLE IF NOT EXISTS line_runs (
      line_id INTEGER NOT NULL, first_text INTEGER NOT NULL, last_text INTEGER NOT NULL,
      PRIMARY KEY (line_id, first_text)
    ) WITHOUT ROWID;
    CREATE VIEW IF NOT EXISTS shots AS
    SELECT f.rowid AS rowid, f.day, f.base, f.gen, f.ts, f.path, f.width, f.height, f.bytes,
           f.dark_fraction, f.mean_lum,
           coalesce((SELECT group_concat(l.text, char(10) ORDER BY j.key)
                     FROM texts t, json_each(t.line_ids) j JOIN lines l ON l.id = j.value
                     WHERE t.id = f.text_id), '') AS text,
           f.lines, f.conf, f.ocr_ms, f.status, f.err, f.text_id
    FROM frames f;
    """

    /// 語を含む本文の id。`?` は FTS5 の句（3 文字以上）
    static let matchingTextIDs = """
    SELECT t.id FROM line_runs r JOIN texts t ON t.id BETWEEN r.first_text AND r.last_text
    WHERE r.line_id IN (SELECT rowid FROM lines_fts WHERE lines_fts MATCH ?)
    """

    /// trigram は 3 文字未満を引けないので、辞書を `instr` で走査する
    static let matchingTextIDsShort = """
    SELECT t.id FROM line_runs r JOIN texts t ON t.id BETWEEN r.first_text AND r.last_text
    WHERE r.line_id IN (SELECT id FROM lines WHERE instr(text, ?) > 0)
    """

    /// 本文 1 つを組み立て直す。`?` は texts.id
    static let textByID = """
    SELECT group_concat(l.text, char(10) ORDER BY j.key)
    FROM texts t, json_each(t.line_ids) j JOIN lines l ON l.id = j.value WHERE t.id = ?
    """

    /// 辞書の引き当てに使う。SHA-256 の先頭 8 バイトを big-endian の符号付き整数にする
    /// （`ocr-compact.py` と同じ）。64bit なので衝突はありうる。引くときは必ず本文も比べる
    static func key(_ text: String) -> Int64 {
        let digest = SHA256.hash(data: Data(text.utf8))
        return digest.prefix(8).reduce(Int64(0)) { ($0 << 8) | Int64($1) }
    }

    private var db: OpaquePointer?
    private var statements: [String: OpaquePointer] = [:]

    init?(url: URL) {
        try? FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        guard sqlite3_open_v2(
            url.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil
        ) == SQLITE_OK else { return nil }

        exec("PRAGMA journal_mode=WAL;")
        exec("PRAGMA synchronous=NORMAL;")
        // scripts/ocr-extract.swift を backlog 埋めで同時に回すことがある。WAL でも
        // writer は同時に 1 つなので、これが無いと INSERT が SQLITE_BUSY で黙って落ちる
        exec("PRAGMA busy_timeout=60000;")

        let version = scalarInt("PRAGMA user_version;") ?? 0
        if version == 0 {
            guard scalarInt("SELECT count(*) FROM sqlite_master WHERE name = 'shots';") == 0 else {
                NSLog("OCR: ocr.sqlite が旧形式。scripts/ocr-compact.py で移行するまで書き込まない")
                return nil
            }
            guard exec(Self.schema), exec("PRAGMA user_version = \(Self.schemaVersion);") else { return nil }
        } else if version != Self.schemaVersion {
            NSLog("OCR: ocr.sqlite の形式が \(version)。このアプリは \(Self.schemaVersion) しか書けない")
            return nil
        }
    }

    deinit {
        for stmt in statements.values { sqlite3_finalize(stmt) }
        sqlite3_close(db)
    }

    @discardableResult
    private func exec(_ sql: String) -> Bool {
        var err: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, sql, nil, nil, &err) == SQLITE_OK else {
            if let err {
                NSLog("OCR: sqlite: \(String(cString: err))")
                sqlite3_free(err)
            }
            return false
        }
        return true
    }

    private func scalarInt(_ sql: String) -> Int? {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return Int(sqlite3_column_int64(stmt, 0))
    }

    /// 使い回す prepared statement。reset とバインドの解除をしてから返す
    private func statement(_ sql: String) -> OpaquePointer? {
        if let stmt = statements[sql] {
            sqlite3_reset(stmt)
            sqlite3_clear_bindings(stmt)
            return stmt
        }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            NSLog("OCR: sqlite: \(String(cString: sqlite3_errmsg(db)))")
            return nil
        }
        statements[sql] = stmt
        return stmt
    }

    func existingKeys(excludingFailed: Bool = false) -> Set<String> {
        var set = Set<String>()
        var stmt: OpaquePointer?
        let sql = excludingFailed
            ? "SELECT day, base FROM frames WHERE status != 'failed';"
            : "SELECT day, base FROM frames;"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK
        else { return set }
        defer { sqlite3_finalize(stmt) }
        while sqlite3_step(stmt) == SQLITE_ROW {
            let day = String(cString: sqlite3_column_text(stmt, 0))
            let base = String(cString: sqlite3_column_text(stmt, 1))
            set.insert("\(day)/\(base)")
        }
        return set
    }

    /// 1 枚分を書く。辞書・本文・範囲・frames を 1 トランザクションで書くので、
    /// `ocr-extract` と同時に走っても本文の連番（`line_runs` の前提）はずれない
    @discardableResult
    func insert(_ row: OCRRow) -> Bool {
        guard exec("BEGIN IMMEDIATE;") else { return false }
        var textID: Int64?
        if !row.text.isEmpty {
            textID = internText(row.text)
            guard textID != nil else { exec("ROLLBACK;"); return false }
        }
        guard upsertFrame(row, textID: textID), exec("COMMIT;") else {
            exec("ROLLBACK;")
            return false
        }
        return true
    }

    /// 本文の id を返す。初めての本文なら辞書・本文・範囲に足す
    private func internText(_ text: String) -> Int64? {
        var ids: [Int64] = []
        for line in text.components(separatedBy: "\n") {
            guard let id = internLine(line) else { return nil }
            ids.append(id)
        }
        let lineIDs = "[" + ids.map(String.init).joined(separator: ",") + "]"
        let key = Self.key(text)

        guard let find = statement("SELECT id FROM texts WHERE key = ? AND line_ids = ?;") else { return nil }
        sqlite3_bind_int64(find, 1, key)
        sqlite3_bind_text(find, 2, lineIDs, -1, SQLITE_TRANSIENT)
        if let id = firstInt64(find) { return id }

        guard let add = statement("INSERT INTO texts (key, line_ids) VALUES (?, ?);") else { return nil }
        sqlite3_bind_int64(add, 1, key)
        sqlite3_bind_text(add, 2, lineIDs, -1, SQLITE_TRANSIENT)
        guard sqlite3_step(add) == SQLITE_DONE else { return nil }
        let textID = sqlite3_last_insert_rowid(db)

        // 直前の本文まで続いている範囲だけを延ばす。見るのはその行の最新の範囲 1 つだけ
        // （last_text だけで引くと、ほぼ毎回写るメニューバーの行で全範囲を走査する）
        guard let extend = statement("""
              UPDATE line_runs SET last_text = ?1
              WHERE line_id = ?2 AND last_text = ?1 - 1
                AND first_text = (SELECT max(first_text) FROM line_runs WHERE line_id = ?2);
              """),
              let start = statement("INSERT INTO line_runs (line_id, first_text, last_text) VALUES (?, ?, ?);")
        else { return nil }
        for lineID in Set(ids) {
            sqlite3_reset(extend)
            sqlite3_bind_int64(extend, 1, textID)
            sqlite3_bind_int64(extend, 2, lineID)
            guard sqlite3_step(extend) == SQLITE_DONE else { return nil }
            if sqlite3_changes(db) == 0 {
                sqlite3_reset(start)
                sqlite3_bind_int64(start, 1, lineID)
                sqlite3_bind_int64(start, 2, textID)
                sqlite3_bind_int64(start, 3, textID)
                guard sqlite3_step(start) == SQLITE_DONE else { return nil }
            }
        }
        return textID
    }

    private func internLine(_ line: String) -> Int64? {
        let key = Self.key(line)
        guard let find = statement("SELECT id FROM lines WHERE key = ? AND text = ?;") else { return nil }
        sqlite3_bind_int64(find, 1, key)
        sqlite3_bind_text(find, 2, line, -1, SQLITE_TRANSIENT)
        if let id = firstInt64(find) { return id }

        guard let add = statement("INSERT INTO lines (key, text) VALUES (?, ?);") else { return nil }
        sqlite3_bind_int64(add, 1, key)
        sqlite3_bind_text(add, 2, line, -1, SQLITE_TRANSIENT)
        guard sqlite3_step(add) == SQLITE_DONE else { return nil }
        return sqlite3_last_insert_rowid(db)
    }

    /// 1 行目の 1 列目。読み終えたら reset して、トランザクションの中に読みかけを残さない
    private func firstInt64(_ stmt: OpaquePointer) -> Int64? {
        defer { sqlite3_reset(stmt) }
        return sqlite3_step(stmt) == SQLITE_ROW ? sqlite3_column_int64(stmt, 0) : nil
    }

    /// REPLACE を使わない。衝突した行を消して rowid を振り直すので、`shots` ビューの rowid が変わる
    private func upsertFrame(_ row: OCRRow, textID: Int64?) -> Bool {
        guard let st = statement("""
        INSERT INTO frames
          (day, base, gen, ts, path, width, height, bytes, dark_fraction, mean_lum,
           text_id, lines, conf, ocr_ms, status, err)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(day, base) DO UPDATE SET
          gen = excluded.gen, ts = excluded.ts, path = excluded.path,
          width = excluded.width, height = excluded.height, bytes = excluded.bytes,
          dark_fraction = excluded.dark_fraction, mean_lum = excluded.mean_lum,
          text_id = excluded.text_id, lines = excluded.lines, conf = excluded.conf,
          ocr_ms = excluded.ocr_ms, status = excluded.status, err = excluded.err;
        """) else { return false }
        sqlite3_bind_text(st, 1, row.day, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(st, 2, row.base, -1, SQLITE_TRANSIENT)
        sqlite3_bind_int(st, 3, Int32(row.gen))
        sqlite3_bind_double(st, 4, row.ts)
        sqlite3_bind_text(st, 5, row.path, -1, SQLITE_TRANSIENT)
        sqlite3_bind_int(st, 6, Int32(row.width))
        sqlite3_bind_int(st, 7, Int32(row.height))
        sqlite3_bind_int64(st, 8, Int64(row.bytes))
        if let v = row.darkFraction { sqlite3_bind_double(st, 9, v) } else { sqlite3_bind_null(st, 9) }
        if let v = row.meanLum { sqlite3_bind_double(st, 10, v) } else { sqlite3_bind_null(st, 10) }
        if let textID { sqlite3_bind_int64(st, 11, textID) } else { sqlite3_bind_null(st, 11) }
        sqlite3_bind_int(st, 12, Int32(row.lines))
        sqlite3_bind_double(st, 13, row.conf)
        sqlite3_bind_int(st, 14, Int32(row.ocrMS))
        sqlite3_bind_text(st, 15, row.status, -1, SQLITE_TRANSIENT)
        if let err = row.err {
            sqlite3_bind_text(st, 16, err, -1, SQLITE_TRANSIENT)
        } else {
            sqlite3_bind_null(st, 16)
        }
        return sqlite3_step(st) == SQLITE_DONE
    }
}
