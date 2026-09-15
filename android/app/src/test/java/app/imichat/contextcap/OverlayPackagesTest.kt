package app.imichat.contextcap

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayPackagesTest {

    /// 実機 7 日分で 37%（9,483 枚）を誤帰属させていた張本人
    @Test
    fun systemUiIsOverlay() {
        assertTrue(OverlayPackages.isOverlay("com.android.systemui", emptySet()))
    }

    /// IME の一覧が取れなかった時も、systemui の除外だけは効かないと意味がない
    @Test
    fun systemUiIsOverlayEvenWithoutImeList() {
        assertTrue(OverlayPackages.isOverlay(OverlayPackages.SYSTEM_UI, emptySet()))
    }

    @Test
    fun enabledImeIsOverlay() {
        val ime = setOf("com.google.android.inputmethod.latin")
        assertTrue(OverlayPackages.isOverlay("com.google.android.inputmethod.latin", ime))
    }

    /// IME は決め打ちにしていない。一覧に無ければ普通のアプリとして扱う
    @Test
    fun unknownImeIsNotOverlayWhenNotEnabled() {
        assertFalse(OverlayPackages.isOverlay("com.google.android.inputmethod.latin", emptySet()))
    }

    /// ホーム画面は実際に前面にある。除外すると滞在の事実が消える
    @Test
    fun launcherIsNotOverlay() {
        assertFalse(
            OverlayPackages.isOverlay("com.google.android.apps.nexuslauncher", emptySet())
        )
    }

    /// 実データで前面アプリとして 82 枚記録されていた Pixel の画面制御
    @Test
    fun pixelDisplayServiceIsOverlay() {
        assertTrue(OverlayPackages.isOverlay("com.android.pixeldisplayservice", emptySet()))
    }

    @Test
    fun ordinaryAppsAreNotOverlay() {
        val ime = setOf("com.google.android.inputmethod.latin")
        for (pkg in listOf(
            "com.google.android.youtube",
            "com.twitter.android",
            "jp.konami.pesam",
            "app.imichat.contextcap",
        )) {
            assertFalse(OverlayPackages.isOverlay(pkg, ime), pkg)
        }
    }
}
