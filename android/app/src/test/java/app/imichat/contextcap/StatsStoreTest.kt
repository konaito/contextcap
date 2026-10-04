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
    fun `削除分を差し引くとフルスキャンし直した値と一致する`() {
        write("2026-08-13", "100000_000", 100)
        write("2026-08-13", "100010_000", 200)
        write("2026-08-14", "100020_000", 300)
        val stats = StatsStore(root)

        File(root, "2026-08-13/100000_000.jpg").delete()
        stats.recordDeletion(count = 1, bytes = 100)

        val rescanned = StatsStore(root)
        assertEquals(rescanned.count, stats.count)
        assertEquals(rescanned.totalBytes, stats.totalBytes)
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
    fun `gN 接尾辞付きと形式外の名前が混ざっても最初と最後を正しく求める`() {
        // 最古は旧世代の .g1。文字列比較は接尾辞を除いた基底名で行う必要がある
        write("2026-08-13", "100000_000.g1", 10)
        write("2026-08-14", "120000_000", 10)
        // 形式外の名前は従来どおりファイルの更新時刻で扱う。最新になるよう未来の時刻にする
        val odd = File(root, "2026-08-14/manual-copy.jpg").apply { writeBytes(ByteArray(10)) }
        val future = CaptureFile.captureDateOf(File(root, "2026-08-20/000000_000.jpg"))!!.time
        odd.setLastModified(future)

        val stats = StatsStore(root)
        assertEquals(3, stats.count)
        assertEquals(
            CaptureFile.captureDateOf(File(root, "2026-08-13/100000_000.g1.jpg"))!!.time,
            stats.firstDate!!.time,
        )
        assertEquals(future, stats.lastDate!!.time)
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
