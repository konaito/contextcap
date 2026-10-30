import Foundation
import SQLite3

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// 読み取った文字を `ocr.sqlite` から読む。書き込みは `OCRIndexer` だけがする。
///
/// 画像は `Retention` が 3 日で消すが、`ocr.sqlite` の行は消さない。
/// 2026-10-21 時点で画像は 3 日分なのに、文字は 76 日分（2026-08-03〜）残っていた。
/// メニューの「期間」を画像だけで数えると、残っている文字が見えなくなる。
///
/// - **読み取り専用で別に開く。** `OCRIndexer` の接続はそのキューに閉じている。
///   WAL なので、書き込み中でも読める
/// - **すべて自前のシリアルキューで走らせる。** 日の一覧（GROUP BY）は、行の辞書に移す前の
///   6.4GB の DB では冷えていると 17.8 秒かかった。移した後の 1GB では 0.14 秒（2026-10-23 実測）
/// - 文字は 1 行ずつの辞書で持っている（`OCRStore`）。検索は辞書に張った FTS5 trigram（`lines_fts`）で引く。
///   trigram は 3 文字未満を引けないので、2 文字以下は辞書を `instr` で走査する
final class TextArchive: @unchecked Sendable {
    struct Day: Sendable {
        /// `YYYY-MM-DD`
        let name: String
        let shots: Int
    }

    /// 同じ文字が続いた区間。5 秒おきに撮るので、同じ画面を見ている間は同じ文字が並ぶ
    struct Passage: Sendable {
        let day: String
        let firstBase: String
        var lastBase: String
        var count: Int
        let text: String
    }

    /// 検索で返す件数の上限（区間にまとめる前の行数）
    static let searchLimit = 300

    private let dbURL: URL
    private let queue = DispatchQueue(label: "app.imichat.contextcap.text", qos: .userInitiated)
    private let lock = NSLock()

    /// `queue` の上でだけ触る
    private var db: OpaquePointer?
    /// 走っている検索を打ち切るための世代番号。`lock` で守る
    private var generation = 0
    /// 検索の走査中だけ true。`lock` で守る。日の一覧を途中で止めると期間の表示が狂うので、
    /// 打ち切るのは検索だけにする
    private var searchRunning = false
    private var cachedSpanText = "文字: 集計中"
    private var lastSpanRefresh: Date?

    init(databaseURL: URL = OCRIndexer.defaultDatabaseURL) {
        self.dbURL = databaseURL
    }

    deinit {
        sqlite3_close(db)
    }

    // MARK: - メニュー用

    /// 「文字: 76 日分（2026-08-03〜）」。メニューは待たせられないので、前回の集計を返す
    var spanText: String {
        lock.lock(); defer { lock.unlock() }
        return cachedSpanText
    }

    /// 日の一覧を取り直して `spanText` を更新する。次にメニューを開いた時に反映される。
    /// メニューを開くたびに呼ばれるので 60 秒は取り直さない（同じキューの検索を待たせないため）
    func refreshSpan() {
        lock.lock()
        let now = Date()
        let stale = lastSpanRefresh.map { now.timeIntervalSince($0) > 60 } ?? true
        if stale { lastSpanRefresh = now }
        lock.unlock()
        guard stale else { return }
        days { _ in }
    }

    // MARK: - 読み出し

    func days(completion: @escaping @MainActor ([Day]) -> Void) {
        queue.async { [self] in
            var out: [Day] = []
            query(
                "SELECT day, count(*) FROM frames WHERE status = 'ok' GROUP BY day ORDER BY day DESC;"
            ) { stmt in
                out.append(Day(name: Self.string(stmt, 0), shots: Int(sqlite3_column_int(stmt, 1))))
            }
            let span = out.isEmpty
                ? "文字: —"
                : "文字: \(out.count) 日分（\(out.last!.name)〜）"
            lock.lock(); cachedSpanText = span; lock.unlock()
            Task { @MainActor in completion(out) }
        }
    }

    func passages(day: String, completion: @escaping @MainActor ([Passage]) -> Void) {
        let token = nextGeneration()
        queue.async { [self] in
            var rows: [Row] = []
            query("""
            SELECT day, base, text_id FROM frames
            WHERE day = ? AND status = 'ok' AND text_id IS NOT NULL ORDER BY base;
            """, bind: [day]) { stmt in
                rows.append(Row(day: Self.string(stmt, 0), base: Self.string(stmt, 1),
                                textID: sqlite3_column_int64(stmt, 2)))
            }
            let out = passages(Self.collapse(rows))
            guard isCurrent(token) else { return }
            Task { @MainActor in completion(out) }
        }
    }

