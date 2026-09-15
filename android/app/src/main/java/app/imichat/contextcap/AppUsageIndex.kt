package app.imichat.contextcap

/// `apps.jsonl` の変化点から「この時刻の画像はどのアプリか」を引く。
///
/// 記録は切り替わった瞬間だけなので、ある画像の所属は
/// **その時刻以前で最も新しい記録**で決まる。最初の記録より前の画像は不明（null）。
///
/// JSON のパースは Android 依存なので分けてある（`ForegroundAppLog.read`）。
/// ここは区間判定だけを持つ純粋ロジックで、unit test の対象。
class AppUsageIndex(entries: List<Entry>) {

    data class Entry(
        /// 画像のファイル名と同じ `HHmmss_SSS`
        val stem: String,
        val packageName: String,
        /// package visibility の制限で引けないことがあるので null 許容
        val label: String?,
    )

    /// 追記の順序が乱れていても引けるよう、読み込み時に整列しておく。
    /// `HHmmss_SSS` は固定長なので辞書順がそのまま時刻順になる。
    private val sorted = entries.sortedBy { it.stem }

    private val labels: Map<String, String> =
        entries.mapNotNull { e -> e.label?.let { e.packageName to it } }.toMap()

    /// その時刻の画像が属するパッケージ名。記録より前なら null
    fun packageAt(stem: String): String? {
        var found: String? = null
        for (entry in sorted) {
            if (entry.stem > stem) break
            found = entry.packageName
        }
        return found
    }

    /// 表示名。引けなかったパッケージは null
    fun labelFor(packageName: String): String? = labels[packageName]

    /// この日に記録された表示名すべて。
    /// **画像が 1 枚も残っていないパッケージの名前もここには残る**ので、
    /// 全部消したアプリを一覧に出すときの手掛かりになる。
    val knownLabels: Map<String, String> get() = labels
}
