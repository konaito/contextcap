package app.imichat.contextcap.sub

import android.Manifest
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * ContextCap 本体（別アプリ）のアクセシビリティサービスを、Settings.Secure を直接書き換えて
 * OFF/ON する。書き換えには WRITE_SECURE_SETTINGS が要る（adb で 1 回付与）。
 *
 * ENABLED_ACCESSIBILITY_SERVICES はコロン区切りの ComponentName リスト。既存の他サービスを
 * 壊さないよう、必ず現在値を読んで対象だけ足し引きして書き戻す。
 */
object AccessibilityToggle {

    /** ContextCap 本体のアクセシビリティサービス（app.imichat.contextcap/.CaptureService） */
    val TARGET: ComponentName =
        ComponentName("app.imichat.contextcap", "app.imichat.contextcap.CaptureService")

    fun hasWriteSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isEnabled(cr: ContentResolver): Boolean =
        parse(read(cr)).contains(TARGET)

    /**
     * 対象を有効/無効にする。現在値から対象だけを足し引きし、全体フラグ(ACCESSIBILITY_ENABLED)も
     * 揃える。WRITE_SECURE_SETTINGS が無ければ SecurityException を投げる。
     */
    fun setEnabled(cr: ContentResolver, enabled: Boolean) {
        val set = parse(read(cr)).toMutableSet()
        if (enabled) set.add(TARGET) else set.remove(TARGET)
        Settings.Secure.putString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            set.joinToString(":") { it.flattenToString() },
        )
        // 1 つでも有効なら全体フラグ 1、空なら 0。
        Settings.Secure.putInt(
            cr,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if (set.isEmpty()) 0 else 1,
        )
    }

    private fun read(cr: ContentResolver): String =
        Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""

    private fun parse(value: String): Set<ComponentName> =
        value.split(':')
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .toSet()
}
