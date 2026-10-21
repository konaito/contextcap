package app.imichat.contextcap

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom
import java.util.Base64

/// 「パソコンから読む」（`RemoteServer`）の設定。撮影パスと画面の両方から読むので、Compose に依存させない。
///
/// **既定はオフ。** オンにした人だけが tailnet に口を開ける。以前はアクセシビリティを有効にした
/// 時点で無条件に待ち受けていて、利用者はそれを知る手段も止める手段も無かった。
///
/// 合鍵は端末ごとにランダムに作り、`MODE_PRIVATE` の SharedPreferences にだけ置く。
/// 作り直すと古い URL は使えなくなる。
object RemoteAccess {
    const val KEY_ENABLED = "RemoteEnabled"
    const val KEY_TOKEN = "RemoteToken"

    private fun prefs(context: Context) =
        context.getSharedPreferences(CaptureService.PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        // オンにする時点で合鍵を用意しておく。サービス側で作ると画面と食い違うことがある
        if (enabled) token(context)
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    /// 合鍵。まだ無ければ作って保存する
    fun token(context: Context): String {
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        return newToken().also { prefs(context).edit { putString(KEY_TOKEN, it) } }
    }

    fun regenerateToken(context: Context): String =
        newToken().also { prefs(context).edit { putString(KEY_TOKEN, it) } }

    /// 32 バイトの乱数を URL にそのまま載せられる形（base64url・パディングなし、43 文字）にする
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /// PC から開く URL。ブラウザでも curl でもそのまま使える
    fun url(address: String, token: String): String =
        "http://$address:${CaptureService.REMOTE_PORT}/?token=$token"
}
