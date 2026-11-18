package app.imichat.contextcap.sub

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * UsageStats から「いま RESUMED のまま残っている Activity」を追う。アクセシビリティを切っている間も
 * 前面を追えるのがこの方式の肝（アクセシビリティ経由だと、切った瞬間に自分が見えなくなる）。
 *
 * 毎回、前回の問い合わせの終わりから（重なりを持たせて）続きのイベントだけを読み、状態を持ち越す。
 * 画面オフで問い合わせが止まっても、次の問い合わせで止まっていた間のイベントをまとめて読む。
 */
class ForegroundApp(private val usm: UsageStatsManager) {

    val state = ResumedActivities()
    private var cursor = System.currentTimeMillis() - BOOTSTRAP_MS

    /** 前回の続きのイベントを読んで状態を進める */
    fun poll(): ResumedActivities {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(cursor - ResumedActivities.DEFAULT_OVERLAP_MS, now + 1)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> ResumedActivities.Type.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> ResumedActivities.Type.PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> ResumedActivities.Type.STOPPED
                else -> continue
            }
            state.apply(
                ResumedActivities.Event(
                    event.timeStamp,
                    type,
                    event.packageName,
                    event.className ?: "",
                ),
            )
        }
        state.prune()
        cursor = now
        return state
    }

    companion object {
        /** 起動直後に読む過去の幅。起動前から前面にある決済アプリを拾うため */
        private const val BOOTSTRAP_MS = 6 * 60 * 60 * 1000L

        @Suppress("DEPRECATION")
        fun hasUsageAccess(ctx: Context): Boolean {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
