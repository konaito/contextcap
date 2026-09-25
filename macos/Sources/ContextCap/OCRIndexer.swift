import CoreGraphics
import Foundation
import ImageIO
import SQLite3
import Vision

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// 撮影した画像を、劣化する前に OCR してテキストを確定させる。
///
/// なぜアプリ内でやるか: 以前は回収後に `scripts/ocr-extract.swift` を手で回していた。
/// その結果 Compactor が OCR を追い越し、2026-08-18 時点で corpus の 39%（20,007 枚）が
/// 圧縮後の画像で OCR され、未 OCR の 4,068 枚は gen0 が 1 枚も残っていなかった。
/// 同一画像で測ると 1920/q60 は行一致 75.1%、1280/q50 は 56.8%、800/q40 は 5.6% まで落ちる。
/// テキストが本体である以上、OCR は撮影と同じ流れに入れて先に確定させないと意味がない。
///
/// 速度は問題にならない。撮影は 5 秒に 1 枚（0.2 枚/秒）、OCR は単プロセスで 1.72 枚/秒。
/// **8.6 倍の余裕がある。** 「5 万枚に 2 時間」は後追いバッチで溜めた backlog の話であって、
/// 定常状態には行列が存在しない。
///
/// 設計上の要点:
///   - **撮影 tick を絶対に待たせない。** 撮影は「保存して enqueue」で終わり。
///     OCR は専用のシリアルキューが後ろで消化する。重いフレームは実測 1,043ms あり、
///     5,000ms の tick 予算に載せるとスキップが増える
///   - **並列にしない。** Vision は内部でシリアライズしていて、スレッドを 2/4/8/12/20 と
///     増やしても 1.55〜1.57 枚/秒で変わらない（統制測定・同一 24 枚）。
///     速くしたければプロセスを分けるしかないが、常駐アプリでその必要はない
///   - **qos は .userInitiated。** .utility だと Apple Silicon で efficiency コアに寄せられ、
///     実測 0.4 枚/秒 まで落ちる（同条件の .userInitiated は 2.1 枚/秒）
///   - DB は `scripts/ocr-extract.swift` と同じファイル・同じスキーマ・同じ主キー。
///     backlog 埋めを外から流しても衝突しないよう busy_timeout を入れる
final class OCRIndexer: @unchecked Sendable {
    /// 既定の出力先。撮影ルートの外に置く（撮影ルートに *.jpg 以外を育てない）
    static var defaultDatabaseURL: URL {
        if let override = UserDefaults.standard.string(forKey: "OCRDatabasePath") {
            return URL(fileURLWithPath: (override as NSString).expandingTildeInPath)
        }
        return FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("ContextCap-analysis/ocr.sqlite")
    }

    private let dbURL: URL
    private let queue = DispatchQueue(label: "app.imichat.contextcap.ocr", qos: .userInitiated)
    private let lock = NSLock()

    /// 撮影直後の分。**必ずこちらを先に処理する。**
    /// backlog と 1 本のキューにすると、追いつき処理が終わるまで新規撮影が待たされて
    /// 「撮影した瞬間に OCR」が成立しない。追いつきは常に暇な時だけ進める。
    private var fresh: [URL] = []
    /// 起動時に拾った未 OCR の分。fresh が空の時だけ 1 枚ずつ消化する
    private var backlog: [URL] = []
    private var draining = false
    private var indexedCount = 0
    private var failedCount = 0

    /// `queue` の上でだけ触る
    private var store: OCRStore?

    init(databaseURL: URL = OCRIndexer.defaultDatabaseURL) {
        self.dbURL = databaseURL
    }

    // MARK: - 外から見える状態（メニュー表示・Retention のガード用）

    var pendingCount: Int {
        lock.lock(); defer { lock.unlock() }
        return fresh.count + backlog.count + (draining ? 1 : 0)
    }

    var statusText: String {
        lock.lock(); defer { lock.unlock() }
        if !backlog.isEmpty {
            return "OCR: 追いつき中 残り \(backlog.count) 枚（新規 \(fresh.count)）"
        }
        if fresh.isEmpty && !draining { return "OCR: 追いついている（\(indexedCount) 枚）" }
        return "OCR: 残り \(fresh.count) 枚"
    }

    // MARK: - 投入

    /// 撮影直後に呼ぶ。ここでは積むだけで、OCR は別キューで走る。
    /// backlog がどれだけ溜まっていても、これが先に処理される。
    func enqueue(_ url: URL) {
        lock.lock()
        fresh.append(url)
        lock.unlock()
        drainIfNeeded()
    }

