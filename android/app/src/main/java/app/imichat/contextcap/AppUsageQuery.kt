package app.imichat.contextcap

/// アプリ別一覧の絞り込み。
///
/// 表示名とパッケージ名の**部分一致**（大文字小文字を区別しない）で引く。
/// 表示名を引けないアプリはパッケージ名しか手掛かりが無いので、両方を対象にする。
/// 並び替えはしない（呼び出し側が決めた順序をそのまま保つ）。
object AppUsageQuery {

    fun filter(items: List<AppUsage>, query: String): List<AppUsage> {
        val needle = query.trim()
        if (needle.isEmpty()) return items
        return items.filter { it.matches(needle) }
    }

    private fun AppUsage.matches(needle: String): Boolean =
        displayName.contains(needle, ignoreCase = true) ||
            packageName?.contains(needle, ignoreCase = true) == true
}
