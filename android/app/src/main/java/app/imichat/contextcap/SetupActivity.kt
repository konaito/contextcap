package app.imichat.contextcap

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import app.imichat.contextcap.ui.ContextCapTheme
import app.imichat.contextcap.ui.HomeScreen

/// 「今どうなっているか」を見る画面。
/// 設定を並べる画面ではないので、主役は撮れている画像と状態。
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        setContent {
            ContextCapTheme {
                HomeScreen(
                    onOpenViewer = { index ->
                        startActivity(
                            Intent(this, ViewerActivity::class.java)
                                .putExtra(ViewerActivity.EXTRA_INDEX, index),
                        )
                    },
                )
            }
        }
    }
}
