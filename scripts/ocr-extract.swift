// ContextCap の撮影画像から Apple Vision で全文 OCR を抽出し、アプリと同じ ocr.sqlite に貯める。
//
//   swiftc -O -parse-as-library -o /tmp/ocr-extract \
//     scripts/ocr-extract.swift macos/Sources/ContextCap/OCRStore.swift \
//     macos/Sources/ContextCap/OCRLayout.swift
//   /tmp/ocr-extract --root ~/ContextCap --db ~/ContextCap-analysis/ocr.sqlite
//
// 設計上の要点:
//   - 同一性のキーは (日付ディレクトリ, gen 接尾辞を除いた基底名)。フルパスではない。
//     Compactor が再圧縮するとファイル名に .gN が付いて変わるため（保存形式の契約と同じ解釈）。
//   - 古い日付から処理する。再圧縮は古い順に食うので、劣化の危険が高い順。
//   - 黒フレーム・OCR 失敗も status 付きで必ず 1 行残す。黙って落とさない。
//   - 出力は ~/ContextCap の外。Compactor の集計対象（*.jpg のみ）ではないが、
//     撮影ルートに育つファイルを置かない。

import AppKit
import CoreGraphics
import Foundation
import ImageIO
import Vision

// MARK: - 引数

struct Options {
    var root = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("ContextCap")
    var db = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("ContextCap-analysis/ocr.sqlite")
    var limit: Int?
    var days: [String]?
    var jobs = max(2, ProcessInfo.processInfo.activeProcessorCount - 2)
    var newestFirst = false
    var progressEvery = 200
    /// 既定を .userInitiated にしている。.utility だと Apple Silicon で efficiency コアに
    /// 寄せられ、実測で 0.4 枚/秒 まで落ちた（同条件の .userInitiated は 2.1 枚/秒）。
    var background = false
    /// N 秒に 1 枚だけ拾う（全量 OCR を待たずに 1 日を粗く見るため）。0 で無効
    var sampleSec = 0
    /// 日付単位のシャーディング。`--shard 0/4` で 4 本のうち 0 番目を担当する。
    ///
    /// Vision のシリアライズはプロセス単位なので、スレッドを増やしても速くならないが
    /// プロセスを増やすと効く（同一画像で実測: 1 本 1.72 / 2 本 3.01 / 3 本 4.04 /
    /// 4 本 4.74 枚/秒）。日で割るのは、同じフレームを 2 本が同時に処理しないため。
    var shardIndex = 0
    var shardCount = 1
    /// 担当範囲を出して即終了。実行せずにシャード分割を検算するため
    var planOnly = false
}

func parseArgs() -> Options {
    var o = Options()
    var it = CommandLine.arguments.dropFirst().makeIterator()
    while let a = it.next() {
        switch a {
        case "--root": if let v = it.next() { o.root = URL(fileURLWithPath: (v as NSString).expandingTildeInPath) }
        case "--db": if let v = it.next() { o.db = URL(fileURLWithPath: (v as NSString).expandingTildeInPath) }
        case "--limit": if let v = it.next() { o.limit = Int(v) }
        case "--days": if let v = it.next() { o.days = v.split(separator: ",").map(String.init) }
        case "--jobs": if let v = it.next(), let n = Int(v) { o.jobs = max(1, n) }
        case "--newest-first": o.newestFirst = true
        case "--plan": o.planOnly = true
        case "--background": o.background = true
        case "--sample-sec": if let v = it.next(), let n = Int(v) { o.sampleSec = n }
        case "--shard":
            guard let v = it.next() else { break }
            let parts = v.split(separator: "/").compactMap { Int($0) }
            guard parts.count == 2, parts[1] > 0, parts[0] >= 0, parts[0] < parts[1] else {
                FileHandle.standardError.write("--shard は i/n 形式で 0 <= i < n\n".data(using: .utf8)!)
                exit(2)
            }
            o.shardIndex = parts[0]
            o.shardCount = parts[1]
        case "--progress-every": if let v = it.next(), let n = Int(v) { o.progressEvery = max(1, n) }
        case "-h", "--help":
            print("""
            usage: ocr-extract [--root DIR] [--db FILE] [--limit N] [--days 2026-08-16,...]
                               [--jobs N] [--newest-first] [--progress-every N] [--background]

              --background  efficiency コア寄りで走らせる（他の作業を邪魔したくない時。約 5 倍遅い）
            """)
            exit(0)
        default:
            FileHandle.standardError.write("unknown arg: \(a)\n".data(using: .utf8)!)
            exit(2)
        }
    }
    return o
}