    /// 全期間から探す。新しい順。前の検索が走っていれば打ち切る
    func search(_ text: String, completion: @escaping @MainActor ([Passage]) -> Void) {
        let token = nextGeneration()
        // 走査中の検索を止める。止められた側は途中までで返り、世代違いで捨てられる
        lock.lock()
        if searchRunning, let db { sqlite3_interrupt(db) }
        lock.unlock()
        queue.async { [self] in
            guard isCurrent(token) else { return }
            lock.lock(); searchRunning = true; lock.unlock()
            defer { lock.lock(); searchRunning = false; lock.unlock() }
            var rows: [Row] = []
            // 句として渡す。記号や AND/OR が FTS5 の演算子として解釈されないように
            let (ids, value) = text.count >= 3
                ? (OCRStore.matchingTextIDs, "\"" + text.replacingOccurrences(of: "\"", with: "\"\"") + "\"")
                : (OCRStore.matchingTextIDsShort, text)
            query("""
            SELECT day, base, text_id FROM frames
            WHERE status = 'ok' AND text_id IN (\(ids))
            ORDER BY ts DESC LIMIT \(Self.searchLimit);
            """, bind: [value]) { stmt in
                rows.append(Row(day: Self.string(stmt, 0), base: Self.string(stmt, 1),
                                textID: sqlite3_column_int64(stmt, 2)))
            }
            let out = passages(Self.collapse(rows))
            guard isCurrent(token) else { return }
            Task { @MainActor in completion(out) }
        }
    }

    // MARK: - 内部

    private struct Row {
        let day: String, base: String, textID: Int64
    }

    /// 並び順のまま、同じ日で同じ本文が続く行を 1 つの区間にまとめる。
    /// 本文は `OCRStore` が 1 つにまとめているので、同じ文字なら同じ `textID` になる
    private static func collapse(_ rows: [Row]) -> [(first: Row, last: Row, count: Int)] {
        var out: [(first: Row, last: Row, count: Int)] = []
        for row in rows {
            if let prev = out.last, prev.first.day == row.day, prev.first.textID == row.textID {
                out[out.count - 1] = (prev.first, row, prev.count + 1)
            } else {
                out.append((row, row, 1))
            }
        }
        return out
    }

    /// 区間ごとに 1 回だけ本文を組み立てる。`queue` の上でだけ呼ぶ
    private func passages(_ runs: [(first: Row, last: Row, count: Int)]) -> [Passage] {
        var texts: [Int64: String] = [:]
        return runs.map { run in
            let text = texts[run.first.textID] ?? {
                var value = ""
                query(OCRStore.textByID, bind: [String(run.first.textID)]) { value = Self.string($0, 0) }
                texts[run.first.textID] = value
                return value
            }()
            return Passage(day: run.first.day, firstBase: run.first.base, lastBase: run.last.base,
                           count: run.count, text: text)
        }
    }

    private func nextGeneration() -> Int {
        lock.lock(); defer { lock.unlock() }
        generation += 1
        return generation
    }

    private func isCurrent(_ token: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return token == generation
    }

    /// `queue` の上でだけ呼ぶ
    private func openIfNeeded() -> OpaquePointer? {
        if let db { return db }
        var handle: OpaquePointer?
        guard sqlite3_open_v2(dbURL.path, &handle, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else {
            sqlite3_close(handle)
            return nil
        }
        sqlite3_busy_timeout(handle, 5000)
        // search() が別スレッドから interrupt のために読むので lock の下で入れる
        lock.lock(); db = handle; lock.unlock()
        return handle
    }

    /// `queue` の上でだけ呼ぶ
    private func query(_ sql: String, bind: [String] = [], step: (OpaquePointer) -> Void) {
        guard let db = openIfNeeded() else { return }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { return }
        defer { sqlite3_finalize(stmt) }
        for (i, value) in bind.enumerated() {
            sqlite3_bind_text(stmt, Int32(i + 1), value, -1, SQLITE_TRANSIENT)
        }
        while sqlite3_step(stmt) == SQLITE_ROW {
            step(stmt)
        }
    }

    private static func string(_ stmt: OpaquePointer, _ column: Int32) -> String {
        guard let c = sqlite3_column_text(stmt, column) else { return "" }
        return String(cString: c)
    }

    /// "093015_123" → "09:30:15"
    static func clockText(_ base: String) -> String {
        let chars = Array(base)
        guard chars.count >= 6 else { return base }
        return "\(String(chars[0..<2])):\(String(chars[2..<4])):\(String(chars[4..<6]))"
    }
}
