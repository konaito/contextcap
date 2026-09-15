import Foundation

/// 「情報のない画面」の判定。DRM 保護で塗り潰された画面と、消灯直前の暗い画面が
/// 同じ形になるので、原因ではなく結果で捉える。
///
/// **黒いピクセルの割合では判定できない。** 真っ黒地に白文字のダークモード画面は
/// 9 割以上のピクセルが黒く、割合だけ見ると保護画面と区別が付かない。
/// 違うのは黒の連続性で、保護画面は広い面が丸ごと黒く、ダークモードは文字が全面に散る。
/// そこで格子に割って「セルごと真っ黒か」を数える。
///
/// Android の `FrameBlackness.kt` と**二重実装**。定数と判定結果を必ず一致させる
/// （回収後の解析スクリプトが両プラットフォームに効かなくなるため）。
/// 契約のテストは Kotlin 側 `FrameBlacknessTest` が正本。
enum FrameBlackness {

    /// 格子のセルの一辺（縮小後のピクセル）
    private static let cell = 8

    /// このセル内の最大輝度を超える点が 1 つでもあれば、そのセルは黒くない
    private static let blackLumaMax = 16

    /// 情報なしと見なす黒セルの割合。macOS ではメニューバーと Dock が
    /// 保護画面でも写るので、その分の余裕を見て 1.0 にはしない
    private static let blackCellRatio = 0.85

    /// ARGB のピクセル列に対する判定。Android 版と同じ純ロジック
    static func isUninformative(pixels: [UInt32], width: Int, height: Int) -> Bool {
        guard width > 0, height > 0, pixels.count >= width * height else { return false }

        var cells = 0
        var blackCells = 0
        var top = 0
        while top < height {
            var left = 0
            while left < width {
                cells += 1
                if isBlackCell(
                    pixels, width: width,
                    left: left, top: top,
                    right: min(left + cell, width), bottom: min(top + cell, height)
                ) {
                    blackCells += 1
                }
                left += cell
            }
            top += cell
        }
        return cells > 0 && Double(blackCells) / Double(cells) >= blackCellRatio
    }

    private static func isBlackCell(
        _ pixels: [UInt32], width: Int, left: Int, top: Int, right: Int, bottom: Int
    ) -> Bool {
        for y in top..<bottom {
            let row = y * width
            for x in left..<right where luma(pixels[row + x]) > blackLumaMax {
                return false
            }
        }
        return true
    }

    /// ITU-R BT.601 の輝度。Android 版と同じく整数のまま計算する
    private static func luma(_ argb: UInt32) -> Int {
        let r = Int((argb >> 16) & 0xFF)
        let g = Int((argb >> 8) & 0xFF)
        let b = Int(argb & 0xFF)
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
