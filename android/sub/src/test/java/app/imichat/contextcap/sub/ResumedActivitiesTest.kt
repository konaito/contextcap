package app.imichat.contextcap.sub

import app.imichat.contextcap.sub.ResumedActivities.Event
import app.imichat.contextcap.sub.ResumedActivities.Type.PAUSED
import app.imichat.contextcap.sub.ResumedActivities.Type.RESUMED
import app.imichat.contextcap.sub.ResumedActivities.Type.STOPPED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResumedActivitiesTest {

    private val payPay = "jp.ne.paypay.android.app"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val sensitive = setOf(payPay)

    private fun ResumedActivities.feed(vararg events: Event): ResumedActivities {
        events.forEach { apply(it) }
        prune()
        return this
    }

    /**
     * 2026-11-17 23:15:43〜23:16:12 の実機の UsageStats（秒以下は ms 単位で抜粋）。
     * 23:15:54.734 にランチャーの RESUMED が入るが、PayPay の MainActivity は 23:16:06.642 まで PAUSED にならない。
     */
    @Test
    fun cancelledHomeGestureKeepsPaymentAppInFront() {
        val s = ResumedActivities()
        s.feed(
            Event(43_280, RESUMED, launcher, "NexusLauncherActivity"),
            Event(44_902, PAUSED, launcher, "NexusLauncherActivity"),
            Event(44_912, RESUMED, payPay, "MiniAppActivity"),
            Event(46_339, PAUSED, payPay, "MiniAppActivity"),
            Event(46_344, RESUMED, payPay, "MainActivity"),
        )
        assertTrue(s.anyResumed(sensitive))

        // ホームへのスワイプを途中でやめた: ランチャーだけが RESUMED → PAUSED
        s.feed(Event(54_734, RESUMED, launcher, "NexusLauncherActivity"))
        assertTrue(s.anyResumed(sensitive), "PayPay は PAUSED になっていないので前面のまま")
        assertEquals(launcher, s.lastResumedPackage)

        s.feed(Event(57_383, PAUSED, launcher, "NexusLauncherActivity"))
        assertTrue(s.anyResumed(sensitive))

        // PayPay が設定のユーザー補助ページを開いた（ここで初めて PayPay が PAUSED）
        s.feed(
            Event(66_642, PAUSED, payPay, "MainActivity"),
            Event(66_645, RESUMED, "com.android.settings", "Settings\$AccessibilitySettingsActivity"),
        )
        assertFalse(s.anyResumed(sensitive))
    }

    @Test
    fun leavingPaymentAppReleases() {
        val s = ResumedActivities().feed(
            Event(1_000, RESUMED, payPay, "MainActivity"),
            Event(5_000, PAUSED, payPay, "MainActivity"),
            Event(5_001, RESUMED, launcher, "NexusLauncherActivity"),
        )
        assertFalse(s.anyResumed(sensitive))
    }

    /** 同じクラスの別インスタンスへの入れ替わり（X・Instagram・Google Pay で実測した並び） */
    @Test
    fun sameClassInstanceSwapKeepsNewInstance() {
        val s = ResumedActivities().feed(
            Event(40_700, RESUMED, payPay, "MiniAppActivity"),
            Event(40_817, PAUSED, payPay, "MiniAppActivity"),
            Event(40_818, RESUMED, payPay, "MiniAppActivity"),
            Event(41_217, STOPPED, payPay, "MiniAppActivity"),
        )
        assertTrue(s.anyResumed(sensitive), "STOPPED は前のインスタンスの後始末で、新しいほうは RESUMED のまま")
        s.feed(Event(50_000, PAUSED, payPay, "MiniAppActivity"))
        assertFalse(s.anyResumed(sensitive))
    }

    /** PAUSED を経ずに STOPPED が来ることがある（10 日で 73 件） */
    @Test
    fun stoppedWithoutPauseReleases() {
        val s = ResumedActivities().feed(
            Event(1_000, RESUMED, payPay, "MainActivity"),
            Event(3_000, STOPPED, payPay, "MainActivity"),
        )
        assertFalse(s.anyResumed(sensitive))
    }

    /** 期限を過ぎた PAUSED は STOPPED の相手にしない（再開した同じインスタンスの PAUSED が残り続けない） */
    @Test
    fun stalePauseDoesNotAbsorbLaterStop() {
        val s = ResumedActivities(pauseStopWindowMs = 10_000).feed(
            Event(1_000, RESUMED, payPay, "MainActivity"),
            Event(2_000, PAUSED, payPay, "MainActivity"),
            Event(3_000, RESUMED, payPay, "MainActivity"),
        )
        assertTrue(s.anyResumed(sensitive))
        // 2_000 の PAUSED は STOPPED にならないまま期限切れ。60_000 の STOPPED は RESUMED を外す
        s.feed(Event(60_000, STOPPED, payPay, "MainActivity"))
        assertFalse(s.anyResumed(sensitive))
    }

    /** 分割画面など、2 つが同時に RESUMED。後から別アプリが RESUMED になっても決済アプリは前面のまま */
    @Test
    fun multiResumeKeepsPaymentApp() {
        val s = ResumedActivities().feed(
            Event(1_000, RESUMED, payPay, "MainActivity"),
            Event(2_000, RESUMED, "com.twitter.android", "MainActivity"),
        )
        assertTrue(s.anyResumed(sensitive))
        assertEquals(setOf(payPay, "com.twitter.android"), s.resumedPackages())
    }

    /** 問い合わせの重なり区間で同じイベントを 2 回受けても、状態は 1 回分 */
    @Test
    fun overlappingQueriesApplyEachEventOnce() {
        val s = ResumedActivities()
        val resumed = Event(1_000, RESUMED, payPay, "MainActivity")
        val paused = Event(3_000, PAUSED, payPay, "MainActivity")
        s.feed(resumed, paused)
        // 次の問い合わせが重なり区間から始まり、同じ 2 件をもう一度返す
        s.feed(resumed, paused)
        assertFalse(s.anyResumed(sensitive))
    }

    /** 前回の問い合わせに間に合わなかった古いイベントが後から届いても、新しい状態を巻き戻さない */
    @Test
    fun lateOlderEventDoesNotRevertNewerState() {
        val s = ResumedActivities()
        s.feed(Event(3_000, PAUSED, payPay, "MainActivity"))
        s.feed(Event(1_000, RESUMED, payPay, "MainActivity"))
        assertFalse(s.anyResumed(sensitive))
    }

    /** 重なり区間より古い記録は捨てる（seen が増え続けない）。捨てた後も状態は保つ */
    @Test
    fun pruneKeepsState() {
        val s = ResumedActivities(overlapMs = 1_000)
        s.feed(Event(1_000, RESUMED, payPay, "MainActivity"))
        s.feed(Event(60_000, RESUMED, launcher, "NexusLauncherActivity"))
        assertTrue(s.anyResumed(sensitive))
        s.feed(Event(61_000, PAUSED, payPay, "MainActivity"))
        assertFalse(s.anyResumed(sensitive))
    }
}
