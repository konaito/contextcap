package app.imichat.contextcap.sub

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt

/**
 * セットアップ画面。権限の状態、adb コマンド、監視の開始/停止、対象アプリの編集を素の View で出す。
 * デザインより、何が足りていて何が要るかが一目で分かることを優先する。
 */
class MainActivity : android.app.Activity() {

    private val adbCommand =
        "adb shell pm grant app.imichat.contextcap.sub android.permission.WRITE_SECURE_SETTINGS"

    private lateinit var statusView: TextView
    private lateinit var sensitiveEdit: EditText
    private lateinit var toggleButton: Button

    private val pad by lazy { dp(16) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title("ContextCap 決済ガード"))
        root.addView(
            body(
                "PayPay など決済アプリを開いている間だけ、ContextCap のアクセシビリティを自動で " +
                    "OFF にして、離れたら自動で ON に戻します。",
            ),
        )

        statusView = body("").apply { setTypeface(Typeface.MONOSPACE) }
        root.addView(sectionLabel("状態"))
        root.addView(statusView)

        root.addView(sectionLabel("1. WRITE_SECURE_SETTINGS を付与（PC で 1 回）"))
        root.addView(
            body("PC に端末を USB 接続して、下のコマンドを 1 回実行する。再起動後も有効。"),
        )
        val cmdView = body(adbCommand).apply {
            setTypeface(Typeface.MONOSPACE)
            setTextIsSelectable(true)
            setBackgroundColor("#F2F2F2".toColorInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(cmdView)
        root.addView(
            button("コマンドをコピー") {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", adbCommand))
                toast("コピーした")
            },
        )

        root.addView(sectionLabel("2. 使用状況へのアクセスを許可"))
        root.addView(
            body("アクセシビリティを切っている間も前面アプリを追うために必要。"),
        )
        root.addView(
            button("使用状況アクセスの設定を開く") {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
        )

        root.addView(sectionLabel("3. 対象の決済アプリ（パッケージ名・1 行 1 個）"))
        root.addView(
            body("初期値は PayPay。下の「最後に検知した前面アプリ」を見て、対象アプリの正しい名前を足せる。"),
        )
        sensitiveEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTypeface(Typeface.MONOSPACE)
            setText(Prefs.sensitive(this@MainActivity).joinToString("\n"))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(sensitiveEdit)
        root.addView(
            button("対象アプリを保存") {
                val set = sensitiveEdit.text.toString()
                    .split('\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                Prefs.setSensitive(this, set)
                toast("保存した（${set.size} 個）")
            },
        )

        root.addView(sectionLabel("4. 監視"))
        toggleButton = button("") { toggleGuardian() }
        root.addView(toggleButton)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun toggleGuardian() {
        if (Prefs.guardianEnabled(this)) {
            GuardianService.stop(this)
            toast("監視を停止")
        } else {
            if (!AccessibilityToggle.hasWriteSecureSettings(this)) {
                toast("先に手順 1（WRITE_SECURE_SETTINGS）を済ませて")
            }
            if (!ForegroundApp.hasUsageAccess(this)) {
                toast("先に手順 2（使用状況アクセス）を許可して")
            }
            GuardianService.start(this)
            toast("監視を開始")
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val write = AccessibilityToggle.hasWriteSecureSettings(this)
        val usage = ForegroundApp.hasUsageAccess(this)
        val running = Prefs.guardianEnabled(this)
        val ccEnabled = runCatching { AccessibilityToggle.isEnabled(contentResolver) }.getOrDefault(false)
        val lastFg = Prefs.lastForeground(this) ?: "（未検知）"

        statusView.text = buildString {
            appendLine("WRITE_SECURE_SETTINGS : ${ok(write)}")
            appendLine("使用状況アクセス       : ${ok(usage)}")
            appendLine("監視                   : ${if (running) "稼働中" else "停止"}")
            appendLine("ContextCap 現在        : ${if (ccEnabled) "ON" else "OFF"}")
            append("最後に検知した前面アプリ: $lastFg")
        }
        toggleButton.text = if (running) "監視を停止" else "監視を開始"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun ok(value: Boolean): String = if (value) "付与済み" else "未付与"

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor("#131D40".toColorInt())
        setPadding(0, 0, 0, dp(8))
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor("#131D40".toColorInt())
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor("#222222".toColorInt())
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(6)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
