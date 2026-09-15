package app.imichat.contextcap

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetentionTest {
    private lateinit var root: File

    private val dayMs = 24L * 60 * 60 * 1000
    /// 固定時刻。テストを実行日に依存させない。
    /// 日付文字列は必ずこの値から導出する（定数を直接書くと、ズレていても気づけない）
    private val now = 1_786_060_800_000L

    /// now から n 日前の日付ディレクトリ名
    private fun day(daysAgo: Int): String =
        CaptureFile.dayDirName(java.util.Date(now - daysAgo * dayMs))

    @BeforeTest
    fun setUp() {
        root = File.createTempFile("retention", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    /// `<root>/<day>/<stem>.jpg` を size バイトで作る
    private fun jpg(day: String, stem: String, size: Int = 100): File {
        val dir = File(root, day).apply { mkdirs() }
        return File(dir, "$stem.jpg").apply { writeBytes(ByteArray(size)) }
    }

    /// その日の ocr.jsonl に「OCR 済み」の 1 行を足す
    private fun markIndexed(day: String, timeStem: String) {
        OcrLog.append(
            File(root, day),
            OcrLog.Entry(
                timeStem = timeStem, gen = 0, width = 1080, height = 2400,
                bytes = 100, lines = 10, ms = 300, status = "ok", text = "hello",
            ),
        )
    }

    private fun retention(budgetBytes: Long = 1_000_000, days: Int = 14) =
        Retention(root, budgetBytes = budgetBytes, retentionDays = days, now = { now })

    @Test
    fun `保持期間内なら何もしない`() {
        jpg(day(1), "120000_000")
        markIndexed(day(1), "120000_000")
        assertNull(retention().sweep())
    }

    @Test
    fun `保持期間を過ぎた OCR 済みは消える`() {
        val old = jpg(day(20), "120000_000")
        markIndexed(day(20), "120000_000")
        val result = retention().sweep()!!
        assertEquals(1, result.deleted)
        assertFalse(old.exists())
    }

    @Test
    fun `未 OCR は保持期間を過ぎても消さない`() {
        val old = jpg(day(20), "120000_000")
        // markIndexed を呼ばない = 未 OCR
        val result = retention().sweep()!!
        assertEquals(0, result.deleted)
        assertEquals(1, result.blockedByOcr)
        assertTrue(old.exists(), "未 OCR の画像を消したらテキストごと永久に失われる")
    }

    @Test
    fun `未 OCR と OCR 済みが混ざっていても OCR 済みだけ消える`() {
        val indexed = jpg(day(20), "120000_000")
        val notIndexed = jpg(day(20), "130000_000")
        markIndexed(day(20), "120000_000")

        val result = retention().sweep()!!
        assertEquals(1, result.deleted)
        assertEquals(1, result.blockedByOcr)
        assertFalse(indexed.exists())
        assertTrue(notIndexed.exists())
    }

    @Test
    fun `保持期間内でも上限を超えたら古い順に消す`() {
        // どれも保持期間内。合計 300 バイトに対して上限 150 バイト
        val a = jpg(day(3), "120000_000", size = 100)
        val b = jpg(day(2), "120000_000", size = 100)
        val c = jpg(day(1), "120000_000", size = 100)
        listOf(day(3), day(2), day(1)).forEach { markIndexed(it, "120000_000") }

        val result = Retention(root, budgetBytes = 150, retentionDays = 14, now = { now }).sweep()!!
        assertEquals(2, result.deleted)
        assertFalse(a.exists(), "古い方から消える")
        assertFalse(b.exists())
        assertTrue(c.exists(), "新しい方が残る")
    }

    @Test
    fun `旧世代の gN 接尾辞が付いていても同一性を保って消せる`() {
        // 段階圧縮を廃止する前に作られたファイル。ocr.jsonl 側は接尾辞なしの基底名で持つ
        val old = jpg(day(20), "120000_000.g1")
        markIndexed(day(20), "120000_000")

        val result = retention().sweep()!!
        assertEquals(1, result.deleted)
        assertFalse(old.exists())
    }

    @Test
    fun `jpg が消えても jsonl は残す`() {
        jpg(day(20), "120000_000")
        markIndexed(day(20), "120000_000")
        retention().sweep()
        assertTrue(
            File(File(root, day(20)), OcrLog.FILE_NAME).exists(),
            "テキストは画像が無くても正解データになるので消さない",
        )
    }
}
