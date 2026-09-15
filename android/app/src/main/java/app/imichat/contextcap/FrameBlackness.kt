package app.imichat.contextcap

/// 「情報のない画面」の判定。DRM 保護（`FLAG_SECURE`）で塗り潰された画面と、
/// 時計だけが光っている AOD が同じ形になるので、原因ではなく結果で捉える。
///
/// **黒いピクセルの割合では判定できない。** 真っ黒地に白文字のダークモード画面は
/// 9 割以上のピクセルが黒く、割合だけ見ると保護画面と区別が付かない。
/// 違うのは黒の連続性で、保護画面は広い面が丸ごと黒く、ダークモードは文字が全面に散る。
/// そこで格子に割って「セルごと真っ黒か」を数える。
///
/// 縮小済みの ARGB ピクセル列を受け取る純ロジック。Android に依存しないので unit test で検証する。
object FrameBlackness {

    /// 格子のセルの一辺（縮小後のピクセル）
    private const val CELL = 8

    /// このセル内の最大輝度を超える点が 1 つでもあれば、そのセルは黒くない
    private const val BLACK_LUMA_MAX = 16

    /// 情報なしと見なす黒セルの割合。ステータスバーとナビゲーションバーは
    /// 保護画面でも通常どおり写るので、その分の余裕を見て 1.0 にはしない
    private const val BLACK_CELL_RATIO = 0.85

    fun isUninformative(pixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return false

        var cells = 0
        var blackCells = 0
        var top = 0
        while (top < height) {
            var left = 0
            while (left < width) {
                cells += 1
                if (isBlackCell(pixels, width, left, top, minOf(left + CELL, width), minOf(top + CELL, height))) {
                    blackCells += 1
                }
                left += CELL
            }
            top += CELL
        }
        return cells > 0 && blackCells.toDouble() / cells >= BLACK_CELL_RATIO
    }

    private fun isBlackCell(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        for (y in top until bottom) {
            val row = y * width
            for (x in left until right) {
                if (luma(pixels[row + x]) > BLACK_LUMA_MAX) return false
            }
        }
        return true
    }

    /// ITU-R BT.601 の輝度。整数のまま計算する
    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
