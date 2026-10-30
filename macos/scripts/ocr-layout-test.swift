// OCRLayout の境目検出と並べ方を、合成画像と作った行で確かめる。Vision は使わない。
// macOS 側にテストターゲットが無いので、対象ソースを直接コンパイルして叩く。
import CoreGraphics
import Foundation

@main
struct OCRLayoutTest {
    static var failures = 0

    static func check(_ ok: Bool, _ name: String) {
        print(ok ? "ok   \(name)" : "FAIL \(name)")
        if !ok { failures += 1 }
    }

    /// ghostty の分割画面と同じ色: 背景 #282c34、x = 199...200 に 2px の境目 (25,26,33)
    /// `dividerTop` は画像の上端からの距離。そこから下にだけ境目を描く
    static func splitScreen(width: Int = 400, height: Int = 200, divider: Bool = true,
                            dividerTop: Int = 0) -> CGImage {
        let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                            bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        ctx.setFillColor(red: 40 / 255, green: 44 / 255, blue: 52 / 255, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        if divider {
            ctx.setFillColor(red: 25 / 255, green: 26 / 255, blue: 33 / 255, alpha: 1)
            // CGContext の原点は左下
            ctx.fill(CGRect(x: 199, y: 0, width: 2, height: height - dividerTop))
        }
        return ctx.makeImage()!
    }

    static func line(_ text: String, x: CGFloat, y: CGFloat, width: CGFloat = 160) -> Line {
        Line(text: text, rect: CGRect(x: x, y: y, width: width, height: 16))
    }

    static func text(_ lines: [Line], _ borders: BorderMap?) -> String {
        readingOrder(groupLines(joinRows(lines, borders: borders), borders: borders), borders: borders)
            .map { $0.map(\.text).joined(separator: "\n") }
            .joined(separator: "\n\n")
    }

    static func main() {
        let borders = BorderMap(image: splitScreen())!
        let plain = BorderMap(image: splitScreen(divider: false))!

        let cuts = borders.verticalBorders(through: CGRect(x: 20, y: 90, width: 360, height: 16))
        check(cuts.count == 1 && abs(cuts[0] - 200) <= 2, "2px の境目を 1 か所だけ見つける（\(cuts)）")
        check(plain.verticalBorders(through: CGRect(x: 20, y: 90, width: 360, height: 16)).isEmpty,
              "境目の無い画面では行を割らない")

        // 左右のペインに 3 行ずつ。旧来の並べ方だと L1 R1 L2 R2 ... と交互になる
        let panes = [
            line("L1", x: 10, y: 20), line("R1", x: 210, y: 20),
            line("L2", x: 10, y: 40), line("R2", x: 210, y: 40),
            line("L3", x: 10, y: 60), line("R3", x: 210, y: 60),
        ]
        let byPane = text(panes, borders)
        check(byPane == "L1\nL2\nL3\n\nR1\nR2\nR3",
              "境目があればペインごとに並べ、間に空行を入れる（\(byPane.debugDescription)）")

        // Vision が 1 行を破片で返したとき、境目が無ければ 1 行につなぐ
        let pieces = [line("RIGHT01", x: 10, y: 20, width: 60), line("rest", x: 80, y: 20, width: 100)]
        check(text(pieces, plain) == "RIGHT01 rest", "同じ高さの破片は 1 行につなぐ")
        let across = [line("left", x: 10, y: 90, width: 170), line("right", x: 220, y: 90, width: 170)]
        check(text(across, borders) == "left\n\nright", "境目をまたぐ破片はつながない")

        // ペインの最上段: 境目は行の 2px 上から始まり、行の上には無い（ghostty で y=78 と y=80）
        let fromTop = BorderMap(image: splitScreen(dividerTop: 30))!
        let firstRow = [line("left", x: 10, y: 32, width: 170), line("right", x: 220, y: 32, width: 170)]
        check(text(firstRow, fromTop) == "left\n\nright", "ペインの最上段でも境目をまたいでつながない")

        print(failures == 0 ? "all passed" : "\(failures) failed")
        exit(failures == 0 ? 0 : 1)
    }
}
