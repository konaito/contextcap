import Foundation

/// 保存済みスクショの枚数・期間・容量を集計する。
/// 起動時にフルスキャンし、以降は撮影ごとに増分更新する。
@MainActor
final class StatsStore {
    let rootDirectory: URL

    private(set) var count: Int = 0
    private(set) var totalBytes: Int64 = 0
    private(set) var firstDate: Date?
    private(set) var lastDate: Date?

    init(rootDirectory: URL) {
        self.rootDirectory = rootDirectory
        rescan()
    }

    /// ディレクトリをフルスキャンして実測値に合わせる
    func rescan() {
        var newCount = 0
        var newBytes: Int64 = 0
        var newFirst: Date?
        var newLast: Date?

        let keys: [URLResourceKey] = [.fileSizeKey, .creationDateKey, .isRegularFileKey]
        guard let enumerator = FileManager.default.enumerator(
            at: rootDirectory,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles]
        ) else {
            count = 0
            totalBytes = 0
            firstDate = nil
            lastDate = nil
            return
        }

        for case let url as URL in enumerator {
            guard url.pathExtension.lowercased() == "jpg",
                  let values = try? url.resourceValues(forKeys: Set(keys)),
                  values.isRegularFile == true else { continue }
            newCount += 1
            newBytes += Int64(values.fileSize ?? 0)
            if let captured = CaptureFile.captureDate(of: url) ?? values.creationDate {
                if newFirst == nil || captured < newFirst! { newFirst = captured }
                if newLast == nil || captured > newLast! { newLast = captured }
            }
        }

        count = newCount
        totalBytes = newBytes
        firstDate = newFirst
        lastDate = newLast
    }

    /// 撮影 1 枚分の増分更新
    func recordCapture(bytes: Int64, date: Date) {
        count += 1
        totalBytes += bytes
        if firstDate == nil { firstDate = date }
        lastDate = date
    }

    // MARK: - 表示用フォーマット

    var countText: String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        let n = formatter.string(from: NSNumber(value: count)) ?? "\(count)"
        return "\(n) 枚"
    }

    var sizeText: String {
        ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file)
    }

    var durationText: String {
        guard let first = firstDate, let last = lastDate, last > first else {
            return "—"
        }
        let seconds = Int(last.timeIntervalSince(first))
        let days = seconds / 86_400
        let hours = (seconds % 86_400) / 3_600
        let minutes = (seconds % 3_600) / 60
        if days > 0 {
            return "\(days)日と\(hours)時間"
        }
        if hours > 0 {
            return "\(hours)時間\(minutes)分"
        }
        return "\(minutes)分"
    }
}