let opts = parseArgs()

// MARK: - 撮影ファイルの命名規約（macos/Sources/ContextCap/CaptureFile.swift と同じ解釈）

let rungSuffixes = ["g1", "g2", "g3"]

func generation(of url: URL) -> Int {
    let stem = url.deletingPathExtension().lastPathComponent
    for (i, s) in rungSuffixes.enumerated() where stem.hasSuffix(".\(s)") { return i + 1 }
    return 0
}

func baseName(of url: URL) -> String {
    var stem = url.deletingPathExtension().lastPathComponent
    for s in rungSuffixes where stem.hasSuffix(".\(s)") {
        stem.removeLast(s.count + 1)
        break
    }
    return stem
}

let captureParser: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "yyyy-MM-dd HHmmss_SSS"
    f.locale = Locale(identifier: "en_US_POSIX")
    return f
}()

// MARK: - 対象の列挙

struct Shot {
    let url: URL
    let day: String
    let base: String
    let gen: Int
    let ts: Double
    let bytes: Int64
}

/// 日を各シャードへ割り当てる。重い日から順に、その時点で最も軽いシャードへ入れる
/// （LPT スケジューリング）。
///
/// **重みは「その日の総枚数」ではなく「まだ OCR していない枚数」で取る。**
/// 総枚数で割ると、既に半分終わっている日を重いと誤認して偏る
/// （実測: 総枚数基準だと 11,984 枚 対 3,291 枚まで開いた）。
func assignDays(remaining: [String: Int], index: Int, count: Int) -> Set<String> {
    guard count > 1 else { return Set(remaining.keys) }
    let sorted = remaining.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
    var buckets = [Set<String>](repeating: [], count: count)
    var loads = [Int](repeating: 0, count: count)
    for (day, n) in sorted {
        var target = 0
        for i in loads.indices where loads[i] < loads[target] { target = i }
        buckets[target].insert(day)
        loads[target] += n
    }
    return buckets[index]
}

func enumerateShots(_ o: Options) -> [Shot] {
    let fm = FileManager.default
    guard let dayDirs = try? fm.contentsOfDirectory(
        at: o.root, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]
    ) else { return [] }

    var days = dayDirs
        .filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true }
        .map { $0.lastPathComponent }
        .filter { $0.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil }
        .sorted()
    if let want = o.days { days = days.filter { want.contains($0) } }
    if o.newestFirst { days.reverse() }

    var shots: [Shot] = []
    for day in days {
        let dir = o.root.appendingPathComponent(day)
        guard let files = try? fm.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles]
        ) else { continue }
        var perDay: [Shot] = []
        for f in files where f.pathExtension.lowercased() == "jpg" {
            let base = baseName(of: f)
            guard let d = captureParser.date(from: "\(day) \(base)") else { continue }
            let size = (try? f.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
            perDay.append(Shot(url: f, day: day, base: base, gen: generation(of: f),
                               ts: d.timeIntervalSince1970, bytes: Int64(size)))
        }
        perDay.sort { $0.base < $1.base }
        if o.sampleSec > 0 {
            var lastTs = -Double.greatestFiniteMagnitude
            perDay = perDay.filter { s in
                guard s.ts - lastTs >= Double(o.sampleSec) else { return false }
                lastTs = s.ts
                return true
            }
        }
        shots.append(contentsOf: perDay)
    }
    return shots
}

// MARK: - 黒フレーム判定（Android の FrameBlackness と同じ考え方: 格子のセル単位で数える）
// 黒ピクセルの割合では判定しない（ダークモードが同じ形になるため）。ここでは落とさず記録だけする。

let gridN = 16
let darkCellThreshold = 8.0 / 255.0

func blackness(of image: CGImage) -> (darkFraction: Double, meanLum: Double) {
    let n = gridN
    var buf = [UInt8](repeating: 0, count: n * n)
    let cs = CGColorSpaceCreateDeviceGray()
    guard let ctx = CGContext(data: &buf, width: n, height: n, bitsPerComponent: 8,
                              bytesPerRow: n, space: cs,
                              bitmapInfo: CGImageAlphaInfo.none.rawValue) else {
        return (0, 0)
    }
    ctx.interpolationQuality = .medium
    ctx.draw(image, in: CGRect(x: 0, y: 0, width: n, height: n))
    var dark = 0
    var sum = 0.0
    for v in buf {
        let l = Double(v) / 255.0
        sum += l
        if l < darkCellThreshold { dark += 1 }
    }
    return (Double(dark) / Double(n * n), sum / Double(n * n))
}

