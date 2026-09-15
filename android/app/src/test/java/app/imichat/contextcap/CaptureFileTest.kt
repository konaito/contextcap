package app.imichat.contextcap

import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureFileTest {

    @Test
    fun `接尾辞のないファイルは gen0`() {
        assertEquals(0, CaptureFile.generationOf(File("/root/2026-08-13/162303_273.jpg")))
    }

    @Test
    fun `g2 接尾辞から世代を読む`() {
        assertEquals(2, CaptureFile.generationOf(File("/root/2026-08-13/162303_273.g2.jpg")))
    }

    @Test
    fun `基底名は gen 接尾辞を除いたもの`() {
        assertEquals("162303_273", CaptureFile.baseNameOf(File("/root/2026-08-13/162303_273.g1.jpg")))
        assertEquals("162303_273", CaptureFile.baseNameOf(File("/root/2026-08-13/162303_273.jpg")))
    }

    @Test
    fun `撮影時刻をパスから解釈する`() {
        val date = CaptureFile.captureDateOf(File("/root/2026-08-13/162303_273.g2.jpg"))
        val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply { time = date!! }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(7, cal.get(Calendar.MONTH)) // 0-indexed なので 8 月は 7
        assertEquals(13, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(16, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(23, cal.get(Calendar.MINUTE))
        assertEquals(3, cal.get(Calendar.SECOND))
        assertEquals(273, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `規約外の名前は null`() {
        assertNull(CaptureFile.captureDateOf(File("/root/notadate/whatever.jpg")))
    }

    @Test
    fun `日付ディレクトリ名と時刻ステムを生成できる`() {
        val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply {
            set(2026, 7, 13, 16, 23, 3)
            set(Calendar.MILLISECOND, 273)
        }
        assertEquals("2026-08-13", CaptureFile.dayDirName(cal.time))
        assertEquals("162303_273", CaptureFile.timeStem(cal.time))
    }

    @Test
    fun `生成した名前を読み戻せる`() {
        val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply {
            set(2026, 7, 13, 16, 23, 3)
            set(Calendar.MILLISECOND, 273)
        }
        val file = File("/root/${CaptureFile.dayDirName(cal.time)}/${CaptureFile.timeStem(cal.time)}.jpg")
        assertEquals(cal.time.time, CaptureFile.captureDateOf(file)!!.time)
    }
}
