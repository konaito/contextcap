package app.imichat.contextcap

import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RemoteServerTest {

    private val root: File = Files.createTempDirectory("contextcap-remote").toFile()
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private var server: RemoteServer? = null

    @AfterTest
    fun cleanup() {
        server?.shutdown()
        root.deleteRecursively()
    }

    private fun write(day: String, name: String, bytes: ByteArray = ByteArray(10)) {
        File(root, day).apply { mkdirs() }.resolve(name).writeBytes(bytes)
    }

    private fun serve(address: () -> InetAddress? = { loopback }): RemoteServer {
        val s = RemoteServer(root, 0, address)
        server = s
        return s
    }

    @Test
    fun `root の外には解決しない`() {
        write("2026-10-21", "120000_000.jpg")
        val s = serve()
        assertNull(s.resolve("/.."))
        assertNull(s.resolve("/2026-10-21/../.."))
        assertNull(s.resolve("/%2e%2e/etc"))
        assertNull(s.resolve("/2026-10-21/..%2F..%2Fetc"))
        assertNull(s.resolve("2026-10-21/120000_000.jpg"))
        assertNotNull(s.resolve("/2026-10-21/120000_000.jpg"))
        assertEquals(root, s.resolve("/"))
    }

    @Test
    fun `書き込み途中の一時ファイルは見せない`() {
        write("2026-10-21", "120000_000.jpg.tmp")
        write("2026-10-21", "120010_000.jpg", ByteArray(3))
        val s = serve()
        assertNull(s.resolve("/2026-10-21/120000_000.jpg.tmp"))
        assertEquals("120010_000.jpg\t3\n", s.listing(File(root, "2026-10-21")))
    }

    @Test
    fun `ルートの一覧は日付ディレクトリを名前順に返す`() {
        write("2026-10-21", "a.jpg")
        write("2026-10-20", "a.jpg")
        assertEquals("2026-10-20/\n2026-10-21/\n", serve().listing(root))
    }

    // --- ソケット越し。待ち受けは refresh（start 直後に 1 回走る）が非同期で張る ---

    private fun startOnLoopback(): Int {
        // ポート 0 にすると実際のポートを外から知る口が無いので、空きポートを先に取る
        val port = java.net.ServerSocket(0).use { it.localPort }
        val s = RemoteServer(root, port, { loopback })
        server = s
        s.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (s.boundAddress == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(loopback, s.boundAddress)
        return port
    }

    private fun get(port: Int, path: String): Pair<Int, ByteArray> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        return try {
            val code = conn.responseCode
            val body = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            code to (body ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `GET でファイルの中身と一覧が取れる`() {
        val jpeg = ByteArray(5000) { it.toByte() }
        write("2026-10-21", "120000_000.jpg", jpeg)
        val ocr = ("""{"t":"120000_000","text":"あ"}""" + "\n").toByteArray()
        write("2026-10-21", "ocr.jsonl", ocr)
        val port = startOnLoopback()

        val (rootCode, rootBody) = get(port, "/")
        assertEquals(200, rootCode)
        assertEquals("2026-10-21/\n", rootBody.decodeToString())

        val (dayCode, dayBody) = get(port, "/2026-10-21/")
        assertEquals(200, dayCode)
        assertEquals("120000_000.jpg\t5000\nocr.jsonl\t${ocr.size}\n", dayBody.decodeToString())

        val (imgCode, imgBody) = get(port, "/2026-10-21/120000_000.jpg")
        assertEquals(200, imgCode)
        assertEquals(jpeg.toList(), imgBody.toList())

        val (ocrCode, ocrBody) = get(port, "/2026-10-21/ocr.jsonl?x=1")
        assertEquals(200, ocrCode)
        assertEquals("""{"t":"120000_000","text":"あ"}""" + "\n", ocrBody.decodeToString())

        assertEquals(404, get(port, "/2026-10-22/").first)
        assertEquals(404, get(port, "/../").first)
    }

    @Test
    fun `GET 以外は受けない`() {
        val port = startOnLoopback()
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("DELETE /2026-10-21/ HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val status = s.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 405 Method Not Allowed", status)
        }
    }

    @Test
    fun `アドレスが無ければ待ち受けない`() {
        val s = serve { null }
        s.start()
        Thread.sleep(200)
        assertNull(s.boundAddress)
    }
}
