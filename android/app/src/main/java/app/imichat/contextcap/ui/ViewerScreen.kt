package app.imichat.contextcap.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import app.imichat.contextcap.CaptureBrowser
import app.imichat.contextcap.CaptureService
import app.imichat.contextcap.CaptureThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/// 遡れる枚数の上限。閲覧のたびに全件を読まないための打ち切り
private const val MAX_ITEMS = 300

/// 1 枚を全画面で見る。前後ボタンで遡る。
///
/// スワイプにしないのは、ページャが増える分の依存と実装に見合わないため。
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ViewerScreen(startIndex: Int) {
    val context = LocalContext.current
    val root = remember { CaptureService.captureRoot(context) }
    // 画面の長辺（px）。これ以上大きくデコードしても表示に使われず、メモリを食うだけ。
    // containerSize は px で返るので dp からの換算が要らない
    val containerSize = LocalWindowInfo.current.containerSize
    val screenLongest = maxOf(containerSize.width, containerSize.height)

    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var index by remember { mutableIntStateOf(startIndex) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) { CaptureBrowser.latest(root, MAX_ITEMS) }
        files = found
        index = startIndex.coerceIn(0, maxOf(0, found.size - 1))
    }

    // 表示中の 1 枚だけをデコードする。まとめて持たない
    LaunchedEffect(files, index) {
        val target = files.getOrNull(index)
        if (target == null) {
            image = null
            loading = files.isEmpty()
            return@LaunchedEffect
        }
        loading = true
        image = withContext(Dispatchers.IO) {
            CaptureThumbnail.decode(target, screenLongest)?.asImageBitmap()
        }
        loading = false
    }

    // ここだけは黒地のまま。画像の色を正しく見るための場所で、
    // 白地だと明るい画像との境界が分からなくなる
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    image != null -> Image(
                        bitmap = image!!,
                        contentDescription = files.getOrNull(index)?.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    loading -> LoadingIndicator()
                    else -> Text(
                        text = "まだ何も撮れていません",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            val current = files.getOrNull(index)
            Text(
                text = if (current == null) {
                    ""
                } else {
                    "${index + 1} / ${files.size}   ${current.parentFile?.name} ${current.name}"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )

            // 今見ている 1 枚を消す。消したら次（新しい方が無ければ古い方）へ送る
            TextButton(
                onClick = {
                    val target = files.getOrNull(index) ?: return@TextButton
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { target.delete() }
                        if (ok) {
                            val remaining = files.filterIndexed { i, _ -> i != index }
                            files = remaining
                            index = index.coerceAtMost(maxOf(0, remaining.size - 1))
                        }
                    }
                },
                enabled = current != null,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 4.dp),
            ) {
                Text("この 1 枚を削除", color = Color(0xFFFFB4AB))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // リストは新しい順なので、+1 が古い方向
                FilledTonalButton(
                    onClick = { if (index < files.size - 1) index += 1 },
                    enabled = index < files.size - 1,
                    modifier = Modifier.weight(1f),
                ) { Text("← 古い") }
                FilledTonalButton(
                    onClick = { if (index > 0) index -= 1 },
                    enabled = index > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("新しい →") }
            }
        }
    }
}
