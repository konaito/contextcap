package app.imichat.contextcap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

/// 撮影した画像を、劣化する前に OCR してテキストを確定させる。
///
/// なぜ撮影と同じ流れに入れるか: macOS 版は回収後にバッチで OCR していた結果、Compactor が
/// OCR を追い越し、corpus の 39%（20,007 枚）が圧縮後の画像で OCR された。同一画像の統制実験で
/// 1920/q60 は行近似一致 75.1%、1280/q50 は 56.8%、800/q40 は 5.6% まで落ちる。
/// Android は 10GB 上限に対してまだ 153MB しか溜まっておらず圧縮が一度も走っていないので、
/// 同じ損失が起きる前に順序を固定する。
///
/// 速度は問題にならない。撮影は 10 秒に 1 枚（0.1 枚/秒）。ML Kit が 1 枚/秒 出れば 10 倍の余裕。
///
/// 設計上の要点:
///   - **撮影 worker とは別スレッド。** 同じ executor に乗せると撮影 tick が詰まる
///   - **並列にしない。** 単一スレッドなので、同時に持つ Bitmap は常に 1 枚だけ。
///     1080x2400 の ARGB_8888 は約 10MB あるので、並列化するとそのまま倍々でメモリを食う
///   - **キューは 2 段。** 撮影直後の分（fresh）を必ず先に処理し、起動時に拾った未 OCR
///     （backlog）は fresh が空の時だけ 1 枚ずつ進める。1 本にすると追いつきが終わるまで
///     新規撮影が待たされ、「撮った瞬間に OCR」が成立しない
///   - **Bitmap は必ず recycle する。** `ScreenshotResult.hardwareBuffer` を閉じ忘れて
///     以降の撮影が全部落ちた件と同型の罠
class OcrIndexer(private val root: File) {
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()

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
                backlog.isNotEmpty() -> "OCR: 追いつき中 残り ${backlog.size} 枚"
                fresh.isNotEmpty() || draining -> "OCR: 残り ${fresh.size} 枚"
                else -> "OCR: 追いついている（$indexed 枚）"
            }
        }

    // MARK: - 投入

    /// 撮影直後に呼ぶ。積むだけで OCR は別スレッドで走る。
    /// backlog がどれだけ溜まっていても、これが先に処理される。
    fun enqueue(file: File) {
        synchronized(lock) { fresh.addLast(file) }
        drainIfNeeded()
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
            synchronized(lock) { backlog.addAll(missing) }
            drainIfNeeded()
        }
    }

    fun shutdown() {
        worker.shutdown()
    }

    // MARK: - 消化

    private fun drainIfNeeded() {
        synchronized(lock) {
            if (draining || (fresh.isEmpty() && backlog.isEmpty())) return
            draining = true
        }
        worker.execute {
            while (true) {
                val file = synchronized(lock) {
                    // fresh を必ず先に見る。撮影中はここだけが回り、backlog は撮影の合間
                    // （10 秒に 1 枚なので大半は暇）に少しずつ進む
                    when {
                        fresh.isNotEmpty() -> fresh.pollFirst()
                        backlog.isNotEmpty() -> backlog.pollFirst()
                        else -> {
                            draining = false
                            null
                        }
                    }
                } ?: return@execute
                process(file)
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
    }
}
