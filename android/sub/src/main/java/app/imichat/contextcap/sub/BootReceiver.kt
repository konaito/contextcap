package app.imichat.contextcap.sub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 再起動後と sub の更新後に、ユーザーが Guardian を有効にしていたら自動で起こす
 * （WRITE_SECURE_SETTINGS は再起動後も保持される）。更新でプロセスが落ちたあと、
 * これが無いと画面には「稼働中」と出たまま監視が止まる。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!Prefs.guardianEnabled(context)) return
        runCatching { GuardianService.start(context) }
            .onFailure { Log.w("BootReceiver", "failed to start guardian on ${intent.action}", it) }
    }
}
