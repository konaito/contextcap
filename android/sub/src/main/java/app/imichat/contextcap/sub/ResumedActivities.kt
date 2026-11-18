package app.imichat.contextcap.sub

/**
 * UsageStats の ACTIVITY_* イベントを順に受けて、「いま RESUMED のまま残っている Activity」の集合を持つ。
 * Android の型に依存しない純ロジック（JVM unit test で確かめる）。
 *
 * 「直近の最後の RESUMED」だけで前面を決めない。ホームへのスワイプを途中でやめると、
 * ランチャーの RESUMED → PAUSED だけが記録され、決済アプリは PAUSED にならないまま前面に残る
 * （2026-11-17 23:15:54 の実機ログと、2026-11-19 の再現）。最後の RESUMED を前面とみなすと、
 * 決済アプリの前面で ContextCap を ON に戻してしまい、決済アプリに検知される。
 *
 * 取りこぼしと二重適用を避けるため、呼び出し側は前回の終わりより少し前から問い合わせてよい。
 * 同じイベントは 1 回しか適用せず、同じ Activity の古いイベントが後から届いても無視する。
 */
class ResumedActivities(private val overlapMs: Long = DEFAULT_OVERLAP_MS) {

    enum class Type { RESUMED, PAUSED, STOPPED }

    data class Event(val time: Long, val type: Type, val pkg: String, val cls: String)

    /** "pkg/cls" → パッケージ名。RESUMED で入り、PAUSED・STOPPED で出る */
    private val resumed = LinkedHashMap<String, String>()

    /** Activity ごとに、最後に適用したイベントの時刻 */
    private val lastApplied = HashMap<String, Long>()

    /** 重なり区間で同じイベントを 2 回適用しないための記録 */
    private val seen = HashSet<Event>()
    private var newest = Long.MIN_VALUE

    /** 最後に RESUMED になったパッケージ（UI に出す参考値。前面の判定には使わない） */
    var lastResumedPackage: String? = null
        private set

    fun apply(e: Event) {
        if (!seen.add(e)) return
        if (e.time > newest) newest = e.time
        val key = "${e.pkg}/${e.cls}"
        val prev = lastApplied[key]
        if (prev != null && e.time < prev) return
        lastApplied[key] = e.time
        when (e.type) {
            Type.RESUMED -> {
                resumed[key] = e.pkg
                lastResumedPackage = e.pkg
            }
            Type.PAUSED, Type.STOPPED -> resumed.remove(key)
        }
    }

    /** 1 回の問い合わせ分を適用し終えたら呼ぶ。重なり区間より古い記録を捨てる */
    fun prune() {
        if (newest == Long.MIN_VALUE) return
        val cutoff = newest - overlapMs
        seen.removeAll { it.time < cutoff }
    }

    fun resumedPackages(): Set<String> = resumed.values.toSet()

    fun anyResumed(packages: Set<String>): Boolean = resumed.values.any { it in packages }

    companion object {
        const val DEFAULT_OVERLAP_MS = 5_000L
    }
}
