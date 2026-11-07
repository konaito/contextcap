package app.imichat.contextcap.sub

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** sub の設定と状態。素の SharedPreferences。 */
object Prefs {
    private const val NAME = "contextcap_sub"
    private const val KEY_SENSITIVE = "sensitive_packages"
    private const val KEY_WE_DISABLED = "we_disabled"
    private const val KEY_GUARDIAN = "guardian_enabled"
    private const val KEY_LAST_FG = "last_foreground"

    /** 初期値は PayPay。実機で前面パッケージを見て足し引きする（UI に最後の前面を出す） */
    val DEFAULT_SENSITIVE: Set<String> = setOf("jp.ne.paypay.android.app")

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun sensitive(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_SENSITIVE, null) ?: DEFAULT_SENSITIVE

    fun setSensitive(ctx: Context, packages: Set<String>) {
        prefs(ctx).edit { putStringSet(KEY_SENSITIVE, packages) }
    }

    /** sub 自身がアクセシビリティを切ったか。切った分だけ後で戻す（手動 OFF と喧嘩しない） */
    fun weDisabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_WE_DISABLED, false)

    fun setWeDisabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit { putBoolean(KEY_WE_DISABLED, value) }
    }

    fun guardianEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_GUARDIAN, false)

    fun setGuardianEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit { putBoolean(KEY_GUARDIAN, value) }
    }

    fun lastForeground(ctx: Context): String? = prefs(ctx).getString(KEY_LAST_FG, null)

    fun setLastForeground(ctx: Context, pkg: String) {
        prefs(ctx).edit { putString(KEY_LAST_FG, pkg) }
    }
}
