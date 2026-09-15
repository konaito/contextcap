package app.imichat.contextcap

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt

/// `FLAG_SECURE` を立てた画面で `takeScreenshot` が
/// `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`（= 6）を返し、
/// CaptureService がクラッシュせず次の tick に進むことを確認するための debug 専用画面。
///
/// debug ソースセットにあるので release APK には含まれない。
///
/// 使い方:
///   adb shell am start -n app.imichat.contextcap/.SecureTestActivity
///   adb logcat -s ContextCap   # "screenshot failed: 6" が出る
class SecureTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#B00020".toColorInt())
            setPadding(56, 240, 56, 56)
        }
        root.addView(
            TextView(this).apply {
                setText(R.string.secure_test_title)
                textSize = 26f
                setTextColor(Color.WHITE)
            },
        )
        root.addView(
            TextView(this).apply {
                setText(R.string.secure_test_body)
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(0, 32, 0, 0)
            },
        )
        setContentView(root)
    }
}
