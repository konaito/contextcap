package app.imichat.contextcap

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.imichat.contextcap.ui.ContextCapTheme
import app.imichat.contextcap.ui.TextScreen

/// 読み取った文字を日ごとに読む。画像が容量上限で消えた日も、文字はここから読める。
class TextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        setContent {
            ContextCapTheme {
                TextScreen(
                    onOpenImage = { file ->
                        startActivity(
                            Intent(this, ViewerActivity::class.java)
                                .putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath),
                        )
                    },
                )
            }
        }
    }
}
