package app.imichat.contextcap

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// 判定は縮小済みの ARGB ピクセル列に対して行う。
/// ここでは 1080x2400 を 1/8 に縮めた程度（135x300）を想定したサイズで組む。
class FrameBlacknessTest {

    private val width = 135
    private val height = 300

    private fun canvas(color: Int = BLACK) = IntArray(width * height) { color }

    private fun IntArray.fillRows(from: Int, to: Int, color: Int) {
        for (y in from until to) {
            for (x in 0 until width) this[y * width + x] = color
        }
    }

    private fun IntArray.fillRect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in y0 until y1) {
            for (x in x0 until x1) this[y * width + x] = color
        }
    }

    @Test
    fun `全面が真っ黒なら情報なし`() {
        assertTrue(FrameBlackness.isUninformative(canvas(), width, height))
    }

    @Test
    fun `通常の明るい画面は情報あり`() {
        assertFalse(FrameBlackness.isUninformative(canvas(WHITE), width, height))
    }

    @Test
    fun `黒の上下にステータスバーとナビゲーションバーが残っていても情報なし`() {
        // FLAG_SECURE のウィンドウはこの形になる（Android 16 で実測）。
        // 端の帯だけが写り、残りは真っ黒
        val px = canvas()
        px.fillRows(0, 13, GRAY) // ステータスバー相当（全体の約 4%）
        px.fillRows(height - 8, height, GRAY) // ナビゲーションバー相当
        assertTrue(FrameBlackness.isUninformative(px, width, height))
    }

    @Test
    fun `真っ黒地に文字が散っているダークモードの画面は情報あり`() {
        // 黒いピクセルの割合だけで判定すると、これを誤って捨てる
        val px = canvas()
        for (row in 2 until 36) {
            for (col in 1 until 16) {
                px.fillRect(col * 8, row * 8, col * 8 + 5, row * 8 + 2, WHITE)
            }
        }
        assertFalse(FrameBlackness.isUninformative(px, width, height))
    }

    @Test
    fun `黒の中に映像が写っていれば情報あり`() {
        val px = canvas()
        px.fillRect(0, 80, width, 220, GRAY)
        assertFalse(FrameBlackness.isUninformative(px, width, height))
    }

    @Test
    fun `わずかに沈んだ黒も黒として扱う`() {
        // JPEG のブロックノイズで完全な 0 にはならない
        assertTrue(FrameBlackness.isUninformative(canvas(NEAR_BLACK), width, height))
    }

    companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val NEAR_BLACK = 0xFF060606.toInt()
        private const val GRAY = 0xFF808080.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}