    /// 起動時に一度だけ、DB に無いファイルを拾い直す。
    /// アプリが落ちていた間の撮影や、enqueue を取りこぼした分の保険。
    /// 古い順に積む（Retention が古い順に消すので、消える前に読む）。
    func reconcile(root: URL) {
        queue.async { [self] in
            guard let store = openStoreIfNeeded() else { return }
            let known = store.existingKeys()

            var missing: [(Date, URL)] = []
            let keys: [URLResourceKey] = [.isRegularFileKey]
            guard let walker = FileManager.default.enumerator(
                at: root, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]
            ) else { return }
            for case let url as URL in walker {
                guard url.pathExtension.lowercased() == "jpg" else { continue }
                let day = url.deletingLastPathComponent().lastPathComponent
                let base = CaptureFile.baseName(of: url)
                guard !known.contains("\(day)/\(base)") else { continue }
                missing.append((CaptureFile.captureDate(of: url) ?? .distantPast, url))
            }
            guard !missing.isEmpty else { return }
            missing.sort { $0.0 < $1.0 }

            lock.lock()
            backlog = missing.map { $0.1 }
            lock.unlock()
            drainIfNeeded()
        }
    }

    /// Retention のガード用。OCR 済み（ok / empty どちらでも）のキー集合。
    /// **failed は含めない。**テキストが取れていないので、消したら永久に失われる。
    /// 5 分に 1 回しか呼ばれないので全件取得で足りる。
    func indexedKeys() -> Set<String> {
        queue.sync {
            guard let store = openStoreIfNeeded() else { return [] }
            return store.existingKeys(excludingFailed: true)
        }
    }

    // MARK: - 消化

    private func drainIfNeeded() {
        lock.lock()
        guard !draining, !(fresh.isEmpty && backlog.isEmpty) else { lock.unlock(); return }
        draining = true
        lock.unlock()

        queue.async { [self] in
            while true {
                lock.lock()
                // fresh を必ず先に見る。撮影中はここだけが回り、backlog は
                // 撮影の合間（1 枚 5 秒のうち OCR は 0.6 秒）に少しずつ進む
                let url: URL
                if !fresh.isEmpty {
                    url = fresh.removeFirst()
                } else if !backlog.isEmpty {
                    url = backlog.removeFirst()
                } else {
                    draining = false
                    lock.unlock()
                    return
                }
                lock.unlock()
                process(url)
            }
        }
    }

    /// `queue` の上でだけ呼ばれる
    private func process(_ url: URL) {
        guard let store = openStoreIfNeeded() else { return }
        // Retention に消された後で回ってくることがある。エラーにしない
        guard FileManager.default.fileExists(atPath: url.path) else { return }

        let day = url.deletingLastPathComponent().lastPathComponent
        let base = CaptureFile.baseName(of: url)
        let gen = CaptureFile.generation(of: url)
        let ts = (CaptureFile.captureDate(of: url) ?? Date()).timeIntervalSince1970
        let bytes = ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int) ?? 0

        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(
                  source, 0, [kCGImageSourceShouldCache: false] as CFDictionary
              ) else {
            store.insert(Row(
                day: day, base: base, gen: gen, ts: ts, path: url.path,
                width: 0, height: 0, bytes: bytes,
                text: "", lines: 0, conf: 0, ocrMS: 0,
                status: "failed", err: "デコード失敗"
            ))
            lock.lock(); failedCount += 1; lock.unlock()
            return
        }

        let t0 = DispatchTime.now().uptimeNanoseconds
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["ja-JP", "en-US"]
        // 実測（08-03 + 08-13 から 40 枚）: 補正 on 0.54 枚/秒 / off 1.20 枚/秒 で 2.2 倍。
        // 認識行数はむしろ off のほうが多い（5781 → 5817）。
        request.usesLanguageCorrection = false

        var text = ""
        var lineCount = 0
        var meanConf = 0.0
        var status = "ok"
        var err: String?

        do {
            let handler = VNImageRequestHandler(cgImage: image, options: [:])
            try handler.perform([request])
            // 読み順に近づける: 上から下、同じ行内は左から右
            let sorted = (request.results ?? []).sorted { a, b in
                let ay = a.boundingBox.midY, by = b.boundingBox.midY
                if abs(ay - by) > 0.01 { return ay > by }
                return a.boundingBox.minX < b.boundingBox.minX
            }
            var parts: [String] = []
            var confs: [Double] = []
            for observation in sorted {
                guard let top = observation.topCandidates(1).first else { continue }
                parts.append(top.string)
                confs.append(Double(top.confidence))
            }
            lineCount = parts.count
            text = parts.joined(separator: "\n")
            meanConf = confs.isEmpty ? 0 : confs.reduce(0, +) / Double(confs.count)
            // 黒画面など文字が 1 つも無い場合。失敗と区別する（黙って落とさない）
            if lineCount == 0 { status = "empty" }
        } catch {
            status = "failed"
            err = error.localizedDescription
        }

        let ms = Int((DispatchTime.now().uptimeNanoseconds - t0) / 1_000_000)
        store.insert(Row(
            day: day, base: base, gen: gen, ts: ts, path: url.path,
            width: image.width, height: image.height, bytes: bytes,
            text: text, lines: lineCount, conf: meanConf, ocrMS: ms,
            status: status, err: err
        ))

        lock.lock()
        if status == "failed" { failedCount += 1 } else { indexedCount += 1 }
        lock.unlock()
    }

    private func openStoreIfNeeded() -> OCRStore? {
        if let store { return store }
        store = OCRStore(url: dbURL)
        return store
    }
}

