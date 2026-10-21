package app.imichat.contextcap

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OcrLogTest {

    private val dayDir: File = Files.createTempDirectory("contextcap-ocrlog").toFile()

    @AfterTest
    fun cleanup() {
        dayDir.deleteRecursively()
    }

    private fun entry(stem: String, text: String, status: String = "ok", err: String? = null) =
        OcrLog.Entry(
            timeStem = stem, gen = 0, width = 1080, height = 2364, bytes = 127131,
            lines = text.count { it == '\n' } + 1, ms = 1040, status = status, text = text, err = err,
        )

    @Test
    fun `書いた行をそのまま読み戻せる`() {
        val tricky = "改行\nと\"引用符\"と\\と\tタブと\u0001制御文字"
        val written = entry("000009_341", tricky, status = "failed", err = "理由: \"x\"")
        OcrLog.append(dayDir, written)

        assertEquals(listOf(written), OcrLog.readDay(dayDir))
    }

    @Test
    fun `実機の 1 行を読める`() {
        val line = """{"t":"000009_341","gen":0,"w":1080,"h":2364,"bytes":127131,"lines":2,""" +
            """"ms":1040,"status":"ok","text":"0:00 m\n投稿者:参 Sudoku"}"""
        val parsed = OcrLog.parseLine(line)!!
        assertEquals("000009_341", parsed.timeStem)
        assertEquals(2364, parsed.height)
        assertEquals("0:00 m\n投稿者:参 Sudoku", parsed.text)
        assertNull(parsed.err)
    }

    @Test
    fun `途中で切れた行は捨てる`() {
        assertNull(OcrLog.parseLine("""{"t":"000009_341","gen":0,"status":"ok","text":"途中"""))
        assertNull(OcrLog.parseLine(""))
    }

    @Test
    fun `同じ時刻が重複していたら後の行を残し、時刻順に並べる`() {
        OcrLog.append(dayDir, entry("120000_000", "後の時刻"))
        OcrLog.append(dayDir, entry("090000_000", "古い読み"))
        OcrLog.append(dayDir, entry("090000_000", "新しい読み"))
        File(dayDir, OcrLog.FILE_NAME).appendText("""{"t":"130000_000","sta""")

        val stems = OcrLog.readDay(dayDir).map { it.timeStem to it.text }
        assertEquals(listOf("090000_000" to "新しい読み", "120000_000" to "後の時刻"), stems)
    }

    @Test
    fun `TextArchive は ocr_jsonl のある日だけを新しい順に返す`() {
        val root = dayDir
        File(root, "2026-08-14").mkdirs()
        OcrLog.append(File(root, "2026-08-14"), entry("000000_000", "a"))
        File(root, "2026-08-15").mkdirs() // テキストなし
        File(root, "2026-10-21").mkdirs()
        OcrLog.append(File(root, "2026-10-21"), entry("000000_000", "b"))

        val days = TextArchive.days(root)
        assertEquals(listOf("2026-10-21", "2026-08-14"), days.map { it.name })
        assertEquals("2 日分（2026-08-14〜）", TextArchive.spanText(days))
        assertEquals("—", TextArchive.spanText(emptyList()))
    }

    @Test
    fun `同じ文字が続く間は 1 つの区間にまとめ、画像は残っている最新の 1 枚を指す`() {
        val entries = listOf(
            entry("090000_000", "A"),
            entry("090005_000", "A"),
            entry("090010_000", "", status = "empty"),
            entry("090015_000", "A"),
            entry("090020_000", "B"),
            entry("090025_000", "x", status = "failed"),
        )
        val passages = TextArchive.passages(entries, imageStems = setOf("090005_000"))
        assertEquals(
            listOf(
                TextArchive.Passage("090000_000", "090015_000", 3, "A", "090005_000"),
                TextArchive.Passage("090020_000", "090020_000", 1, "B", null),
            ),
            passages,
        )
        assertEquals("09:00:15", TextArchive.clockText("090015_000"))
    }
}
