import Foundation

/// 保存ファイルの命名規約: <root>/YYYY-MM-DD/HHmmss_SSS[.gN].jpg
/// 撮影時刻はファイル属性ではなくパスから解釈する（圧縮・コピーで属性が変わっても不変）。
enum CaptureFile {
    static let dayFormat = "yyyy-MM-dd"
    static let timeFormat = "HHmmss_SSS"

    private static let parser: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "\(dayFormat) \(timeFormat)"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = dayFormat
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = timeFormat
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    /// 日付ディレクトリ名（Kotlin 側 `CaptureFile.dayDirName` と対）
    static func dayDirName(_ date: Date) -> String {
        dayFormatter.string(from: date)
    }

    /// gen 接尾辞を付ける前の基底名（Kotlin 側 `CaptureFile.timeStem` と対）
    static func timeStem(_ date: Date) -> String {
        timeFormatter.string(from: date)
    }

    /// 過去に段階圧縮していた頃の接尾辞。**新規ファイルには付かない。**
    /// 2026-08-18 に再圧縮を廃止したが、既存アーカイブに .g1 が 24,148 枚残っており、
    /// これを読めないと撮影時刻の解釈と OCR の同一性判定が壊れる。消さないこと。
    /// Kotlin 側 `CaptureFile` / `CompressionRung` とは今後この点だけ非対称になる。
    static let legacyGenerationSuffixes: [(gen: Int, suffix: String)] = [
        (gen: 1, suffix: "g1"), (gen: 2, suffix: "g2"), (gen: 3, suffix: "g3"),
    ]

    /// "162303_273.jpg" → 0, "162303_273.g2.jpg" → 2
    static func generation(of url: URL) -> Int {
        let stem = url.deletingPathExtension().lastPathComponent
        for entry in legacyGenerationSuffixes where stem.hasSuffix(".\(entry.suffix)") {
            return entry.gen
        }
        return 0
    }

    /// gen 接尾辞を除いた基底名（"162303_273.g1" → "162303_273"）
    static func baseName(of url: URL) -> String {
        var stem = url.deletingPathExtension().lastPathComponent
        for entry in legacyGenerationSuffixes where stem.hasSuffix(".\(entry.suffix)") {
            stem.removeLast(entry.suffix.count + 1)
            break
        }
        return stem
    }

    /// 親ディレクトリ名 + 基底名から撮影時刻を解釈する。規約外の名前なら nil
    static func captureDate(of url: URL) -> Date? {
        let day = url.deletingLastPathComponent().lastPathComponent
        return parser.date(from: "\(day) \(baseName(of: url))")
    }
}
