// OCR の行を、画面の境目（分割ペインの境目・ウィンドウの端・タブバー・枠線）をまたがない
// まとまりに分け、領域ごとの読み順に並べる。分割画面で左右のペインの行が交互に並んだり、
// 1 行に混ざったりするのを防ぐ。
//
// imichat2b の apps/desktop/native/ScreenOCR.swift（imi-chat/imichat2b#316、
// 54f6e81）からの写し。元は kotoba-overlay#3 で、ghostty の分割画面 25 枚・ゲーム画面
// 10 枚・アプリ画面 11 枚で閾値を決めている。原本と突き合わせられるよう、写した部分の
// 英語コメントと閾値はそのまま残す。原本から変えたのは、破片をつなぐかの判定
// （`separates(left:right:)`）で行の上下の延長を片側だけでよくしたこと（理由はそこの
// コメント）。足したのは末尾の `layoutText`。
import CoreGraphics
import Foundation
import ImageIO
import Vision

/// One OCR line in image pixels, top-left origin.
struct Line {
    var text: String
    var rect: CGRect
}

/// Where the screen changes colour along a long straight line: split-pane
/// dividers, tab bars, window edges, box borders, a band of different
/// background. Text from two sides of such a line belongs to different
/// panes or windows, even when the gap between the lines is small.
///
/// Tuned on 25 real ghostty split-pane screens (3600x2338): ghostty draws a
/// 2px divider about 16-18 levels darker than the background, and leaves
/// the background of unfocused splits unchanged, so that thin line is the
/// only cue. Text never forms such a line because glyphs leave gaps.
struct BorderMap {
    let width: Int
    let height: Int
    /// true where the colour changes going down (a horizontal border).
    private var horizontal: [Bool]
    /// true where the colour changes going right (a vertical border).
    private var vertical: [Bool]

    /// Min channel difference (0-255). Background bands in a terminal differ
    /// by only ~8, so this has to stay low; the length requirement below
    /// keeps noise and glyphs out.
    static let threshold: Int32 = 6
    /// Also compare pixels this far apart to catch soft shadows. Adjacent
    /// pixels are compared too: a 2px divider is skipped by a K-apart
    /// comparison alone.
    static let shadowDistance = 2
    /// Gaps this wide still count as one continuous line (dashes, noise).
    static let maxGap = 2
    /// A border must run continuously over this fraction of the span.
    static let coverage = 0.8
    /// A border is a line through flat surroundings: on at least one side,
    /// `isolationDistance` px away, fewer than `isolationMax` of the pixels
    /// may be edges. Without this, textured game backgrounds and video noise
    /// passed everywhere at threshold 6 (10 game frames: 28 chat lines cut
    /// into words; with it 0 cuts).
    static let isolationDistance = 6
    static let isolationMax = 0.3

