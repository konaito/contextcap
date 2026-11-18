package app.imichat.contextcap.sub

/**
 * UsageStats の ACTIVITY_* イベントを順に受けて、「いま RESUMED のまま残っている Activity」を持つ。
 * Android の型に依存しない純ロジック（JVM unit test で確かめる）。
 *
 * 「直近の最後の RESUMED」だけで前面を決めない。ホームへのスワイプを途中でやめると、
 * ランチャーの RESUMED → PAUSED だけが記録され、決済アプリは PAUSED にならないまま前面に残る
 * （2026-11-17 23:15:54 の実機ログと、2026-11-19 の再現）。最後の RESUMED を前面とみなすと、
 * 決済アプリの前面で ContextCap を ON に戻してしまい、決済アプリに検知される。
 *
 * 公開 API ではイベントのインスタンス ID が取れないので、パッケージ名とクラス名ごとに数える。
 * 同じクラスの別インスタンスは `A1 PAUSED → A2 RESUMED → A1 STOPPED` の順に並ぶ（X・Instagram・
 * Google Pay で実測）。STOPPED で一律に外すと A2 まで外れ、PAUSED を経ない STOPPED もある（10 日で 73 件）。
 * そこで、STOPPED は直近 [pauseStopWindowMs] 以内の PAUSED があればその後始末とみなし、
 * 無ければ RESUMED の 1 つを外す。10 日分 22,081 件をインスタンス ID つきの正解と比べ、
 * 期限 10 秒で「正解に無いのに RESUMED とした」0 件、「見落とした」4 件（権限ダイアログ）だった。
 * 期限なしは 118,319 件、30 秒は 9,488 件ずれた（`android/sub/scripts/eval-foreground.py`）。
 *
 * 取りこぼしと二重適用を避けるため、呼び出し側は前回の終わりより少し前から問い合わせてよい。
 * 同じイベントは 1 回しか適用せず、同じ Activity の古いイベントが後から届いても無視する。
 */
class ResumedActivities(
    private val overlapMs: Long = DEFAULT_OVERLAP_MS,
    private val pauseStopWindowMs: Long = PAUSE_STOP_WINDOW_MS,
) {

    enum class Type { RESUMED, PAUSED, STOPPED }

    data class Event(val time: Long, val type: Type, val pkg: String, val cls: String)

    private class Slot(val pkg: String) {
        var resumed = 0

        /** PAUSED のあと、まだ STOPPED が来ていないものの時刻 */
        val pendingPauses = ArrayDeque<Long>()

        /** 最後に適用したイベントの時刻 */
        var lastApplied = Long.MIN_VALUE
    }

    /** "pkg/cls" ごとの数 */
    private val slots = HashMap<String, Slot>()

    /** 重なり区間で同じイベントを 2 回適用しないための記録 */
    private val seen = HashSet<Event>()
    private var newest = Long.MIN_VALUE

    /** 最後に RESUMED になったパッケージ（UI に出す参考値。前面の判定には使わない） */
    var lastResumedPackage: String? = null
        private set

    fun apply(e: Event) {
        if (!seen.add(e)) return
        if (e.time > newest) newest = e.time
        val slot = slots.getOrPut("${e.pkg}/${e.cls}") { Slot(e.pkg) }
        if (e.time < slot.lastApplied) return
        slot.lastApplied = e.time
        while (slot.pendingPauses.isNotEmpty() && e.time - slot.pendingPauses.first() > pauseStopWindowMs) {
            slot.pendingPauses.removeFirst()
        }
        when (e.type) {
            Type.RESUMED -> {
                slot.resumed++
                lastResumedPackage = e.pkg
            }
            Type.PAUSED -> {
                if (slot.resumed > 0) slot.resumed--
                slot.pendingPauses.addLast(e.time)
            }
            Type.STOPPED -> {
                if (slot.pendingPauses.isNotEmpty()) {
                    slot.pendingPauses.removeFirst()
                } else if (slot.resumed > 0) {
                    slot.resumed--
                }
            }
        }
    }

    /** 1 回の問い合わせ分を適用し終えたら呼ぶ。重なり区間より古い記録を捨てる */
    fun prune() {
        if (newest == Long.MIN_VALUE) return
        val cutoff = newest - overlapMs
        seen.removeAll { it.time < cutoff }
    }

    fun resumedPackages(): Set<String> =
        slots.values.filter { it.resumed > 0 }.mapTo(HashSet()) { it.pkg }

    fun anyResumed(packages: Set<String>): Boolean =
        slots.values.any { it.resumed > 0 && it.pkg in packages }

    companion object {
        const val DEFAULT_OVERLAP_MS = 5_000L
        const val PAUSE_STOP_WINDOW_MS = 10_000L
    }
}
