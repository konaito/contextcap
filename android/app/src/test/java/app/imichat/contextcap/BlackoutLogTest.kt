package app.imichat.contextcap

import java.io.File
import java.nio.file.Files
import java.util.Calendar
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BlackoutLogTest {

    private val root: File = Files.createTempDirectory("contextcap-blackout").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun date(day: Int, hour: Int, minute: Int): Date {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(2026, Calendar.AUGUST, day, hour, minute, 0)
        return cal.time
    }

    private fun dayDir(date: Date): File =
        File(root, CaptureFile.dayDirName(date)).apply { mkdirs() }

    private fun record(log: BlackoutLog, date: Date, uninformative: Boolean) {
        log.record(dayDir(date), date, CaptureFile.timeStem(date), uninformative)
    }

    private fun lines(date: Date): List<String> {
        val file = File(dayDir(date), BlackoutLog.FILE_NAME)
        return if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
    }

    @Test
    fun `情報のある画面が続く間はファイルを作らない`() {
        val log = BlackoutLog()
        val d = date(16, 10, 0)
        record(log, d, uninformative = false)
        record(log, date(16, 10, 1), uninformative = false)
        assertFalse(File(dayDir(d), BlackoutLog.FILE_NAME).exists())
    }

    @Test
    fun `黒に入った時刻を start として残す`() {
        val log = BlackoutLog()
        record(log, date(16, 10, 0), uninformative = false)
        val start = date(16, 10, 1)
        record(log, start, uninformative = true)
        assertEquals(
            listOf("""{"t":"${CaptureFile.timeStem(start)}","state":"start"}"""),
            lines(start),
        )
    }

    @Test
    fun `黒が続いても行は増えない`() {
        val log = BlackoutLog()
        val start = date(16, 10, 0)
        record(log, start, uninformative = true)
        record(log, date(16, 10, 1), uninformative = true)
        record(log, date(16, 10, 2), uninformative = true)
        assertEquals(1, lines(start).size)
    }

    @Test
    fun `黒から抜けた時刻を end として残す`() {
        val log = BlackoutLog()
        record(log, date(16, 10, 0), uninformative = true)
        val end = date(16, 11, 0)
        record(log, end, uninformative = false)
        assertEquals(
            """{"t":"${CaptureFile.timeStem(end)}","state":"end"}""",
            lines(end).last(),
        )
    }

    @Test
    fun `最初の記録が黒でも start を残す`() {
        val log = BlackoutLog()
        val start = date(16, 10, 0)
        record(log, start, uninformative = true)
        assertEquals(1, lines(start).size)
    }

    @Test
    fun `日付をまたいで黒が続いたら翌日のファイルにも start を残す`() {
        // 日ごとのファイルだけ見れば区間が復元できるようにする
        val log = BlackoutLog()
        val first = date(16, 23, 59)
        record(log, first, uninformative = true)
        val next = date(17, 0, 0)
        record(log, next, uninformative = true)
        assertEquals(1, lines(first).size)
        assertEquals(
            listOf("""{"t":"${CaptureFile.timeStem(next)}","state":"start"}"""),
            lines(next),
        )
    }
}
