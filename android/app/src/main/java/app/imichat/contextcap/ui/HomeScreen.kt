package app.imichat.contextcap.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.imichat.contextcap.AppUsage
import app.imichat.contextcap.AppUsageQuery
import app.imichat.contextcap.AppUsageStats
import app.imichat.contextcap.CaptureService
import app.imichat.contextcap.StatsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/// ホーム。設定画面ではなく「今どうなっているか」を見る画面として組む。
///
/// 主役は撮れている画像。数字は最下部に置く。
/// 記録中は次の撮影までの進捗が動くので、放置していても生きていることが目に入る。
@Composable
fun HomeScreen(onOpenViewer: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberHomeState()
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            StatusHeader(
                snapshot = state.snapshot,
                recording = state.recording,
                progress = state.progress,
                onOpenAccessibility = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )

            Hero(
                image = state.heroImage,
                fileName = state.newest?.name,
                onClick = { onOpenViewer(0) },
            )

            RecentStrip(
                files = state.recent,
                thumbs = state.thumbs,
                onClick = onOpenViewer,
            )

            AppUsageSection(
                usage = state.usage,
                excluded = state.excluded,
                onToggleExcluded = { pkg -> state.toggleExcluded(context, pkg) },
                onDelete = { pkg ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AppUsageStats.deleteAll(CaptureService.captureRoot(context), pkg)
                        }
                        state.reloadUsage()
                    }
                },
            )

            Numbers(snapshot = state.snapshot)

            PauseRow(
                enabled = state.snapshot?.enabled == true,
                paused = state.paused,
                onToggle = { state.togglePause(context) },
            )
        }
    }
}

/// 状態。ここだけは常に画面上部にあって、開いた瞬間に目に入る
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusHeader(
    snapshot: CaptureSnapshot?,
    recording: Boolean,
    progress: Float,
    onOpenAccessibility: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 16.dp)) {
        Text(
            text = when {
                snapshot == null -> "確認しています"
                !snapshot.enabled -> "止まっています"
                snapshot.paused -> "一時停止中"
                else -> "記録中"
            },
            style = MaterialTheme.typography.displaySmall,
            color = if (recording) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = when {
                snapshot == null -> "…"
                !snapshot.enabled -> "権限がありません"
                snapshot.paused -> "再開するまで撮りません"
                // 黒い画面は保存しないので新しい画像が増えない。
                // 何も出さないと「止まった」と読めてしまうので、理由を出す
                snapshot.blackout -> "保護された画面のため保存していません"
                else -> "${CaptureService.INTERVAL_MS / 1000} 秒ごと"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (recording) {
            // 次の撮影までの進捗。動いていることが目に入るのがこの画面の主目的
            LinearWavyProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            )
        }

        if (snapshot != null && !snapshot.enabled) {
            FilledTonalButton(
                onClick = onOpenAccessibility,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text("権限を渡す")
            }
        }
    }
}

/// ヒーローの高さ。縦長のスクリーンショットでも画面を占有しすぎない大きさ
private val HERO_HEIGHT = 440.dp

