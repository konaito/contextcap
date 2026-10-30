// OCRStore（行の辞書・本文・範囲・frames）を確かめる。
// macOS 側にテストターゲットが無いので、対象ソースを直接コンパイルして叩く。
import Foundation
import SQLite3

@main
struct OCRStoreTest {
    static var failures = 0

    static func check(_ ok: Bool, _ name: String) {
        print(ok ? "ok   \(name)" : "FAIL \(name)")
        if !ok { failures += 1 }
    }

    static func row(_ base: String, _ text: String, status: String = "ok") -> OCRRow {
        OCRRow(day: "2026-10-22", base: base, gen: 0, ts: Double(base.prefix(6)) ?? 0,
               path: "/x/\(base).jpg", width: 1, height: 1, bytes: 1, text: text,
               lines: text.isEmpty ? 0 : text.components(separatedBy: "\n").count,
               conf: 1, ocrMS: 1, status: status, err: nil)
    }

    /// 別の接続で読む（アプリでは TextArchive が別に開いて読む）
    static func query(_ url: URL, _ sql: String, _ bind: String? = nil) -> [String] {
        var db: OpaquePointer?
        sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READONLY, nil)
        defer { sqlite3_close(db) }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            return ["error: \(String(cString: sqlite3_errmsg(db)))"]
        }
        defer { sqlite3_finalize(stmt) }
        if let bind {
            sqlite3_bind_text(stmt, 1, bind, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        }
        var out: [String] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            out.append(sqlite3_column_text(stmt, 0).map { String(cString: $0) } ?? "NULL")
        }
        return out
    }

    /// TextArchive.search と同じ引き方。3 文字以上は索引、2 文字以下は辞書の走査
    static func search(_ url: URL, _ word: String) -> [String] {
        let (ids, value) = word.count >= 3
            ? (OCRStore.matchingTextIDs, "\"\(word)\"")
            : (OCRStore.matchingTextIDsShort, word)
        return query(url, """
        SELECT base FROM frames WHERE status = 'ok' AND text_id IN (\(ids)) ORDER BY base;
        """, value)
    }

    static func count(_ url: URL, _ table: String) -> Int {
        Int(query(url, "SELECT count(*) FROM \(table);").first ?? "") ?? -1
    }

    static func main() {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("ocr-store-test-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        // scripts/ocr-compact.py の key() と同じ値になること（ずれると移行後に辞書が二重になる）
        check(OCRStore.key("") == -2039914840885289964, "空文字の key が Python と一致する")
        check(OCRStore.key("ContextCap") == 6057092582728782130, "ASCII の key が Python と一致する")
        check(OCRStore.key("日本語の行") == 3654332203032423421, "日本語の key が Python と一致する")

        let url = dir.appendingPathComponent("ocr.sqlite")
        do {
            guard let store = OCRStore(url: url) else { check(false, "新しい DB を作れる"); return }
            check(query(url, "PRAGMA user_version;") == ["2"], "新しい DB は schema 2")

            store.insert(row("100000_000", "メニューバー\n最初の画面\n共通の行"))
            store.insert(row("100005_000", "メニューバー\n最初の画面\n共通の行"))
            check(count(url, "frames") == 2 && count(url, "texts") == 1, "同じ本文は 1 つにまとめる")
            check(count(url, "lines") == 3, "行の辞書は 3 種類")

            store.insert(row("100010_000", "メニューバー\n次の画面\n共通の行"))
            check(query(url, """
                SELECT first_text || '-' || last_text FROM line_runs
                WHERE line_id = (SELECT id FROM lines WHERE text = '共通の行');
                """) == ["1-2"], "続く本文に同じ行が出る間は範囲を延ばす")

            store.insert(row("100015_000", "メニューバー\n別の画面"))
            store.insert(row("100020_000", "メニューバー\n共通の行"))
            check(query(url, """
                SELECT first_text || '-' || last_text FROM line_runs
                WHERE line_id = (SELECT id FROM lines WHERE text = '共通の行') ORDER BY first_text;
                """) == ["1-2", "4-4"], "途中で途切れたら範囲を分ける")
            check(search(url, "共通の") == ["100000_000", "100005_000", "100010_000", "100020_000"],
                  "範囲の中の本文を全部引ける")
            check(search(url, "別の画") == ["100015_000"], "範囲の外の本文は引かない")
            check(search(url, "メニューバー") == ["100000_000", "100005_000", "100010_000",
                                                   "100015_000", "100020_000"], "全部に出る行")
            check(search(url, "次の") == ["100010_000"], "2 文字は辞書の走査で引く")

            // 撮影直後の OCR が失敗して、起動時の reconcile で読み直す経路（#13）
            store.insert(row("100025_000", "", status: "failed"))
            let rowid = query(url, "SELECT rowid FROM frames WHERE base = '100025_000';")
            store.insert(row("100025_000", "スリープ明けの画面"))
            check(search(url, "スリープ明け") == ["100025_000"], "failed → ok の読み直しを引ける")
            check(query(url, "SELECT rowid FROM frames WHERE base = '100025_000';") == rowid,
                  "読み直しで rowid が変わらない")

            store.insert(row("100010_000", "書き換えた後の文字"))
            check(!search(url, "次の画面").contains("100010_000"), "書き換え前の本文では引かない")
            check(search(url, "書き換えた") == ["100010_000"], "書き換え後の本文で引ける")

            let tricky = "前に空行\n\n  字下げ\t\"引用\" \\ 記号\n末尾"
            store.insert(row("100030_000", tricky))
            check(query(url, "SELECT text FROM shots WHERE base = '100030_000';") == [tricky],
                  "空行・空白・記号を含む本文をビューで元どおりに組み立てる")
            check(query(url, "SELECT text FROM shots WHERE base = '100025_000';") == ["スリープ明けの画面"],
                  "ビューの text は書いた本文と一致する")
            store.insert(row("100035_000", "", status: "empty"))
            check(query(url, "SELECT quote(text) FROM shots WHERE base = '100035_000';") == ["''"],
                  "文字の無い画面の text は空文字（旧形式と同じ）")
            check(query(url, "INSERT INTO lines_fts(lines_fts, rank) VALUES ('integrity-check', 1);")
                .allSatisfy { !$0.hasPrefix("error") }, "辞書の索引の integrity-check が通る")
        }
        do {
            guard let store = OCRStore(url: url) else { check(false, "schema 2 の DB を開き直せる"); return }
            store.insert(row("110000_000", "開き直した後の画面"))
            check(search(url, "開き直した") == ["110000_000"], "開き直した後も引ける")
            check(query(url, "SELECT count(*) FROM sqlite_master WHERE type = 'trigger';") == ["1"],
                  "開き直してもトリガは 1 つのまま")
        }

        // 小さな語彙でランダムな本文を作り、素朴な部分一致と結果が同じかを見る
        let random = dir.appendingPathComponent("random.sqlite")
        do {
            guard let store = OCRStore(url: random) else { check(false, "比較用の DB を作れる"); return }
            var rng = SystemRandomNumberGenerator()
            let vocab = ["請求書", "Error", "error 42", "会議室", "OK", "の", "ContextCap", "メニュー", "", "abcABC"]
            var texts: [String: String] = [:]
            for i in 0..<300 {
                let n = Int.random(in: 1...4, using: &rng)
                let text = (0..<n).map { _ in vocab.randomElement(using: &rng)! }.joined(separator: "\n")
                let base = String(format: "12%04d_000", i)
                store.insert(row(base, text))
                if !text.isEmpty { texts[base] = text }
            }
            var mismatches = 0
            for word in ["請求書", "error", "Error 4", "会議", "OK", "の", "Cap", "abc", "メニュー"] {
                // trigram は ASCII の大文字小文字を区別しない。2 文字以下の instr は区別する（旧形式と同じ）
                let want = texts.filter {
                    word.count < 3 ? $0.value.contains(word) : $0.value.lowercased().contains(word.lowercased())
                }.keys.sorted()
                if search(random, word) != want { mismatches += 1; print("     mismatch: \(word)") }
            }
            check(mismatches == 0, "ランダムな 300 枚で、素朴な部分一致と検索結果が一致する")
        }

        // 旧形式（shots テーブルに本文を持つ）には書かない
        let legacy = dir.appendingPathComponent("legacy.sqlite")
        do {
            var db: OpaquePointer?
            sqlite3_open(legacy.path, &db)
            sqlite3_exec(db, """
            CREATE TABLE shots (day TEXT NOT NULL, base TEXT NOT NULL, text TEXT, status TEXT NOT NULL,
              PRIMARY KEY (day, base));
            INSERT INTO shots VALUES ('2026-10-22', '100000_000', '旧形式の文字', 'ok');
            """, nil, nil, nil)
            sqlite3_close(db)
            check(OCRStore(url: legacy) == nil, "旧形式の DB は開かない（書き込まない）")
            check(query(legacy, "SELECT count(*) FROM sqlite_master WHERE name = 'frames';") == ["0"],
                  "旧形式の DB に新しいテーブルを足さない")
        }

        if failures > 0 {
            print("\(failures) 件失敗")
            exit(1)
        }
        print("全部通った")
    }
}
