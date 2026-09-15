package app.imichat.contextcap

import androidx.activity.ComponentActivity
import android.os.Bundle
import androidx.activity.compose.setContent
import app.imichat.contextcap.ui.ContextCapTheme
import app.imichat.contextcap.ui.ViewerScreen

/// 撮影済みの画像を全画面で 1 枚ずつ見る。
class ViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val start = intent.getIntExtra(EXTRA_INDEX, 0)
        setContent {
            ContextCapTheme {
                ViewerScreen(startIndex = start)
            }
        }
    }


    companion object {
        const val EXTRA_INDEX = "index"
    }
}
