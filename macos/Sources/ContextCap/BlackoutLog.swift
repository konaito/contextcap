import Foundation

/// 情報のない画面（DRM 保護・消灯直前）が続いた区間を日付ごとの JSONL に残す。
///
/// **画像は保存しないが、観測できなかったという事実は残す。** 2 時間の映画なら
/// 1,440 枚の真っ黒な JPEG が 2 行になる。
///
/// 状態が変わった瞬間だけ 1 行書く。日付が変わった時は、同じ状態でもその日の
/// 最初の 1 行を書く（日ごとのファイルだけ見れば区間が復元できるようにするため）。
///
/// Android の `BlackoutLog.kt` と**二重実装**。行の形（`{"t":...,"state":...}`）と
/// start/end の出し方を必ず一致させる。契約のテストは Kotlin 側 `BlackoutLogTest` が正本。
final class BlackoutLog {
    static let fileName = "blackouts.jsonl"
    static let stateStart = "start"
    static let stateEnd = "end"

    private var lastDay: String?
    private var lastUninformative: Bool?

    /// 撮影 1 枚分。状態が前回と同じなら何も書かない。
    func record(dayDir: URL, date: Date, stem: String, uninformative: Bool) {
        let day = CaptureFile.dayDirName(date)
        let wasBlack = lastUninformative == true
        let dayRolled = lastDay != nil && day != lastDay

        // 黒に入った時と、黒のまま日付が変わった時に start。黒から抜けた時だけ end。
        // 起動直後の 1 枚目が情報ありでも end を書かない（区間が始まっていない）
        let needStart = uninformative && (!wasBlack || dayRolled)
        let needEnd = !uninformative && wasBlack
        guard needStart || needEnd else {
            lastDay = day
            lastUninformative = uninformative
            return
        }

        let state = uninformative ? Self.stateStart : Self.stateEnd
        let line = #"{"t":"\#(stem)","state":"\#(state)"}"# + "\n"
        guard JSONLWriter.append(line, to: dayDir.appendingPathComponent(Self.fileName)) else {
            return
        }
        lastDay = day
        lastUninformative = uninformative
    }
}

/// JSONL への追記。失敗しても撮影を止めない（呼び出し側が成否で状態更新を決める）。
enum JSONLWriter {
    @discardableResult
    static func append(_ line: String, to url: URL) -> Bool {
        guard let data = line.data(using: .utf8) else { return false }
        let fm = FileManager.default
        if !fm.fileExists(atPath: url.path) {
            return fm.createFile(atPath: url.path, contents: data)
        }
        guard let handle = try? FileHandle(forWritingTo: url) else { return false }
        defer { try? handle.close() }
        do {
            try handle.seekToEnd()
            try handle.write(contentsOf: data)
            return true
        } catch {
            return false
        }
    }
}