/// いま見えているもの。切らずに全体を見せる
@Composable
private fun Hero(image: ImageBitmap?, fileName: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Text(
            text = "いま見えているもの",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        // 画像そのものの縦横比で出す。Crop すると上下が切れて
        // ステータスバーやアプリのヘッダが見えなくなり、確認の役に立たない
        // 高さを決めて幅を比率に任せる。幅基準にすると縦長画像で画面を占有してしまう
        val ratio = image?.let { it.width.toFloat() / it.height } ?: 0.46f
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .height(HERO_HEIGHT)
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .clickable(enabled = image != null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "最新のスクリーンショット",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "まだ何も撮れていません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (fileName != null) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/// 直近の流れ。何が溜まっているかが時間の並びとして見える
@Composable
private fun RecentStrip(
    files: List<File>,
    thumbs: Map<String, ImageBitmap>,
    onClick: (Int) -> Unit,
) {
    if (files.isEmpty()) return
    Column(modifier = Modifier.padding(top = 28.dp)) {
        Text(
            text = "ここまでの流れ",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),
        ) {
            itemsIndexed(files) { index, file ->
                // 高さを揃え、幅は画像の比率に任せる。こちらも切らない
                val thumb = thumbs[file.absolutePath]
                val thumbRatio = thumb?.let { it.width.toFloat() / it.height } ?: 0.46f
                Box(
                    modifier = Modifier
                        .height(150.dp)
                        .aspectRatio(thumbRatio)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .clickable { onClick(index) },
                ) {
                    thumb?.let {
                        Image(
                            bitmap = it,
                            contentDescription = file.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/// 数字は最下部。確認したい時だけ読めればいい
@Composable
private fun Numbers(snapshot: CaptureSnapshot?) {
    Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp)) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        NumberRow("これまでに見たもの", snapshot?.countText ?: "—")
        NumberRow("記録している期間", snapshot?.durationText ?: "—")
        NumberRow(
            "使っている容量",
            snapshot?.let { "${it.sizeText} / ${it.budgetText}" } ?: "—",
        )
        NumberRow("撮影画質", snapshot?.qualityText ?: "—")
        Text(
            text = snapshot?.rootPath ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "端末内にだけ保存されます。送信は行いません。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun NumberRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/// 一時停止は目立たせない。誤って止める方が損失が大きい
@Composable
private fun PauseRow(enabled: Boolean, paused: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onToggle, enabled = enabled) {
            Text(
                text = if (paused) "記録を再開する" else "記録を止める",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/// アプリ別の枚数と容量。削除と除外はここから行う。
/// 集計は全件走査なので、終わるまではその旨を出す。
///
/// **撮影を止めたアプリは先頭にまとめる。** 画像を全部消すと 0 枚になるが、
/// 枚数順に混ぜると最下部へ沈んで「どれを止めたか」が分からなくなる。
@Composable
private fun AppUsageSection(
    usage: List<AppUsage>?,
    excluded: Set<String>,
    onToggleExcluded: (String) -> Unit,
    onDelete: (String?) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<AppUsage?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(usage, query) { usage?.let { AppUsageQuery.filter(it, query) } }
    val stopped = filtered?.filter { it.packageName != null && it.packageName in excluded }.orEmpty()
    val recording = filtered?.filter { it.packageName == null || it.packageName !in excluded }.orEmpty()

    Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp)) {
        Text(
            text = "アプリ別",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (!usage.isNullOrEmpty()) {
            AppSearchField(query = query, onQueryChange = { query = it })
        }

        val row: @Composable (AppUsage) -> Unit = { item ->
            AppUsageRow(
                item = item,
                isExcluded = item.packageName != null && item.packageName in excluded,
                onToggleExcluded = { item.packageName?.let(onToggleExcluded) },
                onRequestDelete = { pendingDelete = item },
            )
        }

        when {
            usage == null -> Hint("数えています…")
            usage.isEmpty() -> Hint("まだ何も撮れていません")
            filtered.isNullOrEmpty() -> Hint("「${query.trim()}」に一致するアプリはありません")
            // 止めたアプリが無いなら見出しを出さない。1 本の一覧のままの方が読みやすい
            stopped.isEmpty() -> recording.forEach { row(it) }
            else -> {
                GroupHeader("撮影しないアプリ (${stopped.size})")
                stopped.forEach { row(it) }
                if (recording.isNotEmpty()) {
                    GroupHeader("撮影中")
                    recording.forEach { row(it) }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("${target.displayName} の画像を削除") },
            text = {
                Text(
                    "${target.count} 枚（${StatsStore.formatBytes(target.bytes)}）を削除します。" +
                        "取り消せません。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.packageName)
                    pendingDelete = null
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("やめる") }
            },
        )
    }
}

/// 一覧の絞り込み。アプリ名でもパッケージ名でも引ける。
/// アイコンフォントを足したくないので、見出しと「消す」は文字で済ませる
@Composable
private fun AppSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("アプリ名で絞り込む") },
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = { onQueryChange("") }) { Text("消す") }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun AppUsageRow(
    item: AppUsage,
    isExcluded: Boolean,
    onToggleExcluded: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isExcluded) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("${item.count} 枚")
                    // 0 枚なら容量は自明なので出さない
                    if (item.count > 0) append(" · ${StatsStore.formatBytes(item.bytes)}")
                    if (isExcluded) append(" · 撮影しない")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("⋮") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // 消すものが無い行に削除は出さない
                if (item.count > 0) {
                    DropdownMenuItem(
                        text = { Text("この ${item.count} 枚を削除") },
                        onClick = {
                            menuOpen = false
                            onRequestDelete()
                        },
                    )
                }
                if (item.packageName != null) {
                    DropdownMenuItem(
                        text = { Text(if (isExcluded) "撮影を再開する" else "以降このアプリは撮らない") },
                        onClick = {
                            menuOpen = false
                            onToggleExcluded()
                        },
                    )
                }
            }
        }
    }
}
