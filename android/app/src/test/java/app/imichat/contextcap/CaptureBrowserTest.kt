package app.imichat.contextcap

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureBrowserTest {

    private val root: File = Files.createTempDirectory("contextcap-browser").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun write(day: String, stem: String) {
        val dir = File(root, day).apply { mkdirs() }
        File(dir, "$stem.jpg").writeBytes(ByteArray(10))
    }

    @Test
    fun `空ディレクトリなら空リスト`() {
        assertEquals(emptyList(), CaptureBrowser.latest(root, 10))
    }

    @Test
    fun `新しい順に返る`() {
        write("2026-08-14", "100000_000")
        write("2026-08-14", "100010_000")
        write("2026-08-14", "100020_000")

        val got = CaptureBrowser.latest(root, 10).map { it.name }
        assertEquals(listOf("100020_000.jpg", "100010_000.jpg", "100000_000.jpg"), got)
    }

    @Test
    fun `日付ディレクトリを跨いで新しい順に返る`() {
        write("2026-08-13", "230000_000")
        write("2026-08-14", "010000_000")
        write("2026-08-15", "090000_000")

        val got = CaptureBrowser.latest(root, 10).map { it.parentFile!!.name + "/" + it.name }
        assertEquals(
            listOf(
                "2026-08-15/090000_000.jpg",
                "2026-08-14/010000_000.jpg",
                "2026-08-13/230000_000.jpg",
            ),
            got,
        )
    }

    @Test
    fun `limit を超えて返さない`() {
        write("2026-08-14", "100000_000")
        write("2026-08-14", "100010_000")
        write("2026-08-14", "100020_000")

        assertEquals(2, CaptureBrowser.latest(root, 2).size)
        assertEquals("100020_000.jpg", CaptureBrowser.latest(root, 2)[0].name)
    }

    @Test
    fun `limit が 0 以下なら空リスト`() {
        write("2026-08-14", "100000_000")
        assertEquals(emptyList(), CaptureBrowser.latest(root, 0))
        assertEquals(emptyList(), CaptureBrowser.latest(root, -1))
    }

    @Test
    fun `jpg 以外は無視する`() {
        write("2026-08-14", "100000_000")
        File(root, "2026-08-14/notes.txt").writeBytes(ByteArray(10))

        val got = CaptureBrowser.latest(root, 10).map { it.name }
        assertEquals(listOf("100000_000.jpg"), got)
    }

    @Test
    fun `gen 接尾辞があっても撮影時刻の順序で並ぶ`() {
        write("2026-08-14", "100000_000.g3")
        write("2026-08-14", "100010_000.g1")
        write("2026-08-14", "100020_000")

        val got = CaptureBrowser.latest(root, 10).map { it.name }
        assertEquals(
            listOf("100020_000.jpg", "100010_000.g1.jpg", "100000_000.g3.jpg"),
            got,
        )
    }

    @Test
    fun `古い日付ディレクトリは limit が満ちたら走査しない`() {
        // 最新の日付だけで limit が満ちる場合、古いディレクトリのファイルは結果に出ない
        write("2026-01-01", "100000_000")
        write("2026-08-14", "100000_000")
        write("2026-08-14", "100010_000")

        val got = CaptureBrowser.latest(root, 2).map { it.parentFile!!.name }
        assertTrue(got.all { it == "2026-08-14" }, "古い日付を走査してしまっている: $got")
    }
}
