package app.imichat.contextcap.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/// 白ベース。撮った画像が主役なので、地は白のまま沈黙させて、
/// 色を持たせるのは「動いているか」を示す 2 箇所（記録中の緑・止まっている赤）だけにする。
///
/// dynamic color は使わない。端末の壁紙で色が変わると、
/// 記録中かどうかを色で判断できなくなるため。
private val Scheme = lightColorScheme(
    // 記録中であることを示す唯一のアクセント。白地で沈まない濃さにする
    primary = Color(0xFF176B43),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA4F4C4),
    onPrimaryContainer = Color(0xFF00210F),

    secondary = Color(0xFF4E6355),
    onSecondary = Color(0xFFFFFFFF),

    // 止まっている状態
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1A),

    // 画像の下敷きと境界。白地で画像の輪郭が溶けないようにする
    surfaceVariant = Color(0xFFF2F4F1),
    onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF717971),
    outlineVariant = Color(0xFFD5DBD2),
)

/// M3 Expressive のテーマ。標準の MaterialTheme より motion が強く、
/// 「動いている」ことを見せたいこのアプリに合う。
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContextCapTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = Scheme,
        content = content,
    )
}