    init?(image: CGImage) {
        let w = image.width, h = image.height
        guard w > 2 * Self.shadowDistance, h > 2 * Self.shadowDistance else { return nil }
        let bytesPerRow = w * 4
        var pixels = [UInt8](repeating: 0, count: bytesPerRow * h)
        let drawn = pixels.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h,
                                      bitsPerComponent: 8, bytesPerRow: bytesPerRow,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
            else { return false }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return nil }
        width = w
        height = h
        var hz = [Bool](repeating: false, count: w * h)
        var vt = [Bool](repeating: false, count: w * h)
        let k = Self.shadowDistance
        let t = Self.threshold
        pixels.withUnsafeBufferPointer { p in
            // Row 0 of the buffer is the top of the image.
            @inline(__always) func diff(_ i: Int, _ j: Int) -> Bool {
                let a = i * 4, b = j * 4
                return abs(Int32(p[a]) - Int32(p[b])) > t
                    || abs(Int32(p[a + 1]) - Int32(p[b + 1])) > t
                    || abs(Int32(p[a + 2]) - Int32(p[b + 2])) > t
            }
            for y in 0..<h {
                let row = y * w
                for x in 0..<w {
                    let i = row + x
                    if y >= 1 && diff(i, i - w) { hz[i] = true }
                    else if y >= k && y < h - k && diff(i + k * w, i - k * w) { hz[i] = true }
                    if x >= 1 && diff(i, i - 1) { vt[i] = true }
                    else if x >= k && x < w - k && diff(i + k, i - k) { vt[i] = true }
                }
            }
        }
        horizontal = hz
        vertical = vt
    }

    /// Is there a horizontal border between `upper` and `lower`? Looks a
    /// little inside both boxes too: a line clipped at a pane edge gets an
    /// OCR box that overlaps the divider.
    func separates(_ upper: CGRect, _ lower: CGRect) -> Bool {
        let pad = 0.25 * max(upper.height, lower.height)
        return horizontalLine(y0: upper.maxY - pad, y1: lower.minY + pad,
                              x0: min(upper.minX, lower.minX), x1: max(upper.maxX, lower.maxX))
    }

    /// A horizontal border somewhere between y0 and y1 that runs over most
    /// of x0...x1.
    func horizontalLine(y0: CGFloat, y1: CGFloat, x0: CGFloat, x1: CGFloat) -> Bool {
        let y0 = max(0, Int(y0)), y1 = min(height, Int(y1))
        let x0 = max(0, Int(x0)), x1 = min(width, Int(x1))
        guard y1 > y0, x1 > x0 else { return false }
        let need = Int(Self.coverage * Double(x1 - x0))
        for y in y0..<y1 where longestRun(row: y, from: x0, to: x1) >= need
            && flatBeside(row: y, from: x0, to: x1) {
            return true
        }
        return false
    }

    /// x positions of vertical borders that run through the whole height
    /// of `rect` and one line-height beyond it on both sides, ignoring one
    /// line-height at each end. Used to cut an OCR line that spans two
    /// panes. A quarter line-height also matched glyph strokes and cursor
    /// edges (3 false cuts in 25 screens); a full line-height kept all 6
    /// real dividers and dropped those.
    ///
    /// ContextCap: with `eitherSide`, one line-height beyond on one side is
    /// enough. Only `separates(left:right:)` uses it; see there.
    func verticalBorders(through rect: CGRect, lineHeight: CGFloat? = nil,
                         eitherSide: Bool = false) -> [CGFloat] {
        let lineHeight = lineHeight ?? rect.height
        let y0 = max(0, Int(rect.minY - lineHeight))
        let y1 = min(height, Int(rect.maxY + lineHeight))
        let x0 = max(0, Int(rect.minX + lineHeight))
        let x1 = min(width, Int(rect.maxX - lineHeight))
        guard y1 > y0, x1 > x0 else { return [] }
        var hits: [Int] = []
        for x in x0..<x1 {
            if eitherSide {
                if borderAboveOrBelow(column: x, rect: rect, from: y0, to: y1) { hits.append(x) }
                continue
            }
            var full = true
            for y in y0..<y1 where !vertical[y * width + x] {
                full = false
                break
            }
            if full && flatBeside(column: x, from: y0, to: y1) { hits.append(x) }
        }
        // A divider is a few adjacent columns; report the middle of each.
        var borders: [CGFloat] = []
        var start = hits.first ?? 0
        var last = start
        for x in hits.dropFirst() {
            if x - last > Int(lineHeight / 2) {
                borders.append(CGFloat(start + last) / 2)
                start = x
            }
            last = x
        }
        if !hits.isEmpty { borders.append(CGFloat(start + last) / 2) }
        return borders
    }

    /// Does a vertical border run anywhere through the span of two pieces
    /// of one row? The whole span, not only the gap: a misread piece can
    /// get a box that reaches back over the divider. The line height is the
    /// smaller piece's: a misread piece covering two lines made the span
    /// reach a horizontal divider, where the vertical one breaks off (both
    /// seen on a real ghostty screen).
    ///
    /// ContextCap: one line-height beyond on either side is enough here.
    /// Requiring both never separated the first line of a split pane: the
    /// ghostty divider starts at y=78, just above that line (y=80..122). The
    /// joined line then spans both panes and blocks `verticalCut`, so the
    /// panes' lines went back to interleaving. Using it for `split` too cut
    /// 4 more lines mid-word on 432 screens (a stock table row, a tweet, a
    /// diff row, a caption over a photo); here a wrong answer only leaves
    /// two pieces unjoined, as before this file existed.
    func separates(left: CGRect, right: CGRect) -> Bool {
        !verticalBorders(through: left.union(right),
                         lineHeight: min(left.height, right.height),
                         eitherSide: true).isEmpty
    }

    /// Edges in `column` through the whole height of `rect`, and on through
    /// the rest of `from..<to` above it or below it.
    private func borderAboveOrBelow(column x: Int, rect: CGRect, from y0: Int, to y1: Int) -> Bool {
        let top = max(y0, Int(rect.minY)), bottom = min(y1, Int(rect.maxY))
        func full(_ a: Int, _ b: Int) -> Bool {
            for y in a..<b where !vertical[y * width + x] { return false }
            return true
        }
        guard bottom > top, full(top, bottom) else { return false }
        return (y0 < top && full(y0, top) && flatBeside(column: x, from: y0, to: bottom))
            || (bottom < y1 && full(bottom, y1) && flatBeside(column: x, from: top, to: y1))
    }

    /// A vertical border somewhere between x0 and x1 that is drawn over most
    /// of y0...y1 (a pane divider, the edge of a sidebar).
    func verticalLine(x0: CGFloat, x1: CGFloat, y0: CGFloat, y1: CGFloat) -> Bool {
        let x0 = max(0, Int(x0) - 2), x1 = min(width, Int(x1) + 3)
        let y0 = max(0, Int(y0)), y1 = min(height, Int(y1))
        guard y1 > y0, x1 > x0 else { return false }
        let need = Int(Self.coverage * Double(y1 - y0))
        for x in x0..<x1 {
            var edges = 0
            for y in y0..<y1 where vertical[y * width + x] { edges += 1 }
            if edges >= need && flatBeside(column: x, from: y0, to: y1) { return true }
        }
        return false
    }

    private func flatBeside(row y: Int, from x0: Int, to x1: Int) -> Bool {
        let d = Self.isolationDistance
        return [y - d, y + d].contains { r in
            guard r >= 0, r < height else { return false }
            var edges = 0
            for x in x0..<x1 where horizontal[r * width + x] { edges += 1 }
            return Double(edges) < Self.isolationMax * Double(x1 - x0)
        }
    }

    private func flatBeside(column x: Int, from y0: Int, to y1: Int) -> Bool {
        let d = Self.isolationDistance
        return [x - d, x + d].contains { c in
            guard c >= 0, c < width else { return false }
            var edges = 0
            for y in y0..<y1 where vertical[y * width + c] { edges += 1 }
            return Double(edges) < Self.isolationMax * Double(y1 - y0)
        }
    }

    private func longestRun(row y: Int, from x0: Int, to x1: Int) -> Int {
        var best = 0
        var start = -1
        var lastHit = -1
        let base = y * width
        for x in x0..<x1 where horizontal[base + x] {
            if start < 0 || x - lastHit > Self.maxGap + 1 { start = x }
            lastHit = x
            best = max(best, lastHit - start + 1)
        }
        return best
    }
}