// MARK: - SQLite

/// `scripts/ocr-extract.swift` と同じファイルを共有する。スキーマ・主キー・
/// FTS の作り方をずらすと、backlog 埋めを外から流した時に壊れる。
private struct Row {
    let day: String, base: String
    let gen: Int, ts: Double, path: String
    let width: Int, height: Int, bytes: Int
    let text: String, lines: Int, conf: Double, ocrMS: Int
    let status: String
    var err: String?
}

private final class OCRStore {
    private var db: OpaquePointer?
    private var insertShot: OpaquePointer?
    private var insertFTS: OpaquePointer?

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
        exec("""
        CREATE TABLE IF NOT EXISTS shots (
          day TEXT NOT NULL, base TEXT NOT NULL, gen INTEGER NOT NULL,
          ts REAL NOT NULL, path TEXT NOT NULL,
          width INTEGER, height INTEGER, bytes INTEGER,
          dark_fraction REAL, mean_lum REAL,
          text TEXT, lines INTEGER, conf REAL, ocr_ms INTEGER,
          status TEXT NOT NULL, err TEXT,
          PRIMARY KEY (day, base)
        );
        """)
        exec("CREATE INDEX IF NOT EXISTS shots_ts ON shots(ts);")
        exec("CREATE INDEX IF NOT EXISTS shots_status ON shots(status);")
        // 日本語を substring で引くため trigram。unicode61 は CJK を分割しない
        exec("""
        CREATE VIRTUAL TABLE IF NOT EXISTS shots_fts
        USING fts5(text, day UNINDEXED, base UNINDEXED, tokenize='trigram');
        """)

        guard sqlite3_prepare_v2(db, """
        INSERT OR REPLACE INTO shots
          (day, base, gen, ts, path, width, height, bytes, dark_fraction, mean_lum,
           text, lines, conf, ocr_ms, status, err)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);
        """, -1, &insertShot, nil) == SQLITE_OK,
              sqlite3_prepare_v2(
                  db, "INSERT INTO shots_fts (text, day, base) VALUES (?,?,?);",
                  -1, &insertFTS, nil
              ) == SQLITE_OK else { return nil }
    }

    deinit {
        sqlite3_finalize(insertShot)
        sqlite3_finalize(insertFTS)
        sqlite3_close(db)
    }

    private func exec(_ sql: String) {
        sqlite3_exec(db, sql, nil, nil, nil)
    }

    func existingKeys(excludingFailed: Bool = false) -> Set<String> {
        var set = Set<String>()
        var stmt: OpaquePointer?
        let sql = excludingFailed
            ? "SELECT day, base FROM shots WHERE status != 'failed';"
            : "SELECT day, base FROM shots;"
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

    func insert(_ row: Row) {
        sqlite3_reset(insertShot)
        sqlite3_clear_bindings(insertShot)
        sqlite3_bind_text(insertShot, 1, row.day, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(insertShot, 2, row.base, -1, SQLITE_TRANSIENT)
        sqlite3_bind_int(insertShot, 3, Int32(row.gen))
        sqlite3_bind_double(insertShot, 4, row.ts)
        sqlite3_bind_text(insertShot, 5, row.path, -1, SQLITE_TRANSIENT)
        sqlite3_bind_int(insertShot, 6, Int32(row.width))
        sqlite3_bind_int(insertShot, 7, Int32(row.height))
        sqlite3_bind_int(insertShot, 8, Int32(row.bytes))
        sqlite3_bind_null(insertShot, 9)
        sqlite3_bind_null(insertShot, 10)
        sqlite3_bind_text(insertShot, 11, row.text, -1, SQLITE_TRANSIENT)
        sqlite3_bind_int(insertShot, 12, Int32(row.lines))
        sqlite3_bind_double(insertShot, 13, row.conf)
        sqlite3_bind_int(insertShot, 14, Int32(row.ocrMS))
        sqlite3_bind_text(insertShot, 15, row.status, -1, SQLITE_TRANSIENT)
        if let err = row.err {
            sqlite3_bind_text(insertShot, 16, err, -1, SQLITE_TRANSIENT)
        } else {
            sqlite3_bind_null(insertShot, 16)
        }
        sqlite3_step(insertShot)

        guard !row.text.isEmpty else { return }
        sqlite3_reset(insertFTS)
        sqlite3_bind_text(insertFTS, 1, row.text, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(insertFTS, 2, row.day, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(insertFTS, 3, row.base, -1, SQLITE_TRANSIENT)
        sqlite3_step(insertFTS)
    }
}
