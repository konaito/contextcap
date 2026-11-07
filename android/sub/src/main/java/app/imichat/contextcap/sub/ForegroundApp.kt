package app.imichat.contextcap.sub

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * UsageStats から「今いちばん手前に出ているアプリ」を読む。アクセシビリティを切っている間も
 * 前面を追えるのがこの方式の肝（アクセシビリティ経由だと、切った瞬間に自分が見えなくなる）。
 */
object ForegroundApp {

    /** 直近 10 秒のイベントから、最後に前面へ出たパッケージを返す。取れなければ null */
    fun current(usm: UsageStatsManager): String? {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000L, now + 1_000L)
        var pkg: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                pkg = event.packageName
            }
        }
        return pkg
    }

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
