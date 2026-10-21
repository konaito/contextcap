package app.imichat.contextcap

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/// 撮影データを tailnet 上の PC から読むための、読み取り専用の HTTP サーバー。
/// `adb` もワイヤレスデバッグも無しに `curl` で読めるようにするのが目的。
///
///   GET /                       → 日付ディレクトリ名を 1 行ずつ
///   GET /2026-10-21/            → そのディレクトリのファイルを `名前<TAB>バイト数` で 1 行ずつ
///   GET /2026-10-21/ocr.jsonl   → ファイルの中身そのまま
///
/// **書き込み・削除の口は持たない。** 端末側の保存形式をそのまま見せるだけなので、
/// 回収後の解析は `adb pull` したものと同じ手順で扱える。
///
/// 待ち受けるのは `addressProvider` が返すアドレス（Tailscale のアドレス）だけ。
/// 0.0.0.0 で受けて接続元で弾く方式は採らない。Wi-Fi やモバイル回線にポートが開くため。
/// Tailscale は後から繋がったりアドレスが変わったりするので、`CHECK_INTERVAL_MS` ごとに
/// 確かめて張り直す。
///
/// 撮影・OCR・削除のスレッドとは executor を共有しない。大きい画像の転送で撮影が詰まらないように。
/// `android.util.Log` は使わない（JVM unit test から起動して叩くため）。ログは `log` に渡す。
class RemoteServer(
    private val root: File,
    private val port: Int,
    private val addressProvider: () -> InetAddress?,
    private val log: (String) -> Unit = {},
) {
    /// 張り直しの確認。アドレスの判定もここで行う
    private val control: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    /// 1 接続 1 タスク。一覧と画像取得を並べられるだけあればいい
    private val handlers = Executors.newFixedThreadPool(HANDLER_THREADS)

    @Volatile
    private var socket: ServerSocket? = null

    fun start() {
        control.scheduleWithFixedDelay(::refresh, 0, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun shutdown() {
        stopped = true
        control.shutdownNow()
        closeSocket()
        handlers.shutdownNow()
    }

    /// shutdown と refresh が同時に走ると、閉じた直後に張り直されることがあるので見る
    @Volatile
    private var stopped = false

    /// 今待ち受けているアドレス。待ち受けていなければ null
    val boundAddress: InetAddress?
        get() = socket?.takeUnless { it.isClosed }?.inetAddress

    private fun refresh() {
        val address = addressProvider()
        val current = socket
        if (current != null && !current.isClosed && current.inetAddress == address) return

        closeSocket()
        if (address == null) return
        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, port))
            }
        }.getOrElse {
            log("bind failed: ${address.hostAddress}:$port $it")
            return
        }
        socket = server
        if (stopped) return closeSocket()
        log("listening on ${address.hostAddress}:$port")
        Thread({ acceptLoop(server) }, "ContextCap-remote-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun closeSocket() {
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        try {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { handlers.execute { handle(client) } }
                    .onFailure { runCatching { client.close() } }
            }
        } finally {
            // accept が失敗して抜けた時も閉じておく。開いたまま受け手がいないと、
            // refresh が「張れている」と見なして張り直さない
            runCatching { server.close() }
        }
    }

    private fun handle(client: Socket) {
        client.use { s ->
            runCatching {
                s.soTimeout = READ_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                // ヘッダは読み捨てる。中身に依存する機能が無い
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                respond(requestLine, s.getOutputStream())
            }.onFailure { log("request failed: $it") }
        }
    }

    private fun respond(requestLine: String, out: OutputStream) {
        val parts = requestLine.split(' ')
        if (parts.size != 3) return writeText(out, 400, "bad request\n")
        val (method, target, _) = parts
        val headOnly = method == "HEAD"
        if (method != "GET" && !headOnly) return writeText(out, 405, "method not allowed\n")

        val file = resolve(target.substringBefore('?'))
            ?: return writeText(out, 404, "not found\n", headOnly)
        if (file.isDirectory) return writeText(out, 200, listing(file), headOnly)

        // 削除と競合して消えていることがある（Retention は別スレッドで動く）
        val length = file.length()
        if (!file.isFile) return writeText(out, 404, "not found\n", headOnly)
        writeHeader(out, 200, contentTypeOf(file.name), length)
        if (!headOnly) file.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    /// URL のパスを `root` 配下のファイルに解決する。規約外の名前・root の外は null。
    ///
    /// 保存形式に出てくる名前は英数字と `-` `_` `.` だけなので、それ以外の文字は一律に拒否する。
    /// パーセントエンコーディングも解釈しない（`%2e%2e` で root の外に出られないように）
    fun resolve(path: String): File? {
        if (!path.startsWith('/')) return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." || !SEGMENT.matches(it) }) return null
        if (segments.lastOrNull()?.endsWith(".tmp") == true) return null

        val file = segments.fold(root) { dir, name -> File(dir, name) }
        val base = root.canonicalFile
        val canonical = file.canonicalFile
        if (canonical != base && !canonical.path.startsWith(base.path + File.separator)) return null
        return file.takeIf { it.exists() }
    }

    /// ルートなら日付ディレクトリ、日付ディレクトリならファイルと大きさ。名前順。
    /// 1 ファイルごとに stat するので 1 日 8,000 枚で数百 ms かかる。UI スレッドから呼ばない
    fun listing(dir: File): String {
        val entries = dir.listFiles() ?: return ""
        val sb = StringBuilder()
        for (entry in entries.sortedBy { it.name }) {
            when {
                entry.isDirectory -> sb.append(entry.name).append("/\n")
                // 書き込み途中の一時ファイルは見せない（CaptureService が rename 前に置く）
                entry.name.endsWith(".tmp") -> Unit
                else -> sb.append(entry.name).append('\t').append(entry.length()).append('\n')
            }
        }
        return sb.toString()
    }

    private fun writeText(out: OutputStream, status: Int, body: String, headOnly: Boolean = false) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHeader(out, status, TEXT, bytes.size.toLong())
        if (!headOnly) out.write(bytes)
        out.flush()
    }

    private fun writeHeader(out: OutputStream, status: Int, contentType: String, length: Long) {
        val reason = REASONS[status] ?: "Error"
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: $length\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
    }

    private fun contentTypeOf(name: String): String = when {
        name.endsWith(".jpg") -> "image/jpeg"
        // jsonl は curl でそのまま grep / jq に流せるよう text として返す
        else -> TEXT
    }

    companion object {
        /// 張り直しの確認間隔。Tailscale を繋ぎ直してから読めるようになるまでの最大待ち時間でもある
        const val CHECK_INTERVAL_MS = 30_000L
        const val READ_TIMEOUT_MS = 10_000
        const val HANDLER_THREADS = 2

        private const val TEXT = "text/plain; charset=utf-8"
        private val SEGMENT = Regex("""[A-Za-z0-9._-]+""")
        private val REASONS = mapOf(
            200 to "OK",
            400 to "Bad Request",
            404 to "Not Found",
            405 to "Method Not Allowed",
        )
    }
}
