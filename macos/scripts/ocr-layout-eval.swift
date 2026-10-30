// OCRLayout の前後で本文がどう変わるかを、実際の撮影画像で比べる。Vision は 1 枚 1 回だけ回し、
// 旧来の並べ方（上から下、同じ高さは左から右）と layoutText の両方を 1 行の JSON で書き出す。
//
//   swiftc -O -parse-as-library -o /tmp/ocr-layout-eval \
//     scripts/ocr-layout-eval.swift Sources/ContextCap/OCRLayout.swift
//   /tmp/ocr-layout-eval out.jsonl ~/ContextCap/2026-10-30/*.jpg
//
// 標準エラーに 1 枚ごとの行数・境目で割った行の数・時間を出す。

import CoreGraphics
import Foundation
import ImageIO
import Vision

@main
struct OCRLayoutEval {
    static func main() {
        let args = Array(CommandLine.arguments.dropFirst())
        guard args.count >= 2 else {
            fputs("usage: ocr-layout-eval out.jsonl image...\n", stderr)
            exit(2)
        }
        FileManager.default.createFile(atPath: args[0], contents: nil)
        guard let out = FileHandle(forWritingAtPath: args[0]) else {
            fputs("cannot write: \(args[0])\n", stderr)
            exit(1)
        }
        for path in args.dropFirst() {
            guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
                  let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
                fputs("cannot load: \(path)\n", stderr)
                continue
            }
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.recognitionLanguages = ["ja-JP", "en-US"]
            request.usesLanguageCorrection = false
            let t0 = Date()
            do {
                try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
            } catch {
                fputs("ocr failed: \(path)\n", stderr)
                continue
            }
            let tOCR = Date().timeIntervalSince(t0)
            let observations = request.results ?? []

            // 変更前の OCRIndexer と同じ並べ方
            let old = observations.sorted { a, b in
                let ay = a.boundingBox.midY, by = b.boundingBox.midY
                if abs(ay - by) > 0.01 { return ay > by }
                return a.boundingBox.minX < b.boundingBox.minX
            }.compactMap { $0.topCandidates(1).first?.string }

            let t1 = Date()
            let borders = BorderMap(image: image)
            let tBorders = Date().timeIntervalSince(t1)
            // layoutText と同じ条件で、境目をまたいで読まれた行を数える
            let w = CGFloat(image.width), h = CGFloat(image.height)
            var cut: [[String: Any]] = []
            for observation in observations {
                guard let borders, let c = observation.topCandidates(1).first else { continue }
                let bb = observation.boundingBox
                let rect = CGRect(x: bb.minX * w, y: (1 - bb.maxY) * h,
                                  width: bb.width * w, height: bb.height * h)
                guard rect.width >= rect.height * 6 else { continue }
                let xs = borders.verticalBorders(through: rect)
                if !xs.isEmpty { cut.append(["text": c.string, "at": xs.map { Double($0) }]) }
            }

            let t2 = Date()
            let new = layoutText(observations, image: image)
            let tLayout = Date().timeIntervalSince(t2)

            let obj: [String: Any] = [
                "path": path,
                "ocr_s": tOCR, "borders_s": tBorders, "layout_s": tLayout,
                "old_lines": old.count, "new_lines": new.lines,
                "regions": new.text.components(separatedBy: "\n\n").count,
                "cut": cut,
                "old": old.joined(separator: "\n"), "new": new.text,
            ]
            var data = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data()
            data.append(0x0A)
            out.write(data)
            fputs(String(format: "%@ old=%d new=%d cut=%d ocr=%.2fs borders=%.2fs layout=%.2fs\n",
                         path, old.count, new.lines, cut.count, tOCR, tBorders, tLayout), stderr)
        }
    }
}
