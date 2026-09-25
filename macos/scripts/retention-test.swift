// Retention の未 OCR ガードを実際に発火させて確かめる。
// macOS 側にテストターゲットが無いので、対象ソースを直接コンパイルして叩く。
import Foundation

@main
struct RetentionTest {
    static func make(_ root: URL, _ day: String, _ stem: String) -> URL {
        let dir = root.appendingPathComponent(day)
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(stem).jpg")
        try! Data(repeating: 0x41, count: 1000).write(to: url)
        return url
    }

    static func dayString(daysAgo: Int) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: Calendar.current.date(byAdding: .day, value: -daysAgo, to: Date())!)
    }

    static func main() async {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("retention-test-\(ProcessInfo.processInfo.processIdentifier)")

        let oldDay = dayString(daysAgo: 10)   // 3 日を大きく超える
        let newDay = dayString(daysAgo: 0)    // 保持期間内

        let oldIndexed   = make(root, oldDay, "120000_000")     // 古い・OCR済み  → 消えるべき
        let oldUnindexed = make(root, oldDay, "130000_000")     // 古い・未OCR   → **残るべき**
        let oldLegacyG1  = make(root, oldDay, "140000_000.g1")  // 古い・OCR済み(.g1) → 消えるべき
        let newIndexed   = make(root, newDay, "150000_000")     // 新しい・OCR済み → 残るべき
        // 境目: 2 日前の 12:00 は最大でも 60h 前、4 日前の 12:00 は最小でも 84h 前（保持 72h）
        let day2 = dayString(daysAgo: 2), day4 = dayString(daysAgo: 4)
        let within = make(root, day2, "120000_000")             // 72h 以内・OCR済み → 残るべき
        let beyond = make(root, day4, "120000_000")             // 72h 超・OCR済み  → 消えるべき

        // OCR 済みキーは gen 接尾辞を除いた基底名で持つ（保存形式の契約どおり）
        let indexed: Set<String> = [
            "\(oldDay)/120000_000",
            "\(oldDay)/140000_000",
            "\(newDay)/150000_000",
            "\(day2)/120000_000",
            "\(day4)/120000_000",
        ]

        let result = await Retention(root: root).sweep(indexedKeys: indexed)
        func exists(_ u: URL) -> Bool { FileManager.default.fileExists(atPath: u.path) }

        var failures = 0
        func check(_ label: String, _ got: Bool, _ want: Bool) {
            if got == want { print("  ok   \(label)") }
            else { print("  FAIL \(label): got \(got), want \(want)"); failures += 1 }
        }

        print("sweep 結果: 削除 \(result?.deleted ?? 0) 枚 / 未 OCR で保留 \(result?.blockedByOCR ?? 0) 枚")
        check("古い・OCR済み は消える",       exists(oldIndexed),   false)
        check("古い・未OCR は残る（ガード）",  exists(oldUnindexed), true)
        check("古い・OCR済み(.g1) は消える",  exists(oldLegacyG1),  false)
        check("新しい・OCR済み は残る",       exists(newIndexed),   true)
        check("2 日前・OCR済み は残る（72h 以内）", exists(within), true)
        check("4 日前・OCR済み は消える（72h 超）", exists(beyond), false)
        check("blockedByOCR が 1",           result?.blockedByOCR == 1, true)

        try? FileManager.default.removeItem(at: root)
        if failures > 0 { print("\n\(failures) 件 FAIL"); exit(1) }
        print("\n全部通った")
    }
}
