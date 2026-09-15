package app.imichat.contextcap

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.core.content.edit
import androidx.core.graphics.scale
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

/// 画面を一定間隔で撮影して端末内に保存する常駐サービス。
/// AccessibilityService 自体が OS 管理の常駐プロセスなので、ForegroundService は持たない。
///
/// 次の tick は「撮影 → 保存」の完了後にスケジュールするので、
/// 別途 in-flight guard を持たなくても直列性が保たれる（macOS 版の in-flight guard と同じ思想）。
class CaptureService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    /// 撮影のコールバック受けと JPEG 保存を行う。メインスレッドを I/O で塞がない
    private val worker = Executors.newSingleThreadExecutor()
    /// 削除判断は撮影用 worker と分ける。ここを共有すると撮影が詰まる
    private val retentionWorker = Executors.newSingleThreadExecutor()
    /// 撮影直後の OCR。撮影 worker とも retention worker とも別スレッドで、内部は直列
    private lateinit var ocr: OcrIndexer

    private lateinit var stats: StatsStore
    private lateinit var appLog: ForegroundAppLog
    private val blackoutLog = BlackoutLog()

    /// 直近の撮影が情報のない画面だったか。画面表示のために SharedPreferences へ写す
    private var lastUninformative: Boolean? = null

    /// 前面アプリのパッケージ名。onAccessibilityEvent (メイン) が書き、撮影スレッドが読む
    @Volatile
    private var foregroundPackage: String? = null

    /// 有効な IME のパッケージ。onServiceConnected で 1 回だけ取る。
    /// 取得できなければ空のまま（その場合も systemui の除外だけは効く）
    @Volatile
    private var imePackages: Set<String> = emptySet()
    private var running = false
    /// 最後に撮影サイクルが完了した時刻。watchdog が tick の停止を検出するのに使う
    private var lastCycleAt = 0L

    private val tickRunnable = Runnable { captureOnce() }

    private val retentionRunnable = Runnable {
        restartIfStalled()
        sweepNow()
    }

    /// 画面 ON/OFF と設定変更を受ける。ACTION_SCREEN_* は manifest 静的登録できないので動的登録する
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "screen on")
                    if (!isPaused()) start()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "screen off")
                    stop()
                }
                ACTION_SETTINGS_CHANGED -> {
                    if (isPaused()) stop() else start()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val root = captureRoot(this)
        // 適応撮影プロファイルは廃止した。残っていると誤読するので消す
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CAPTURE_GEN) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACTION_SETTINGS_CHANGED)
        }
        // ACTION_SETTINGS_CHANGED は自アプリ内の broadcast なので、
        // 他アプリから送られないよう NOT_EXPORTED を明示する
        registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // 有効な IME の一覧。前面アプリの判定から除くために使う。
        // 決め打ちのパッケージ名リストにしないのは、機種と設定で変わるため。
        // 失敗しても撮影は続ける（AccessibilityManager の一覧取得が Android 17 実機で
        // 壊れていた前例があるので、この種の API の戻りに依存しきらない）
        imePackages = runCatching {
            getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.mapNotNull { it.packageName }
                ?.toSet()
        }.getOrNull() ?: emptySet()

        appLog = ForegroundAppLog(this)
        ocr = OcrIndexer(root)
        // サービスが止まっていた間の撮影を拾い直す。backlog キューに入るので新規撮影を待たせない
        ocr.reconcile()
        worker.execute {
            stats = StatsStore(root)
            handler.post {
                start()
                handler.postDelayed(retentionRunnable, RETENTION_INTERVAL_MS)
            }
        }
        Log.i(TAG, "connected. root=${root.absolutePath}")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(systemReceiver) }
        stop()
        handler.removeCallbacks(retentionRunnable)
        worker.shutdown()
        retentionWorker.shutdown()
        if (::ocr.isInitialized) ocr.shutdown()
        super.onDestroy()
    }

    /// 購読しているのは typeWindowStateChanged だけ。前面アプリの把握に使う。
    /// 画面の中身は読まない（canRetrieveWindowContent を付けていないので読めない）。
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        // 通知シェード・キーボードなどのオーバーレイでは上書きしない。
        // 開いた時だけイベントが飛び、閉じた時は元アプリのイベントが来ないので、
        // 上書きすると systemui に張り付いたまま戻らなくなる（OverlayPackages 参照）
        if (OverlayPackages.isOverlay(packageName, imePackages)) return
        // ContextCap 自身も対象にする。撮ると記録が自分の画面で埋まるが、
        // 嫌なら「アプリ別」から除外指定して止められる方が筋が通る
        foregroundPackage = packageName
    }

    override fun onInterrupt() = Unit

    // MARK: - 制御

    private fun isPaused(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)

    private fun excludedPackages(): Set<String> =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXCLUDED_PACKAGES, emptySet()) ?: emptySet()

    private fun start() {
        if (running || isPaused()) return
        running = true
        handler.post(tickRunnable) // 有効化直後にも 1 枚撮る
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
        // 止めた状態のまま「保護された画面」と出し続けないよう畳んでおく
        rememberUninformative(false)
    }

    private fun scheduleNext() {
        lastCycleAt = System.currentTimeMillis()
        if (!running) return
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, INTERVAL_MS)
    }

    /// 撮影ループは callback 駆動なので、takeScreenshot が callback を呼ばずに終わると
    /// 二度と再開しない。放置前提のアプリでは静かに死ぬのが最悪なので、
    /// 一定時間サイクルが完了していなければ tick を叩き直す。
    private fun restartIfStalled() {
        if (!running) return
        val silence = System.currentTimeMillis() - lastCycleAt
        if (silence < STALL_THRESHOLD_MS) return
        Log.w(TAG, "tick stalled for ${silence}ms. restarting")
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    // MARK: - 撮影

    private fun captureOnce() {
        if (!running) return
        // 除外指定されたアプリは撮らない。apps.jsonl にも残さない（履歴だけ残ると除外の意味がない）
        val front = foregroundPackage
        if (front != null && front in excludedPackages()) {
            handler.post { scheduleNext() }
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            worker,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace,
                        )
                        if (bitmap == null) {
                            Log.w(TAG, "wrapHardwareBuffer returned null")
                            return
                        }
                        val now = Date()
                        // 情報のない画面は保存されず null が返る。統計にも数えない
                        val saved = save(bitmap, now)
                        if (saved != null) {
                            stats.recordCapture(saved.length(), now)
                            // 撮った瞬間に OCR へ。完了は待たない（待つと撮影が詰まる）
                            ocr.enqueue(saved)
                            if (stats.totalBytes > budgetBytes()) {
                                handler.post { sweepNow() }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "save failed", e)
                    } finally {
                        // 解放しないとバッファが枯渇して以降の撮影が全部落ちる
                        screenshot.hardwareBuffer.close()
                        handler.post { scheduleNext() }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    // 失敗しても無視して次の tick へ。
                    // なお FLAG_SECURE のウィンドウはここに来ない。撮影は成功し、
                    // 該当領域が黒く塗られた画像が保存される（Android 16 で実測）。
                    // ERROR_TAKE_SCREENSHOT_SECURE_WINDOW を返す端末があるかもしれないので
                    // 分岐自体は残してある。
                    if (errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) {
                        Log.e(TAG, "accessibility access lost")
                    } else {
                        Log.d(TAG, "screenshot failed: $errorCode")
                    }
                    handler.post { scheduleNext() }
                }
            },
        )
    }

    /// **常に等倍で保存する。** 以前は Compactor が使った圧縮段を撮影プロファイルとして
    /// 採用し、以降その解像度で撮っていた。2026-08-18 に廃止した。macOS 版の同一画像の
    /// 統制実験で、縮小しても OCR は速くならず（時間は解像度ではなく行数に比例）
    /// テキストだけが壊れると分かったため（1920/q60 で行近似一致 75.1%）。
    ///
    /// 情報のない画面（DRM 保護・AOD）は**ファイルを作らずに捨てる**。捨てた場合は null。
    /// 判定のためにエンコードを先に済ませ、ファイルには書かずに済ませている。
    private fun save(bitmap: Bitmap, date: Date): File? {
        val scaled = bitmap

        val encoded = ByteArrayOutputStream()
        val compressed = scaled.compress(Bitmap.CompressFormat.JPEG, FULL_RES_QUALITY, encoded)
        if (scaled !== bitmap) scaled.recycle()
        if (!compressed) throw IllegalStateException("JPEG エンコード失敗")
        val jpeg = encoded.toByteArray()
        // 0 バイトの JPEG を書かない。画面ロック・スリープ中に compress が中身の無い
        // 結果を返すことがある（このエミュレータでも 2026-08-16 01:04 に 4 枚出た）。
        // 書いてしまうと後段が「読めなかった」のか「観測できなかった」のか区別できず、
        // MiaChat 本体では text-only 再試行に落ちて幻覚 annotation を 247 件作った。
        // 撮れなかったことは gaps.jsonl 側に残るので、ここは黙って捨ててよい
        if (jpeg.isEmpty()) return null

        val dayDir = File(captureRoot(this), CaptureFile.dayDirName(date)).apply { mkdirs() }
        val timeStem = CaptureFile.timeStem(date)
        val uninformative = isUninformative(jpeg)

        // 前面アプリは画面が黒でも残す。「何を見ていたか」は画像が無くても正解データになる
        appLog.record(dayDir, date, timeStem, foregroundPackage)
        blackoutLog.record(dayDir, date, timeStem, uninformative)
        rememberUninformative(uninformative)
        if (uninformative) return null

        // 等倍でしか撮らなくなったので gen 接尾辞は付けない。
        // 既に .gN が付いたファイルを読む側は CaptureFile.LEGACY_GENERATION_SUFFIXES が面倒を見る
        val stem = timeStem
        val target = File(dayDir, "$stem.jpg")
        // 書き込み途中のファイルを回収されないよう、一時ファイルに書いてから rename する
        val tmp = File(dayDir, "$stem.jpg.tmp")

        tmp.outputStream().use { out -> out.write(jpeg) }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("rename 失敗: ${target.absolutePath}")
        }
        return target
    }

    /// 保存する価値のない画面かどうか。判定は縮小デコードしたピクセルに対して行う。
    /// HARDWARE config の Bitmap は直接読めないので、エンコード済みの JPEG から読み直す
    /// （撮影ごとに原寸を ARGB_8888 へコピーするより安い）。
    private fun isUninformative(jpeg: ByteArray): Boolean {
        val small = CaptureThumbnail.decode(jpeg, BLACKNESS_MAX_PIXEL) ?: return false
        return try {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            FrameBlackness.isUninformative(pixels, small.width, small.height)
        } finally {
            small.recycle()
        }
    }

    /// 画面に出すために現在の状態を残す。変わった時だけ書く
    private fun rememberUninformative(value: Boolean) {
        if (lastUninformative == value) return
        lastUninformative = value
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_UNINFORMATIVE, value) }
    }

    // MARK: - 容量管理

    private fun budgetBytes(): Long =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_BUDGET_BYTES, DEFAULT_BUDGET_BYTES)

    /// 5 分ごとの定期チェック。保持期間を過ぎた「OCR 済み」画像を消す。再圧縮はしない
    private fun sweepNow() {
        handler.removeCallbacks(retentionRunnable)
        retentionWorker.execute {
            val result = Retention(
                root = captureRoot(this),
                budgetBytes = budgetBytes(),
            ).sweep()

            if (result != null) {
                Log.i(
                    TAG,
                    "retention: deleted=${result.deleted} " +
                        "freed=${result.freedBytes} blockedByOcr=${result.blockedByOcr}",
                )
                // stats への書き込みは撮影スレッドに寄せて競合を避ける
                worker.execute { stats.rescan() }
            }
            handler.post { handler.postDelayed(retentionRunnable, RETENTION_INTERVAL_MS) }
        }
    }

    companion object {
        const val TAG = "ContextCap"
        const val PREFS_NAME = "contextcap"
        /// 廃止済み。起動時に消すためだけに残している
        const val KEY_CAPTURE_GEN = "CaptureGeneration"
        const val KEY_PAUSED = "Paused"
        const val KEY_BUDGET_BYTES = "StorageBudgetBytes"

        /// 撮影しないアプリのパッケージ名
        const val KEY_EXCLUDED_PACKAGES = "ExcludedPackages"

        /// 直近の撮影が情報のない画面だったか。画面表示のためだけに置く
        const val KEY_UNINFORMATIVE = "LastCaptureUninformative"

        /// SetupActivity の一時停止トグルから送られる
        const val ACTION_SETTINGS_CHANGED = "app.imichat.contextcap.SETTINGS_CHANGED"

        /// 撮影間隔。macOS 版は 5 秒だが、スマホは容量制約から 10 秒にする
        const val INTERVAL_MS = 10_000L

        /// これだけサイクルが完了していなければ tick が死んだとみなして叩き直す
        const val STALL_THRESHOLD_MS = 60_000L

        /// 保持期間・容量チェックの間隔。削除は日単位の判断なので撮影ごとにやる必要はない
        const val RETENTION_INTERVAL_MS = 5 * 60 * 1000L

        /// 保存する JPEG の品質。常に等倍で撮るので段による分岐は無い
        const val FULL_RES_QUALITY = 75

        /// 情報のない画面かどうかを判定するときのデコードサイズ（長辺）。
        /// 原寸を読む必要はなく、格子で見るのに足りればいい
        const val BLACKNESS_MAX_PIXEL = 160

        /// 容量上限。テスト時はこの定数を書き換えて再ビルドする（README 参照）
        const val DEFAULT_BUDGET_BYTES = 10_000_000_000L

        /// 保存先ルート。エミュレータ Android 16 で adb pull が通ることを確認済み
        fun captureRoot(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "ContextCap").apply { mkdirs() }
        }
    }
}