// MARK: - OCR

struct OCRResult {
    var text = ""
    var lines = 0
    var conf = 0.0
    var status = "ok"
    var err: String?
    var width = 0
    var height = 0
    var darkFraction = 0.0
    var meanLum = 0.0
    var ms = 0
}

func recognize(_ shot: Shot) -> OCRResult {
    var r = OCRResult()
    let t0 = DispatchTime.now().uptimeNanoseconds

    guard let src = CGImageSourceCreateWithURL(shot.url as CFURL, nil),
          let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
        r.status = "error"
        r.err = "decode failed"
        r.ms = Int((DispatchTime.now().uptimeNanoseconds - t0) / 1_000_000)
        return r
    }
    r.width = img.width
    r.height = img.height
    (r.darkFraction, r.meanLum) = blackness(of: img)

    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.recognitionLanguages = ["ja-JP", "en-US"]
    // 実測（08-03 + 08-13 から 40 枚）: 補正 on 0.54 枚/秒 / off 1.20 枚/秒 で 2.2 倍。
    // 一方で文字カバー率は 99.9%、認識行数はむしろ off のほうが多い（5781 → 5817）。
    // 全量 47,388 枚が 24.3 時間 → 11.0 時間になるので off で回す。
    request.usesLanguageCorrection = false

    do {
        let handler = VNImageRequestHandler(cgImage: img, options: [:])
        try handler.perform([request])
        let obs = (request.results ?? [])
        let confs = obs.compactMap { $0.topCandidates(1).first.map { Double($0.confidence) } }
        // 境目で割り、領域ごとの読み順に並べる（アプリと同じ OCRLayout.swift）
        (r.text, r.lines) = layoutText(obs, image: img)
        r.conf = confs.isEmpty ? 0 : confs.reduce(0, +) / Double(confs.count)
        if r.lines == 0 { r.status = "empty" }
    } catch {
        r.status = "error"
        r.err = error.localizedDescription
    }
    r.ms = Int((DispatchTime.now().uptimeNanoseconds - t0) / 1_000_000)
    return r
}

// MARK: - SQLite

/// 書き込みはアプリと同じ `OCRStore` に任せる（`macos/Sources/ContextCap/OCRStore.swift` を
/// 一緒にコンパイルする）。辞書・本文・範囲の書き方を 2 か所に持つと、片方だけ直して壊れる
final class Store {
    /// 書き込みに失敗した件数。並列実行では欠落に直結するので必ず報告する
    private(set) var writeFailures = 0
    private let store: OCRStore

    init(path: URL) throws {
        guard let store = OCRStore(url: path) else {
            throw NSError(domain: "ocr", code: 1, userInfo: [NSLocalizedDescriptionKey:
                "開けない、または旧形式（先に scripts/ocr-compact.py で移行する）: \(path.path)"])
        }
        self.store = store
    }

    func existingKeys() -> Set<String> {
        store.existingKeys()
    }

    /// 溜めた行を書く。1 枚ごとに `OCRStore` がトランザクションを切る。
    ///
    /// **OCR しながらトランザクションを開けたままにしない。** SQLite は WAL でも
    /// writer が同時に 1 つしか居られず、最初の INSERT から COMMIT までロックを保持する。
    /// 50 件たまるまで開けっぱなしにすると 1 プロセスが 40 秒ロックを握り、
    /// 4 本並列でも 3 本が常に待つ（実測: 本来 4.74 枚/秒 のところ 1.67 枚/秒 まで落ちた）。
    func flush(_ batch: [(Shot, OCRResult)]) {
        for (s, r) in batch {
            let row = OCRRow(
                day: s.day, base: s.base, gen: s.gen, ts: s.ts, path: s.url.path,
                width: r.width, height: r.height, bytes: Int(s.bytes),
                text: r.text, lines: r.lines, conf: r.conf, ocrMS: r.ms,
                status: r.status, err: r.err, darkFraction: r.darkFraction, meanLum: r.meanLum
            )
            // 並列実行では書き込み失敗＝そのフレームが静かに欠ける。件数を持ち回って
            // 最後に必ず報告する（0 でないなら、その分は再実行で拾い直す必要がある）
            if !store.insert(row) {
                writeFailures += 1
                FileHandle.standardError.write("insert failed: \(s.day)/\(s.base)\n".data(using: .utf8)!)
            }
        }
    }
}

