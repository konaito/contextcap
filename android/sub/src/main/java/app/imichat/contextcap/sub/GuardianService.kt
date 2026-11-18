package app.imichat.contextcap.sub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
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
 * 決済アプリの前面監視。1 秒ごとに RESUMED のまま残っている Activity を見て、
 *  - 決済アプリの Activity が RESUMED → ContextCap のアクセシビリティを OFF
 *  - 決済アプリの Activity が 1 つも RESUMED でない → sub が切った分だけ ON に戻す
 * を行う。画面オフの間は tick を止める（止まっていた間のイベントは次の tick でまとめて読む）。
 * ロック画面の間は ON に戻さない。決済アプリの前面で画面を消すと決済アプリは PAUSED になるが、
 * ロック解除で決済アプリへ戻ることが多く、ロック画面で戻すと解除の直後に決済アプリから見えてしまう。
 *
 * sub が切っていないのに ContextCap が OFF のとき（手で切った、決済アプリに促されて切った）は、
 * 自動では戻さず、通知に OFF であることと「ON に戻す」を出す。
 */
class GuardianService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var foreground: ForegroundApp
    private lateinit var keyguard: KeyguardManager
    private var ticking = false
    private var lastError: String? = null

    /** 通知に出している状態。変わったときだけ通知を出し直す */
    private var shownStatus: Status? = null

    private enum class Status { WATCHING, OFF_NOT_OURS }

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
        foreground = ForegroundApp(getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
        keyguard = getSystemService(KeyguardManager::class.java)
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
        if (intent?.action == ACTION_ENABLE) enableFromNotification()
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
        val state = foreground.poll()
        state.lastResumedPackage
            ?.takeIf { it != packageName && it != Prefs.lastForeground(this) }
            ?.let { Prefs.setLastForeground(this, it) }

        val front = state.resumedPackages().intersect(Prefs.sensitive(this))
        val sensitive = front.isNotEmpty()
        try {
            val enabled = AccessibilityToggle.isEnabled(contentResolver)
            if (sensitive) {
                if (enabled) {
                    AccessibilityToggle.setEnabled(contentResolver, false)
                    Prefs.setWeDisabled(this, true)
                    Log.i(TAG, "disabled ContextCap accessibility for $front")
                }
            } else if (!keyguard.isKeyguardLocked) {
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
                shownStatus = null
            }
            val status = if (!AccessibilityToggle.isEnabled(contentResolver) && !Prefs.weDisabled(this)) {
                Status.OFF_NOT_OURS
            } else {
                Status.WATCHING
            }
            if (status != shownStatus) {
                if (status == Status.OFF_NOT_OURS) {
                    Log.i(TAG, "ContextCap accessibility is OFF and was not disabled by sub; leaving it")
                }
                shownStatus = status
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
        val offNotOurs = lastError == null && shownStatus == Status.OFF_NOT_OURS
        val text = when {
            lastError != null -> lastError
            offNotOurs -> "ContextCap が OFF のまま（sub が切ったものではないので自動では戻さない）"
            else -> "決済アプリの前面で ContextCap を自動一時停止"
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (offNotOurs) "ContextCap が止まっている" else "ContextCap 保護を監視中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
        if (offNotOurs) {
            val enable = PendingIntent.getService(
                this,
                1,
                Intent(this, GuardianService::class.java).setAction(ACTION_ENABLE),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(null, "ON に戻す", enable).build(),
            )
        }
        return builder.build()
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

    /** 通知の「ON に戻す」。決済アプリが前面なら戻さない（次の tick で切ることになるため） */
    private fun enableFromNotification() {
        val front = foreground.poll().resumedPackages().intersect(Prefs.sensitive(this))
        if (front.isNotEmpty()) {
            Log.i(TAG, "enable requested from notification, but $front is in front; skipped")
            return
        }
        try {
            AccessibilityToggle.setEnabled(contentResolver, true)
            Log.i(TAG, "re-enabled ContextCap accessibility from notification")
        } catch (se: SecurityException) {
            Log.w(TAG, "missing WRITE_SECURE_SETTINGS; cannot enable", se)
        }
        shownStatus = null
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
        private const val ACTION_ENABLE = "app.imichat.contextcap.sub.action.ENABLE"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, GuardianService::class.java))
        }

        fun stop(ctx: Context) {
            Prefs.setGuardianEnabled(ctx, false)
            ctx.stopService(Intent(ctx, GuardianService::class.java))
        }
    }
}
