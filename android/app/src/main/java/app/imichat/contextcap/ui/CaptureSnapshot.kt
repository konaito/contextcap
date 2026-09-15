package app.imichat.contextcap.ui

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import app.imichat.contextcap.AppUsage
import app.imichat.contextcap.AppUsageStats
import app.imichat.contextcap.CaptureService
import app.imichat.contextcap.StatsStore
import java.io.File

/// 画面に出す値をまとめたもの。
/// 読み取りに I/O を含む（`StatsStore` はディレクトリのフルスキャン）ため、
/// **必ず IO スレッドで作る**こと。UI スレッドで作ると枚数に比例して固まる。
data class CaptureSnapshot(
    val enabled: Boolean,
    val paused: Boolean,
    val countText: String,
    val durationText: String,
    val sizeText: String,
    val budgetText: String,
    val qualityText: String,
    val rootPath: String,
    /// 直近の撮影が情報のない画面（DRM 保護・AOD）で、保存を見送ったか
    val blackout: Boolean,
) {
    val recording: Boolean get() = enabled && !paused
}

/// アクセシビリティが有効かどうか。
///
/// `AccessibilityManager.getEnabledAccessibilityServiceList()` は使わない。
/// **Android 17 の実機では、有効になっていても自分自身が返ってこなかった**
/// （Android 16 のエミュレータでは返る。同じ APK で挙動が違った）。
/// OS の設定値を直接読む方が確実で、撮影が動いているのに「権限がありません」と
/// 誤表示する事故を防げる。
fun isCaptureServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    // "pkg/cls:pkg/cls" 形式。短縮形で入ることもあるのでパッケージ名で判定する
    return enabled.split(':').any { it.substringBefore('/') == context.packageName }
}

fun isPaused(context: Context): Boolean =
    context.getSharedPreferences(CaptureService.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(CaptureService.KEY_PAUSED, false)

fun setPaused(context: Context, paused: Boolean) {
    context.getSharedPreferences(CaptureService.PREFS_NAME, Context.MODE_PRIVATE)
        .edit { putBoolean(CaptureService.KEY_PAUSED, paused) }
}

fun excludedPackages(context: Context): Set<String> =
    context.getSharedPreferences(CaptureService.PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(CaptureService.KEY_EXCLUDED_PACKAGES, emptySet()) ?: emptySet()

/// 指定アプリを撮影対象から外す / 戻す
fun setExcluded(context: Context, packageName: String, excluded: Boolean) {
    val current = excludedPackages(context).toMutableSet()
    if (excluded) current.add(packageName) else current.remove(packageName)
    context.getSharedPreferences(CaptureService.PREFS_NAME, Context.MODE_PRIVATE)
        .edit { putStringSet(CaptureService.KEY_EXCLUDED_PACKAGES, current) }
}

/// 表示名を引けなかった行を `PackageManager` で補う。IO を含むので IO スレッド専用。
///
/// `apps.jsonl` ごと消えた除外アプリ（日付ディレクトリが容量整理で消えた場合など）は、
/// ここでしか名前が付かない。package visibility の制限で引けなければパッケージ名のまま出す
/// （`QUERY_ALL_PACKAGES` は足さない方針）。
fun withInstalledLabels(context: Context, items: List<AppUsage>): List<AppUsage> {
    val pm = context.packageManager
    return items.map { item ->
        val pkg = item.packageName
        if (item.label != null || pkg == null) return@map item
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        if (label == null || label == pkg) item else item.copy(label = label)
    }.sortedWith(AppUsageStats.ORDER)
}

/// 統計を含む全体を読む。IO スレッド専用
fun readSnapshot(context: Context): CaptureSnapshot {
    val prefs = context.getSharedPreferences(CaptureService.PREFS_NAME, Context.MODE_PRIVATE)
    val root: File = CaptureService.captureRoot(context)
    val stats = StatsStore(root)
    val budget = prefs.getLong(CaptureService.KEY_BUDGET_BYTES, CaptureService.DEFAULT_BUDGET_BYTES)

    return CaptureSnapshot(
        enabled = isCaptureServiceEnabled(context),
        paused = prefs.getBoolean(CaptureService.KEY_PAUSED, false),
        countText = stats.countText,
        durationText = stats.durationText,
        sizeText = stats.sizeText,
        budgetText = StatsStore.formatBytes(budget),
        // 段階圧縮を廃止したので常に等倍
        qualityText = "等倍 q${CaptureService.FULL_RES_QUALITY}",
        rootPath = root.absolutePath,
        blackout = prefs.getBoolean(CaptureService.KEY_UNINFORMATIVE, false),
    )
}
