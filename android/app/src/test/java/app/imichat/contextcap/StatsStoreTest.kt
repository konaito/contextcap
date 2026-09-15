package app.imichat.contextcap

import java.io.File
import java.nio.file.Files
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsStoreTest {

    private val root: File = Files.createTempDirectory("contextcap-stats").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun write(day: String, stem: String, bytes: Int) {
        val dir = File(root, day).apply { mkdirs() }
        File(dir, "$stem.jpg").writeBytes(ByteArray(bytes))
    }

    @Test
    fun `空ディレクトリは 0 枚`() {
        val stats = StatsStore(root)
        assertEquals(0, stats.count)
        assertEquals(0L, stats.totalBytes)
        assertEquals(null, stats.firstDate)
    }

    @Test
    fun `フルスキャンで枚数と容量を数える`() {
        write("2026-08-13", "100000_000", 100)
        write("2026-08-13", "100010_000", 200)
        write("2026-08-14", "100020_000", 300)

        val stats = StatsStore(root)
        assertEquals(3, stats.count)
        assertEquals(600L, stats.totalBytes)
    }

    @Test
    fun `jpg 以外は数えない`() {
        write("2026-08-13", "100000_000", 100)
        File(root, "2026-08-13/notes.txt").writeBytes(ByteArray(999))

        val stats = StatsStore(root)
        assertEquals(1, stats.count)
        assertEquals(100L, stats.totalBytes)
    }

    @Test
    fun `最初と最後の撮影時刻をパスから求める`() {
        write("2026-08-13", "100000_000", 10)
        write("2026-08-15", "230000_000", 10)
        write("2026-08-14", "120000_000", 10)

        val stats = StatsStore(root)
        assertEquals(
            CaptureFile.captureDateOf(File(root, "2026-08-13/100000_000.jpg"))!!.time,
            stats.firstDate!!.time,
        )
        assertEquals(
            CaptureFile.captureDateOf(File(root, "2026-08-15/230000_000.jpg"))!!.time,
            stats.lastDate!!.time,
        )
    }

    @Test
    fun `増分更新で枚数と容量が増える`() {
        write("2026-08-13", "100000_000", 100)
        val stats = StatsStore(root)
        stats.recordCapture(bytes = 50, date = Date())

        assertEquals(2, stats.count)
        assertEquals(150L, stats.totalBytes)
    }

    @Test
    fun `記録期間は日と時間で表示する`() {
        write("2026-08-13", "100000_000", 10)
        write("2026-08-16", "140000_000", 10)

        val stats = StatsStore(root)
        assertEquals("3日と4時間", stats.durationText)
    }

    @Test
    fun `記録が 1 枚だけなら期間はダッシュ`() {
        write("2026-08-13", "100000_000", 10)
        assertEquals("—", StatsStore(root).durationText)
    }
}
