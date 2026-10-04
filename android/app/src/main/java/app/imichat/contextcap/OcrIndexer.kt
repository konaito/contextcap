package app.imichat.contextcap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

/// 撮影した画像を OCR してテキストを確定させる。**OCR は充電中にだけ回す。**
///
/// 以前は撮影直後に OCR していた。Compactor（段階再圧縮）が OCR を追い越して、
/// 圧縮後の画像で OCR されるのを防ぐためだった。Compactor は 2026-08-18 に廃止したので、
/// 撮影直後である必要はもう無い。
///
/// 充電中に寄せた理由は電池。実機（Pixel 10 Pro Fold・2026-10-04）の OCR は 1 枚あたり
/// wall 1.1〜1.7 秒で、ML Kit が複数スレッドを使うため、10 秒ごとの撮影に追従すると
/// アプリ全体で CPU 1 コアの約 45% を使い続けた（うち撮影と保存は 1〜2%）。
/// OCR 結果を即時に読む画面は無く、`Retention` は未 OCR の画像を消さないので、
/// 充電までテキストの確定を待っても失うものは無い。
///
/// 設計上の要点:
///   - **充電判定は「電源につながっているか」。** 満充電で STATUS_FULL になっても止めない
///   - **消化中は PARTIAL_WAKE_LOCK を持つ。** 画面オフだと充電中でも CPU が寝て進まない。
///     1 枚ごとに timeout 付きで取り直すので、取りっぱなしにならない
///   - **撮影直後の enqueue は続ける。** 止めるのは消化だけ。積んでおけば充電した瞬間に始まり、
///     `pendingCount` も正しい
///   - **同じファイルを二重に積まない。** reconcile と enqueue が同じ画像を拾うことがある
///   - **撮影 worker とは別スレッド。** 同じ executor に乗せると撮影 tick が詰まる
///   - **並列にしない。** 単一スレッドなので、同時に持つ Bitmap は常に 1 枚だけ。
///     1080x2400 の ARGB_8888 は約 10MB あるので、並列化するとそのまま倍々でメモリを食う
///   - **キューは 2 段。** 撮影直後の分（fresh）を必ず先に処理し、起動時に拾った未 OCR
///     （backlog）は fresh が空の時だけ 1 枚ずつ進める。1 本にすると追いつきが終わるまで
///     新規撮影が待たされ、「撮った瞬間に OCR」が成立しない
///   - **Bitmap は必ず recycle する。** `ScreenshotResult.hardwareBuffer` を閉じ忘れて
///     以降の撮影が全部落ちた件と同型の罠
class OcrIndexer(
    private val root: File,
    private val wakeLock: PowerManager.WakeLock? = null,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()

    /// 電源につながっている間だけ true。false の間は積むだけで消化しない
    @Volatile private var charging = false
    /// 積まれているファイルのパス。二重に積まないために持つ
    private val queued = HashSet<String>()

    /// 撮影直後の分。必ずこちらを先に処理する
    private val fresh = ArrayDeque<File>()
    /// 起動時に拾った未 OCR の分。fresh が空の時だけ消化する
    private val backlog = ArrayDeque<File>()
    private var draining = false

    @Volatile private var indexed = 0
    @Volatile private var failed = 0

    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    // MARK: - 状態（画面表示・Retention のガード用）

    val pendingCount: Int
        get() = synchronized(lock) { fresh.size + backlog.size + if (draining) 1 else 0 }

    val statusText: String
        get() = synchronized(lock) {
            when {
                !charging && (fresh.isNotEmpty() || backlog.isNotEmpty()) ->
                    "OCR: 充電待ち ${fresh.size + backlog.size} 枚"
                backlog.isNotEmpty() -> "OCR: 追いつき中 残り ${backlog.size} 枚"
                fresh.isNotEmpty() || draining -> "OCR: 残り ${fresh.size} 枚"
                else -> "OCR: 追いついている（$indexed 枚）"
            }
        }

    // MARK: - 投入

    /// 撮影直後に呼ぶ。積むだけで OCR は別スレッドで走る。
    /// backlog がどれだけ溜まっていても、これが先に処理される。
    fun enqueue(file: File) {
        synchronized(lock) {
            if (queued.add(file.absolutePath)) fresh.addLast(file)
        }
        drainIfNeeded()
    }

    /// 電源の抜き差しで呼ぶ。挿したら溜まった分の消化を始め、抜いたら今の 1 枚で止める
    fun setCharging(value: Boolean) {
        if (charging == value) return
        charging = value
        Log.i(TAG, if (value) "充電中: OCR を再開" else "電源なし: OCR を止める")
        if (value) drainIfNeeded()
    }

    /// 起動時に一度だけ、`ocr.jsonl` に無いファイルを拾い直す。
    /// サービスが止まっていた間の撮影や、enqueue の取りこぼしの保険。
    /// 古い順に積む（Retention が古い順に消すので、消える前に読む）。
    fun reconcile() {
        worker.execute {
            val missing = ArrayList<File>()
            val dayDirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return@execute
            for (dayDir in dayDirs) {
                val done = OcrLog.indexedStems(dayDir)
                val jpgs = dayDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: continue
                jpgs.sortedBy { it.name }
                    .filter { CaptureFile.baseNameOf(it) !in done }
                    .forEach { missing.add(it) }
            }
            if (missing.isEmpty()) return@execute
            Log.i(TAG, "追いつき対象 ${missing.size} 枚")
            synchronized(lock) {
                missing.filter { queued.add(it.absolutePath) }.forEach { backlog.addLast(it) }
            }
            drainIfNeeded()
        }
    }

    fun shutdown() {
        worker.shutdown()
    }

    // MARK: - 消化

    private fun drainIfNeeded() {
        synchronized(lock) {
            if (!charging || draining || (fresh.isEmpty() && backlog.isEmpty())) return
            draining = true
        }
        worker.execute {
            while (true) {
                val file = synchronized(lock) {
                    // fresh を必ず先に見る。撮影中はここだけが回り、backlog は撮影の合間
                    // （10 秒に 1 枚なので大半は暇）に少しずつ進む。
                    // 電源が抜かれたら残りは積んだまま止める
                    when {
                        !charging -> null
                        fresh.isNotEmpty() -> fresh.pollFirst()
                        backlog.isNotEmpty() -> backlog.pollFirst()
                        else -> null
                    }?.also { queued.remove(it.absolutePath) }
                        ?: run {
                            draining = false
                            null
                        }
                } ?: return@execute
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                try {
                    process(file)
                } finally {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
            }
        }
    }

    /// `worker` の上でだけ呼ばれる
    private fun process(file: File) {
        // Retention に消された後で回ってくることがある。エラーにしない
        if (!file.isFile) return
        val dayDir = file.parentFile ?: return
        val stem = CaptureFile.baseNameOf(file)
        val gen = CaptureFile.generationOf(file)
        val bytes = file.length()

        val started = System.currentTimeMillis()
        var bitmap: Bitmap? = null
        try {
            // **縮小しない。** macOS の統制実験で、縮小しても OCR は速くならず
            // （時間は解像度ではなく行数に比例）テキストだけが壊れると分かっている
            bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null) {
                OcrLog.append(dayDir, OcrLog.Entry(
                    stem, gen, 0, 0, bytes, 0, 0, "failed", "", "デコード失敗"
                ))
                failed++
                return
            }

            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            val lines = result.textBlocks.sumOf { block -> block.lines.size }
            val text = result.text
            val status = if (lines == 0) "empty" else "ok"

            OcrLog.append(dayDir, OcrLog.Entry(
                timeStem = stem, gen = gen,
                width = bitmap.width, height = bitmap.height, bytes = bytes,
                lines = lines, ms = System.currentTimeMillis() - started,
                status = status, text = text,
            ))
            indexed++
        } catch (e: Exception) {
            // 黙って落とさない。失敗も 1 行残して、無限に再試行しないようにする
            Log.w(TAG, "OCR 失敗: ${file.name}", e)
            OcrLog.append(dayDir, OcrLog.Entry(
                stem, gen, bitmap?.width ?: 0, bitmap?.height ?: 0, bytes,
                0, System.currentTimeMillis() - started, "failed", "", e.message ?: "unknown"
            ))
            failed++
        } finally {
            // 閉じ忘れるとメモリを食い続ける。1080x2400 ARGB_8888 で約 10MB
            bitmap?.recycle()
        }
    }

    companion object {
        private const val TAG = "OcrIndexer"

        /// 1 枚分の wakelock の上限。実機の OCR は p90 で約 1.7 秒なので十分に長く、
        /// 解放し損ねても 1 分で切れる
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
    }
}
