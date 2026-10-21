package app.imichat.contextcap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.imichat.contextcap.CaptureService
import app.imichat.contextcap.OcrLog
import app.imichat.contextcap.StatsStore
import app.imichat.contextcap.TextArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/// 1 区間で最初に見せる行数。1 画面 100 行を超えることがあるので、開くまでは畳む
private const val COLLAPSED_LINES = 8

/// 読み取った文字を日ごとに読む。日の一覧 → その日の文字の並び、の 2 段。
@Composable
fun TextScreen(onOpenImage: (File) -> Unit) {
    val context = LocalContext.current
    val root = remember { CaptureService.captureRoot(context) }
    var days by remember { mutableStateOf<List<TextArchive.Day>?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        days = withContext(Dispatchers.IO) { TextArchive.days(root) }
    }

    BackHandler(enabled = selected != null) { selected = null }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val day = selected?.let { name -> days?.firstOrNull { it.name == name } }
            if (day == null) {
                DayList(days = days, onSelect = { selected = it.name })
            } else {
                DayText(day = day, onBack = { selected = null }, onOpenImage = onOpenImage)
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DayList(days: List<TextArchive.Day>?, onSelect: (TextArchive.Day) -> Unit) {
    Header(
        title = "読み取った文字",
        subtitle = "画像が消えた日も、文字は残っています",
    )
    when {
        days == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        days.isEmpty() -> Text(
            text = "まだ何も読み取れていません",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(days, key = { it.name }) { day ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(day) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = day.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = StatsStore.formatBytes(day.bytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DayText(day: TextArchive.Day, onBack: () -> Unit, onOpenImage: (File) -> Unit) {
    var passages by remember(day.name) { mutableStateOf<List<TextArchive.Passage>?>(null) }
    var images by remember(day.name) { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 1 日分は最大 4〜5MB。全部メモリに載せても足りるが、パースは IO スレッドで
    LaunchedEffect(day.name) {
        withContext(Dispatchers.IO) {
            val found = TextArchive.images(day.dir)
            images = found
            passages = TextArchive.passages(OcrLog.readDay(day.dir), found.keys)
        }
    }

    val list = passages
    // 見出しも一緒に流す。横向きだと見出しだけで画面の半分を使う
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) {
                Text("← 日付の一覧")
            }
            Header(
                title = day.name,
                subtitle = list?.let { "${it.size} 区間（同じ文字が続く間は 1 つにまとめています）" } ?: "読んでいます",
            )
        }
        when {
            list == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
            list.isEmpty() -> item(key = "empty") {
                Text(
                    text = "この日は文字が読み取れていません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            else -> items(list, key = { it.firstStem }) { passage ->
                PassageItem(
                    passage = passage,
                    onOpenImage = passage.imageStem?.let { images[it] }?.let { name ->
                        { onOpenImage(File(day.dir, name)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun PassageItem(passage: TextArchive.Passage, onOpenImage: (() -> Unit)?) {
    var expanded by rememberSaveable(passage.firstStem) { mutableStateOf(false) }
    val lineCount = remember(passage.text) { passage.text.count { it == '\n' } + 1 }

    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append(TextArchive.clockText(passage.firstStem))
                    if (passage.count > 1) {
                        append("〜").append(TextArchive.clockText(passage.lastStem))
                        append(" · ").append(passage.count).append(" 枚")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            if (onOpenImage != null) {
                TextButton(onClick = onOpenImage) { Text("画像") }
            }
        }
        SelectionContainer {
            Text(
                text = passage.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (lineCount > COLLAPSED_LINES) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "畳む" else "続き（全 $lineCount 行）")
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}
