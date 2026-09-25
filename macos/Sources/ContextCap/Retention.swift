import Foundation

/// 保存総量の上限。`defaults write app.imichat.contextcap StorageBudgetGB -float N` で
/// 上書き可能（テスト用）。未設定なら 40GB。
enum StorageBudget {
    static var bytes: Int64 {
        let gb = UserDefaults.standard.double(forKey: "StorageBudgetGB")
        let effective = gb > 0 ? gb : 40
        return Int64(effective * 1_000_000_000)
    }

    static var displayText: String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

struct RetentionResult: Sendable {
    var deleted = 0
    var freedBytes: Int64 = 0
    /// OCR がまだ済んでいないので消せなかった枚数。0 でないなら OCR が詰まっている
    var blockedByOCR = 0
    var finishedAt = Date.distantPast
}

/// 保持期間を過ぎた画像を削除する。**再圧縮はしない。**
///
/// なぜ段階圧縮（旧 Compactor）を捨てたか:
/// 段階圧縮は「テキストが無いから画像を捨てられない」という前提の産物だった。
/// OCR が撮影に先行するなら、テキストが本体になって画像は一時キャッシュになる。
/// そして再圧縮には実害しかない — 同一画像の統制実験（60 枚・lines 分位で層化）で、
/// 圧縮しても OCR は速くならず（時間は解像度ではなく行数に比例。≒ 5.5ms/行 + 60ms）、
/// テキストだけが壊れた:
///
///   段            平均行数   文字保持   行近似一致(≥0.85)   1 枚
///   gen0（等倍）    101.0     1.000        1.000          813KB
///   g1 (1920/q60)   97.3     0.948        0.751          278KB
///   g2 (1280/q50)   88.5     0.891        0.568          106KB
///   g3 (800/q40)    24.1     0.258        0.056           40KB
///
/// 容量の見積り: ピーク実績 9,383 枚/日 × 813KB = 7.3GB/日。3 日で 21.9GB なので
/// 40GB に収まる。ただし理論最大（24h 稼働・全フレーム差分あり）だと 13.4GB/日 に
/// なって 3 日で 40.2GB と僅かに超えるので、日数だけでなく容量の逃げ道も残してある。
///
/// **削除は不可逆なので、OCR が済んでいないファイルは絶対に消さない。**
/// 消せずに容量が上限を超え続ける場合は、それを `blockedByOCR` で表に出す。
/// 黙って消して 2026-08-18 と同じ損失（corpus の 39% が劣化テキスト）を繰り返さない。
actor Retention {
    /// 保持日数。これより古い「OCR 済み」画像を消す
    static let retentionDays = 3

    private struct Entry {
        let url: URL
        let size: Int64
        let date: Date
        let key: String
    }

    private let root: URL

    init(root: URL) {
        self.root = root
    }

    /// 削除パスを 1 回実行する。消すものが無ければ nil。
    ///
    /// `indexedKeys` は「OCR 済みの "day/base" 集合」。OCRIndexer から受け取る。
    /// 呼び出しごとに 1 回だけ取るので、パス中に増えた分は次回に回る（安全側）。
    func sweep(indexedKeys: Set<String>) -> RetentionResult? {
        let entries = scan()
        guard !entries.isEmpty else { return nil }

        var result = RetentionResult()
        var total = entries.reduce(Int64(0)) { $0 + $1.size }
        let cutoff = Calendar.current.date(
            byAdding: .day, value: -Self.retentionDays, to: Date()
        ) ?? .distantPast

        // 古い順に見る。消す条件は「OCR 済み」かつ「保持期間外 または 容量超過」
        let byAge = entries.sorted { $0.date < $1.date }
        for entry in byAge {
            let tooOld = entry.date < cutoff
            let overBudget = total > StorageBudget.bytes
            guard tooOld || overBudget else { break }

            guard indexedKeys.contains(entry.key) else {
                // まだ読めていない。消したらテキストごと永久に失われる
                result.blockedByOCR += 1
                continue
            }
            guard (try? FileManager.default.removeItem(at: entry.url)) != nil else { continue }
            total -= entry.size
            result.deleted += 1
            result.freedBytes += entry.size
        }

        guard result.deleted > 0 || result.blockedByOCR > 0 else { return nil }
        if result.deleted > 0 { removeEmptyDayDirectories() }
        result.finishedAt = Date()
        return result
    }

    // MARK: - スキャン

    private func scan() -> [Entry] {
        let keys: [URLResourceKey] = [.fileSizeKey, .creationDateKey, .isRegularFileKey]
        guard let walker = FileManager.default.enumerator(
            at: root, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]
        ) else { return [] }

        var entries: [Entry] = []
        for case let url as URL in walker {
            guard url.pathExtension.lowercased() == "jpg",
                  let values = try? url.resourceValues(forKeys: Set(keys)),
                  values.isRegularFile == true else { continue }
            let day = url.deletingLastPathComponent().lastPathComponent
            let base = CaptureFile.baseName(of: url)
            entries.append(Entry(
                url: url,
                size: Int64(values.fileSize ?? 0),
                // 撮影時刻はファイル属性ではなくパスから読む（保存形式の契約）
                date: CaptureFile.captureDate(of: url) ?? values.creationDate ?? .distantPast,
                key: "\(day)/\(base)"
            ))
        }
        return entries
    }

    private func removeEmptyDayDirectories() {
        guard let dayDirs = try? FileManager.default.contentsOfDirectory(
            at: root, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]
        ) else { return }
        for dir in dayDirs {
            guard (try? dir.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true,
                  let contents = try? FileManager.default.contentsOfDirectory(
                      at: dir, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]
                  ),
                  contents.isEmpty else { continue }
            try? FileManager.default.removeItem(at: dir)
        }
    }
}
