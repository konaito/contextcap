package app.imichat.contextcap

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
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
/// **合鍵（`token`）が無いリクエストには何も返さない。** `Authorization: Bearer <token>` か
/// `?token=<token>` で渡す。tailnet に自分以外の端末（会社・共有ノード）がいても読めないように。
///
/// **同じ端末からの接続は弾く。** Tailscale のアドレスは端末自身のインターフェースのアドレスなので、
/// INTERNET 権限を持つ他アプリが `100.x.y.z:8642` に繋ぐと届いてしまう（2026-10-22 の実機で、
/// アプリとは別の uid 2000 から `200 OK` が返った）。Android のアプリ間の分離を素通りさせない。
///
/// 接続数・1 行の長さ・ヘッダの量・読み取りの所要時間に上限を置く。同じプロセスで撮影しているので、
/// 接続を溜め込まれてメモリやファイル記述子が尽きると撮影ごと落ちる。
///
/// 撮影・OCR・削除のスレッドとは executor を共有しない。大きい画像の転送で撮影が詰まらないように。
/// `android.util.Log` は使わない（JVM unit test から起動して叩くため）。ログは `log` に渡す。
class RemoteServer(
    private val root: File,
    private val port: Int,
    private val addressProvider: () -> InetAddress?,
    private val token: String,
    /// 接続元がこの端末自身か。unit test は loopback から繋ぐので差し替える
    private val isLocalPeer: (InetAddress) -> Boolean = ::isOwnAddress,
    private val log: (String) -> Unit = {},
) {
    init {
        require(token.isNotEmpty()) { "token is empty" }
    }

    /// 張り直しの確認。アドレスの判定もここで行う
    private val control: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    /// 1 接続 1 タスク。一覧と画像取得を並べられるだけあればいい
    private val handlers = Executors.newFixedThreadPool(HANDLER_THREADS)
    /// 処理中と順番待ちを合わせた接続数の上限。`newFixedThreadPool` の待ち行列は無制限なので、
    /// ここで数えないと接続を張られるだけ Socket が溜まる
    private val slots = Semaphore(MAX_CONNECTIONS)

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
                // 上限を超えた分はすぐ閉じる。順番待ちにも入れない
                if (!slots.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                runCatching {
                    handlers.execute {
                        try {
                            handle(client)
                        } finally {
                            slots.release()
                        }
                    }
                }.onFailure {
                    slots.release()
                    runCatching { client.close() }
                }
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
                val out = s.getOutputStream()
                // 要求を読む前に弾く。同じ端末の他アプリには何も読ませない
                if (isLocalPeer(s.inetAddress)) return writeText(out, 403, "forbidden\n")
                val request = readRequest(BufferedInputStream(s.getInputStream()))
                    ?: return writeText(out, 400, "bad request\n")
                respond(request, out)
            }.onFailure { log("request failed: $it") }
        }
    }

    /// 要求行と、使うヘッダ（Authorization）だけ取り出す
    internal data class Request(val line: String, val authorization: String?)

    /// 上限のどれかを超えたら null（400 を返して閉じる）
    private fun readRequest(input: InputStream): Request? {
        val deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS
        val requestLine = readLine(input, deadline) ?: return null
        var authorization: String? = null
        var headerBytes = 0
        repeat(MAX_HEADERS) {
            val line = readLine(input, deadline) ?: return null
            if (line.isEmpty()) return Request(requestLine, authorization)
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) return null
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Authorization", ignoreCase = true)) {
                authorization = line.substring(colon + 1).trim()
            }
        }
        return null
    }

    /// LF（直前の CR は落とす）まで読む。`MAX_LINE_BYTES` を超えるか、期限を過ぎたら null。
    /// `BufferedReader.readLine` は改行が来るまで無制限に溜めるので使わない
    private fun readLine(input: InputStream, deadline: Long): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            if (System.currentTimeMillis() > deadline) return null
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (buf.size() >= MAX_LINE_BYTES) return null
            buf.write(b)
        }
        return buf.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
    }

    private fun respond(request: Request, out: OutputStream) {
        val parts = request.line.split(' ')
        if (parts.size != 3) return writeText(out, 400, "bad request\n")
        val (method, target, _) = parts
        val headOnly = method == "HEAD"
        if (method != "GET" && !headOnly) return writeText(out, 405, "method not allowed\n")
        if (!authorized(request.authorization, target.substringAfter('?', "")))
            return writeText(out, 401, "unauthorized\n", headOnly)

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

    /// `Authorization: Bearer <token>` か クエリの `token=<token>` が合鍵と一致するか。
    /// 長さや一致した文字数で時間が変わらない比較を使う
    private fun authorized(authorization: String?, query: String): Boolean {
        val bearer = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)
            ?.trim()
        val fromQuery = query.split('&')
            .firstOrNull { it.startsWith("token=") }
            ?.substringAfter('=')
        val presented = bearer ?: fromQuery ?: return false
        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
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
        /// 処理中 2 + 順番待ち 6。PC 1 台から一覧と画像を並べて取るには足りる
        const val MAX_CONNECTIONS = 8
        /// 要求行とヘッダを読み終えるまでの上限。1 バイトずつ送り続けて居座らせない
        const val REQUEST_DEADLINE_MS = 15_000L
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_HEADERS = 64
        const val MAX_HEADER_BYTES = 32 * 1024

        /// 端末自身のアドレスか（loopback か、この端末のどれかのインターフェースに付いたもの）。
        /// 調べられなかったときは自分とみなして弾く
        fun isOwnAddress(address: InetAddress): Boolean =
            address.isLoopbackAddress || address.isAnyLocalAddress ||
                runCatching { NetworkInterface.getByInetAddress(address) != null }.getOrDefault(true)

        private const val TEXT = "text/plain; charset=utf-8"
        private val SEGMENT = Regex("""[A-Za-z0-9._-]+""")
        private val REASONS = mapOf(
            200 to "OK",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
        )
    }
}
