// OCR の速度レバーを実測する。標本は「文字の多い画面」で採る。
// 前回 08-16 から等間隔 20 枚で測ったら 1 枚 43 行しか無く、2.1 枚/秒 という
// 実態の 4 倍速い嘘の数字が出た。OCR 時間は解像度ではなく行数に比例するので、
// 標本は必ず実際に処理する母集団と同じ文字密度で採る。
//
//   swiftc -O -o /tmp/ocr-bench scripts/ocr-bench.swift && /tmp/ocr-bench --n 40

import CoreGraphics
import Foundation
import ImageIO
import Vision

var sampleN = 40
var it = CommandLine.arguments.dropFirst().makeIterator()
while let a = it.next() { if a == "--n", let v = it.next(), let n = Int(v) { sampleN = n } }

let root = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("ContextCap")

// 08-03 は実測で 1 枚 164 行・19 秒。母集団の重い側の代表。
func sample() -> [URL] {
    var all: [URL] = []
    for day in ["2026-08-03", "2026-08-13"] {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: root.appendingPathComponent(day), includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]) else { continue }
        let jpgs = files.filter { $0.pathExtension.lowercased() == "jpg" }.sorted { $0.path < $1.path }
        guard !jpgs.isEmpty else { continue }
        let want = sampleN / 2
        let step = max(1, jpgs.count / want)
        all += stride(from: 0, to: jpgs.count, by: step).prefix(want).map { jpgs[$0] }
    }
    return all
}

func load(_ url: URL) -> CGImage? {
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
    return CGImageSourceCreateImageAtIndex(src, 0, nil)
}

struct Variant {
    let name: String
    let correction: Bool
    let langs: [String]
    let jobs: Int
}

let variants: [Variant] = [
    Variant(name: "ja+en 補正on  jobs=8", correction: true, langs: ["ja-JP", "en-US"], jobs: 8),
    Variant(name: "ja+en 補正off jobs=8", correction: false, langs: ["ja-JP", "en-US"], jobs: 8),
    Variant(name: "ja+en 補正off jobs=4", correction: false, langs: ["ja-JP", "en-US"], jobs: 4),
    Variant(name: "ja+en 補正off jobs=16", correction: false, langs: ["ja-JP", "en-US"], jobs: 16),
    Variant(name: "ja    補正off jobs=8", correction: false, langs: ["ja-JP"], jobs: 8),
]

struct Out { var text = ""; var lines = 0; var ms = 0 }

func run(_ img: CGImage, _ v: Variant) -> Out {
    var o = Out()
    let t0 = DispatchTime.now().uptimeNanoseconds
    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    req.recognitionLanguages = v.langs
    req.usesLanguageCorrection = v.correction
    let h = VNImageRequestHandler(cgImage: img, options: [:])
    if (try? h.perform([req])) != nil {
        let parts = (req.results ?? []).compactMap { $0.topCandidates(1).first?.string }
        o.lines = parts.count
        o.text = parts.joined(separator: "\n")
    }
    o.ms = Int((DispatchTime.now().uptimeNanoseconds - t0) / 1_000_000)
    return o
}

let files = sample()
print("sample: \(files.count) 枚 (08-03 + 08-13, 文字の多い日)\n")

// デコードは一度だけ。OCR の時間だけを比べる
let images = files.compactMap { load($0) }
print("decoded: \(images.count) 枚\n")

var baseline: [String] = []

for (vi, v) in variants.enumerated() {
    var outs = [Out](repeating: Out(), count: images.count)
    let lock = NSLock()
    let sem = DispatchSemaphore(value: v.jobs)
    let group = DispatchGroup()
    let start = Date()
    for i in images.indices {
        sem.wait(); group.enter()
        DispatchQueue.global(qos: .userInitiated).async {
            let o = run(images[i], v)
            lock.lock(); outs[i] = o; lock.unlock()
            sem.signal(); group.leave()
        }
    }
    group.wait()
    let wall = Date().timeIntervalSince(start)
    let lines = outs.reduce(0) { $0 + $1.lines }
    if vi == 0 { baseline = outs.map { $0.text } }

    var cover = ""
    if vi > 0 {
        var kept = 0, total = 0
        for (i, o) in outs.enumerated() {
            let b = baseline[i].filter { !$0.isWhitespace }
            let c = Set(o.text.filter { !$0.isWhitespace })
            total += b.count
            kept += b.filter { c.contains($0) }.count
        }
        cover = String(format: "  文字カバー率 %.1f%%", total == 0 ? 0 : 100.0 * Double(kept) / Double(total))
    }
    print(String(format: "%-22@  %.2f 枚/秒  %5d 行  47,388 枚換算 %.1f 時間%@",
                 v.name as NSString, Double(images.count) / wall, lines,
                 47388.0 / (Double(images.count) / wall) / 3600, cover))
}