/// Cut a line at each x in `cuts`, assigning every character by the centre
/// of its own box. Vision returns per-character boxes for Japanese too;
/// whitespace comes back at x=0, so it stays with the current piece.
func split(_ line: Line, candidate: VNRecognizedText, at cuts: [CGFloat],
           imageWidth: CGFloat) -> [Line] {
    let text = candidate.string
    var pieces: [(text: String, minX: CGFloat)] = [("", line.rect.minX)]
    var nextCut = 0
    for i in text.indices {
        let range = i..<text.index(after: i)
        let char = text[range]
        if nextCut < cuts.count, !char.allSatisfy(\.isWhitespace),
           let box = try? candidate.boundingBox(for: range) {
            while nextCut < cuts.count, box.boundingBox.midX * imageWidth > cuts[nextCut] {
                pieces.append(("", cuts[nextCut]))
                nextCut += 1
            }
        }
        pieces[pieces.count - 1].text += char
    }
    var out: [Line] = []
    for (idx, piece) in pieces.enumerated() {
        let trimmed = piece.text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { continue }
        let maxX = idx + 1 < pieces.count ? pieces[idx + 1].minX : line.rect.maxX
        out.append(Line(text: trimmed,
                        rect: CGRect(x: piece.minX, y: line.rect.minY,
                                     width: maxX - piece.minX, height: line.rect.height)))
    }
    return out
}

