package app.imichat.contextcap.sub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 再起動後、ユーザーが Guardian を有効にしていたら自動で起こす（WRITE_SECURE_SETTINGS は再起動後も保持される）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.guardianEnabled(context)) return
        runCatching { GuardianService.start(context) }
            .onFailure { Log.w("BootReceiver", "failed to start guardian on boot", it) }
    }
}
