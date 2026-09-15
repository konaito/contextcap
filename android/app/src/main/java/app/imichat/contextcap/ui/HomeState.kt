package app.imichat.contextcap.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.imichat.contextcap.CaptureBrowser
import app.imichat.contextcap.CaptureService
import app.imichat.contextcap.AppUsage
import app.imichat.contextcap.AppUsageStats
import app.imichat.contextcap.CaptureThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/// 帯に並べる枚数
private const val RECENT_LIMIT = 20

/// ヒーローとサムネイルのデコードサイズ。原寸で読むと 1 枚 10MB 近くなる
private const val HERO_MAX_PIXEL = 1200
private const val THUMB_MAX_PIXEL = 240

/// ホーム画面が見せる値。
/// 読み取りと監視をここに閉じ込めて、`HomeScreen` はレイアウトに専念させる。
@Stable
class HomeState internal constructor() {
    var snapshot by mutableStateOf<CaptureSnapshot?>(null)
        internal set
    var newest by mutableStateOf<File?>(null)
        internal set
    var heroImage by mutableStateOf<ImageBitmap?>(null)
        internal set
    var recent by mutableStateOf<List<File>>(emptyList())
        internal set
    var thumbs by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
        internal set

    /// アプリ別の枚数と容量。全件走査なので取得中は null
    var usage by mutableStateOf<List<AppUsage>?>(null)
        internal set
    var excluded by mutableStateOf<Set<String>>(emptySet())
        internal set

    /// この画面で除外を解除したアプリ。
    /// 画像が 0 枚だと解除した瞬間に行が消えて、気が変わっても止め直せない。
    /// 次の撮影で行が生えるまでの繋ぎとして、画面を開いている間だけ 0 枚の行を残す
    internal var unexcludedHere by mutableStateOf<Set<String>>(emptySet())

    /// 集計をやり直す合図。削除や除外の後に増やす
    internal var usageRevision by mutableIntStateOf(0)

    fun reloadUsage() {
        usage = null
        usageRevision += 1
    }

    /// 一時停止はトグルした瞬間に表示へ反映したいので、5 秒周期の `snapshot` とは別に持つ。
    /// `snapshot` 側の値は次の更新で追いつく。
    var paused by mutableStateOf(false)
        internal set

    internal var lastCaptureAt by mutableLongStateOf(0L)
    internal var now by mutableLongStateOf(0L)

    val recording: Boolean get() = snapshot?.recording ?: false

    /// 次の撮影までの進み具合（0..1）
    val progress: Float
        get() = if (lastCaptureAt == 0L) {
            0f
        } else {
            ((now - lastCaptureAt).coerceAtLeast(0L).toFloat() / CaptureService.INTERVAL_MS)
                .coerceIn(0f, 1f)
        }

    /// 除外の切り替え。集計を取り直すのは、全部消したアプリを除外した時に
    /// 「0 枚 · 撮影しない」の行を作り直す必要があるため（作らないと一覧から消えて解除できなくなる）
    fun toggleExcluded(context: Context, packageName: String) {
        val next = packageName !in excluded
        setExcluded(context, packageName, next)
        excluded = excludedPackages(context)
        unexcludedHere = if (next) unexcludedHere - packageName else unexcludedHere + packageName
        reloadUsage()
    }

    fun togglePause(context: Context) {
        val next = !paused
        paused = next
        setPaused(context, next)
        context.sendBroadcast(
            Intent(CaptureService.ACTION_SETTINGS_CHANGED).setPackage(context.packageName),
        )
    }
}

/// 状態を作って監視を開始する。監視は 4 本あり、それぞれ更新頻度が違う。
@Composable
fun rememberHomeState(): HomeState {
    val context = LocalContext.current
    val root = remember { CaptureService.captureRoot(context) }
    val state = remember { HomeState().apply { paused = isPaused(context) } }

    // 進捗インジケータを動かすための時計
    LaunchedEffect(Unit) {
        while (true) {
            state.now = System.currentTimeMillis()
            delay(100)
        }
    }

    // 統計。フルスキャンなので IO スレッドで、頻度も落とす
    LaunchedEffect(state.paused) {
        while (true) {
            state.snapshot = withContext(Dispatchers.IO) { readSnapshot(context) }
            delay(5_000)
        }
    }

    // 最新の 1 枚を監視する。CaptureBrowser は全件走査しないので毎秒でも軽い
    LaunchedEffect(Unit) {
        while (true) {
            val found = withContext(Dispatchers.IO) { CaptureBrowser.latest(root, 1).firstOrNull() }
            if (found?.absolutePath != state.newest?.absolutePath) {
                state.newest = found
                state.lastCaptureAt = System.currentTimeMillis()
                state.heroImage = withContext(Dispatchers.IO) {
                    found?.let { CaptureThumbnail.decode(it, HERO_MAX_PIXEL)?.asImageBitmap() }
                }
            }
            delay(1_000)
        }
    }

    // アプリ別の集計。全件走査なので、開いた時と明示的な合図の時だけ。
    // 除外アプリは画像が 0 枚でも行にしたいので、集計に渡す
    LaunchedEffect(state.usageRevision) {
        val excluded = excludedPackages(context)
        state.excluded = excluded
        state.usage = withContext(Dispatchers.IO) {
            withInstalledLabels(
                context,
                AppUsageStats.collect(root, excluded + state.unexcludedHere),
            )
        }
    }

    // 直近の帯。新しい 1 枚が来たら読み直す
    LaunchedEffect(state.newest?.absolutePath) {
        val files = withContext(Dispatchers.IO) { CaptureBrowser.latest(root, RECENT_LIMIT) }
        state.recent = files
        state.thumbs = withContext(Dispatchers.IO) {
            files.mapNotNull { file ->
                CaptureThumbnail.decode(file, THUMB_MAX_PIXEL)
                    ?.let { file.absolutePath to it.asImageBitmap() }
            }.toMap()
        }
    }

    return state
}
