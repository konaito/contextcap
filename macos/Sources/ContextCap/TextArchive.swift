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
/// - **すべて自前のシリアルキューで走らせる。** DB は 6.4GB あり、日の一覧（GROUP BY）は
///   キャッシュが冷えていると十数秒かかる（実測 17.8 秒、温まると 0.8 秒）
/// - 検索は FTS5 trigram（`shots_fts`）。trigram は 3 文字未満を引けないので、
///   2 文字以下は新しい順に `instr` で走査する（実測 2.6 秒）
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
                "SELECT day, count(*) FROM shots WHERE status = 'ok' GROUP BY day ORDER BY day DESC;"
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
            var rows: [(String, String, String)] = []
            query(
                "SELECT day, base, text FROM shots WHERE day = ? AND status = 'ok' ORDER BY base;",
                bind: [day]
            ) { stmt in
                rows.append((Self.string(stmt, 0), Self.string(stmt, 1), Self.string(stmt, 2)))
            }
            let out = Self.collapse(rows)
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
            var rows: [(String, String, String)] = []
            let step: (OpaquePointer) -> Void = { stmt in
                rows.append((Self.string(stmt, 0), Self.string(stmt, 1), Self.string(stmt, 2)))
            }
            if text.count >= 3 {
                // 句として渡す。記号や AND/OR が FTS5 の演算子として解釈されないように
                let phrase = "\"" + text.replacingOccurrences(of: "\"", with: "\"\"") + "\""
                query("""
                SELECT s.day, s.base, s.text FROM shots_fts f
                JOIN shots s ON s.day = f.day AND s.base = f.base
                WHERE shots_fts MATCH ? AND s.status = 'ok'
                ORDER BY s.ts DESC LIMIT \(Self.searchLimit);
                """, bind: [phrase], step: step)
            } else {
                query("""
                SELECT day, base, text FROM shots
                WHERE status = 'ok' AND instr(text, ?) > 0
                ORDER BY ts DESC LIMIT \(Self.searchLimit);
                """, bind: [text], step: step)
            }
            // FTS には同じ画像が 2 行入っていることがある（shots は REPLACE、FTS は追記のため）
            var seen = Set<String>()
            rows = rows.filter { seen.insert("\($0.0)/\($0.1)").inserted }
            let out = Self.collapse(rows)
            guard isCurrent(token) else { return }
            Task { @MainActor in completion(out) }
        }
    }

    // MARK: - 内部

    /// 並び順のまま、同じ日で同じ文字が続く行を 1 つの区間にまとめる
    static func collapse(_ rows: [(day: String, base: String, text: String)]) -> [Passage] {
        var out: [Passage] = []
        for row in rows where !row.text.isEmpty {
            if var last = out.last, last.day == row.day, last.text == row.text {
                last.lastBase = row.base
                last.count += 1
                out[out.count - 1] = last
            } else {
                out.append(Passage(day: row.day, firstBase: row.base, lastBase: row.base, count: 1, text: row.text))
            }
        }
        return out
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