/// Vision often returns one screen line as several pieces ("RIGHT01" and
/// the rest of the line). Grouping works on vertical gaps only, so such
/// pieces would land in different blocks; join pieces that share a row,
/// however far apart (line numbers and code, table cells), unless a border
/// runs between them. Without borders this keeps the old reading order,
/// where pieces of one row were adjacent lines.
func joinRows(_ input: [Line], borders: BorderMap?) -> [Line] {
    var rows: [Line] = []
    for line in input.sorted(by: { $0.rect.minX < $1.rect.minX }) {
        let index = rows.lastIndex { row in
            let h = max(row.rect.height, line.rect.height)
            let overlap = min(row.rect.maxY, line.rect.maxY) - max(row.rect.minY, line.rect.minY)
            return overlap > 0.5 * min(row.rect.height, line.rect.height)
                && line.rect.minX - row.rect.maxX > -0.5 * h
                && !(borders?.separates(left: row.rect, right: line.rect) ?? false)
        }
        if let index {
            rows[index].text += " " + line.text
            rows[index].rect = rows[index].rect.union(line.rect)
        } else {
            rows.append(line)
        }
    }
    return rows
}

private func horizontalOverlapRatio(_ a: CGRect, _ b: CGRect) -> CGFloat {
    let overlap = min(a.maxX, b.maxX) - max(a.minX, b.minX)
    let narrower = min(a.width, b.width)
    guard narrower > 0 else { return 0 }
    return max(0, overlap) / narrower
}

/// Lines join a block when they sit in the same column with a small gap
/// (relative to line height h), and never across a border.
func groupLines(_ input: [Line], borders: BorderMap?) -> [[Line]] {
    let lines = input
        .filter { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }
        .sorted { a, b in
            a.rect.minY != b.rect.minY ? a.rect.minY < b.rect.minY : a.rect.minX < b.rect.minX
        }
    var blocks: [[Line]] = []
    for line in lines {
        // Try every open block, not just the newest: lines from two columns
        // interleave in y-order, so a paragraph in the left column resumes
        // after right-column lines have been seen.
        var bestIndex: Int?
        var bestGap = CGFloat.greatestFiniteMagnitude
        for (idx, block) in blocks.enumerated() {
            guard let prev = block.last else { continue }
            let h = max(prev.rect.height, line.rect.height)
            let gap = line.rect.minY - prev.rect.maxY
            guard gap >= -h * 0.35, gap <= h * 1.3 else { continue }
            let overlap = horizontalOverlapRatio(prev.rect, line.rect)
            let leftAligned = abs(line.rect.minX - prev.rect.minX) < h * 1.2
            let rightAligned = abs(line.rect.maxX - prev.rect.maxX) < h * 1.2
            let blockLeft = block.map(\.rect.minX).min() ?? line.rect.minX
            let tight = gap <= h * 0.9 && (overlap > 0.2 || leftAligned || rightAligned)
            let loose = abs(line.rect.minX - blockLeft) < h * 0.6 && overlap > 0.15
            if (tight || loose) && gap < bestGap {
                if let borders, borders.separates(prev.rect, line.rect) { continue }
                bestGap = gap
                bestIndex = idx
            }
        }
        if let idx = bestIndex {
            blocks[idx].append(line)
        } else {
            blocks.append([line])
        }
    }
    return blocks
}

private func bounds(_ block: [Line]) -> CGRect {
    block.dropFirst().reduce(block[0].rect) { $0.union($1.rect) }
}

private func span(_ rects: [CGRect]) -> CGRect {
    rects.dropFirst().reduce(rects[0]) { $0.union($1) }
}