// MARK: - 実行

func log(_ s: String) {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss"
    print("[\(f.string(from: Date()))] \(s)")
    fflush(stdout)
}

@main
struct OCRExtract {
    static func main() {
        let store: Store
        do {
            store = try Store(path: opts.db)
        } catch {
            FileHandle.standardError.write("db error: \(error.localizedDescription)\n".data(using: .utf8)!)
            exit(1)
        }

        let done = store.existingKeys()
        var todo = enumerateShots(opts).filter { !done.contains("\($0.day)/\($0.base)") }
        let totalFound = todo.count + done.count

        // シャード分割は「残り枚数」を確定させてから行う。日の総枚数で割ると偏る
        if opts.shardCount > 1 {
            var remaining: [String: Int] = [:]
            for s in todo { remaining[s.day, default: 0] += 1 }
            let mine = assignDays(remaining: remaining, index: opts.shardIndex, count: opts.shardCount)
            todo = todo.filter { mine.contains($0.day) }
        }

        if let n = opts.limit { todo = Array(todo.prefix(n)) }

        log("root=\(opts.root.path) db=\(opts.db.path)")
        let shardLabel = opts.shardCount > 1 ? " / shard \(opts.shardIndex)/\(opts.shardCount)" : ""
        log("対象 \(totalFound) 枚 / 済 \(done.count) 枚 / 今回 \(todo.count) 枚 / jobs=\(opts.jobs)\(shardLabel)")

        if opts.planOnly {
            let byDay = Dictionary(grouping: todo, by: { $0.day })
                .map { (day: $0.key, n: $0.value.count) }
                .sorted { $0.day < $1.day }
            log("担当: " + byDay.map { "\($0.day)=\($0.n)" }.joined(separator: " "))
            log("PLAN 合計 \(todo.count)")
            exit(0)
        }

        if todo.isEmpty { log("処理対象なし"); exit(0) }

        let writeQ = DispatchQueue(label: "ocr.write")
        let sem = DispatchSemaphore(value: opts.jobs)
        let group = DispatchGroup()
        var processed = 0
        var statusCounts: [String: Int] = [:]
        var msSum = 0
        let start = Date()
        /// 書き込み待ちの行。トランザクションを開けたまま OCR しないための緩衝
        var batch: [(Shot, OCRResult)] = []

        for shot in todo {
            sem.wait()
            group.enter()
            DispatchQueue.global(qos: opts.background ? .utility : .userInitiated).async {
                let r = recognize(shot)
                writeQ.async {
                    batch.append((shot, r))
                    processed += 1
                    statusCounts[r.status, default: 0] += 1
                    msSum += r.ms
                    // 途中で落ちた時に失う量を抑えるため細かく刻む。
                    // 50 件の書き込み自体は数十ミリ秒で終わり、その間だけロックを握る
                    if batch.count >= 50 {
                        store.flush(batch)
                        batch.removeAll(keepingCapacity: true)
                    }
                    if processed % opts.progressEvery == 0 {
                        let el = Date().timeIntervalSince(start)
                        let rate = Double(processed) / el
                        let remain = Double(todo.count - processed) / max(rate, 0.001)
                        log(String(format: "%d/%d  %.1f 枚/秒  平均 %dms/枚  残り %.0f 分  %@",
                                   processed, todo.count, rate, msSum / max(processed, 1),
                                   remain / 60,
                                   statusCounts.sorted { $0.key < $1.key }
                                       .map { "\($0.key)=\($0.value)" }.joined(separator: " ")))
                    }
                    sem.signal()
                    group.leave()
                }
            }
        }

        group.wait()
        writeQ.sync {
            store.flush(batch)
            batch.removeAll()
        }

        let el = Date().timeIntervalSince(start)
        log(String(format: "完了 %d 枚 / %.1f 分 / %.1f 枚/秒 / 平均 %dms",
                   processed, el / 60, Double(processed) / el, msSum / max(processed, 1)))
        log("status: " + statusCounts.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }
            .joined(separator: " "))
        if store.writeFailures > 0 {
            log("⚠️ 書き込み失敗 \(store.writeFailures) 件。その分は DB に入っていないので再実行すること")
            exit(1)
        }
    }
}
