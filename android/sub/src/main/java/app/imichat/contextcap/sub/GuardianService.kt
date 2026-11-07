package app.imichat.contextcap.sub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 決済アプリの前面監視。1 秒ごとに前面アプリを見て、
 *  - 決済アプリが前面になった → ContextCap のアクセシビリティを OFF
 *  - 決済アプリから離れた     → sub が切った分だけ ON に戻す
 * を行う。画面オフの間は tick を止める。
 */
class GuardianService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var usm: UsageStatsManager
    private var ticking = false
    private var lastError: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> startTicking()
                Intent.ACTION_SCREEN_OFF -> stopTicking()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            step()
            if (ticking) handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createChannel()
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.setGuardianEnabled(this, true)
        startForegroundCompat()
        startTicking()
        return START_STICKY
    }

    override fun onDestroy() {
        stopTicking()
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.post(tick)
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(tick)
    }

    private fun step() {
        val fg = ForegroundApp.current(usm) ?: return
        if (fg == packageName) return
        Prefs.setLastForeground(this, fg)

        val sensitive = Prefs.sensitive(this).contains(fg)
        try {
            val enabled = AccessibilityToggle.isEnabled(contentResolver)
            if (sensitive) {
                if (enabled) {
                    AccessibilityToggle.setEnabled(contentResolver, false)
                    Prefs.setWeDisabled(this, true)
                    Log.i(TAG, "disabled ContextCap accessibility for $fg")
                }
            } else {
                if (Prefs.weDisabled(this)) {
                    if (!enabled) {
                        AccessibilityToggle.setEnabled(contentResolver, true)
                        Log.i(TAG, "re-enabled ContextCap accessibility after leaving payment app")
                    }
                    // enabled のまま(= ユーザーが手で戻した)なら所有権だけ手放す
                    Prefs.setWeDisabled(this, false)
                }
            }
            if (lastError != null) {
                lastError = null
                updateNotification()
            }
        } catch (se: SecurityException) {
            if (lastError == null) {
                lastError = "WRITE_SECURE_SETTINGS 未付与"
                updateNotification()
                Log.w(TAG, "missing WRITE_SECURE_SETTINGS; cannot toggle", se)
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "決済アプリ監視",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "決済アプリの前面時に ContextCap を自動で一時停止します" }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = lastError ?: "決済アプリの前面で ContextCap を自動一時停止"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ContextCap 保護を監視中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification())
    }

    companion object {
        private const val TAG = "GuardianService"
        private const val CHANNEL_ID = "guardian"
        private const val NOTIF_ID = 1
        private const val INTERVAL_MS = 1_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, GuardianService::class.java))
        }

        fun stop(ctx: Context) {
            Prefs.setGuardianEnabled(ctx, false)
            ctx.stopService(Intent(ctx, GuardianService::class.java))
        }
    }
}