/// A gap in x that no block crosses, with a border drawn along the blocks
/// on at least one side: the blocks left of it, then the rest.
private func verticalCut(_ rects: [CGRect], _ borders: BorderMap) -> ([Int], [Int])? {
    guard rects.count > 1 else { return nil }
    let byX = rects.indices.sorted { rects[$0].minX < rects[$1].minX }
    var reach = rects[byX[0]].maxX
    for (n, i) in byX.enumerated().dropFirst() {
        let r = rects[i]
        if r.minX >= reach {
            let left = Array(byX[..<n]), right = Array(byX[n...])
            let l = span(left.map { rects[$0] }), rr = span(right.map { rects[$0] })
            if borders.verticalLine(x0: reach, x1: r.minX, y0: l.minY, y1: l.maxY)
                || borders.verticalLine(x0: reach, x1: r.minX, y0: rr.minY, y1: rr.maxY) {
                return (left, right)
            }
        }
        reach = max(reach, r.maxX)
    }
    return nil
}

/// Reading order: split the blocks into regions at a vertical border first
/// (panes side by side, a sidebar), then at a gap in y, and read the
/// regions one after another. A gap in y is used when a border runs across
/// it, or when it frees a vertical cut on one side (a tab bar above split
/// panes). Only lines drawn on the screen split regions, so chat bubbles on
/// alternating sides stay in time order. Inside a region, top to bottom;
/// blocks starting on the same row go left to right.
func readingOrder(_ blocks: [[Line]], borders: BorderMap?) -> [[Line]] {
    guard blocks.count > 1, let borders else { return topToBottom(blocks) }
    let rects = blocks.map(bounds)
    if let (left, right) = verticalCut(rects, borders) {
        return readingOrder(left.map { blocks[$0] }, borders: borders)
            + readingOrder(right.map { blocks[$0] }, borders: borders)
    }
    let all = span(rects)
    let byY = rects.indices.sorted { rects[$0].minY < rects[$1].minY }
    var bottom = rects[byY[0]].maxY
    for (n, i) in byY.enumerated().dropFirst() {
        let r = rects[i]
        if r.minY >= bottom {
            let upper = Array(byY[..<n]), lower = Array(byY[n...])
            if borders.horizontalLine(y0: bottom, y1: r.minY, x0: all.minX, x1: all.maxX)
                || verticalCut(upper.map { rects[$0] }, borders) != nil
                || verticalCut(lower.map { rects[$0] }, borders) != nil {
                return readingOrder(upper.map { blocks[$0] }, borders: borders)
                    + readingOrder(lower.map { blocks[$0] }, borders: borders)
            }
        }
        bottom = max(bottom, r.maxY)
    }
    return topToBottom(blocks)
}

private func topToBottom(_ blocks: [[Line]]) -> [[Line]] {
    blocks.sorted { a, b in
        let p = a[0].rect, q = b[0].rect
        if abs(p.minY - q.minY) >= min(p.height, q.height) { return p.minY < q.minY }
        return bounds(a).minX < bounds(b).minX
    }
}

/// Vision の結果を、保存する本文にする。まとまりの中は改行、まとまりの間は空行。
/// `lines` は空行を数えない（読めた行の数）。
func layoutText(_ observations: [VNRecognizedTextObservation],
                image: CGImage) -> (text: String, lines: Int) {
    let borders = BorderMap(image: image)
    let w = CGFloat(image.width), h = CGFloat(image.height)
    var lines: [Line] = []
    for observation in observations {
        guard let candidate = observation.topCandidates(1).first else { continue }
        let bb = observation.boundingBox
        let line = Line(text: candidate.string,
                        rect: CGRect(x: bb.minX * w, y: (1 - bb.maxY) * h,
                                     width: bb.width * w, height: bb.height * h))
        // Vision sometimes reads straight across a pane divider (6 of 3101
        // lines on 25 split-pane screens). Only long lines can span two
        // panes; skipping short ones keeps the column scan cheap.
        if let borders, line.rect.width >= line.rect.height * 6 {
            let cuts = borders.verticalBorders(through: line.rect)
            if !cuts.isEmpty {
                lines += split(line, candidate: candidate, at: cuts, imageWidth: w)
                continue
            }
        }
        lines.append(line)
    }
    let blocks = readingOrder(groupLines(joinRows(lines, borders: borders), borders: borders),
                              borders: borders)
    let text = blocks.map { $0.map(\.text).joined(separator: "\n") }.joined(separator: "\n\n")
    return (text, blocks.reduce(0) { $0 + $1.count })
}
