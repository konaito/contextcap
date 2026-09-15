package app.imichat.contextcap

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.util.Date

/// 前面アプリが切り替わった時刻を日付ごとの JSONL に残す。
///
/// **切り替わった瞬間だけ書く。** 撮影のたびに書くと 1 日 8,000 行を超えてファイルが肥大するが、
/// 変化点だけなら 1 日数十行で収まる。解析側は「この時刻以降はこのアプリ」と読めばいい。
///
/// 画像のファイル名（`HHmmss_SSS`）を `t` に入れて対応付ける。
/// 命名規約は macOS 版と共通なので、そちらを壊さないために別ファイルへ分ける。
class ForegroundAppLog(private val context: Context) {

    /// パッケージ名 → 表示名。PackageManager への問い合わせは毎回やると重い
    private val labels = mutableMapOf<String, String>()

    private var lastDay: String? = null
    private var lastPackage: String? = null

    /// 撮影 1 枚分。前回と同じアプリなら何も書かない。
    /// 日付が変わったら、同じアプリでもその日の最初の 1 行を書く。
    fun record(dayDir: File, date: Date, stem: String, packageName: String?) {
        if (packageName == null) return
        val day = CaptureFile.dayDirName(date)
        if (day == lastDay && packageName == lastPackage) return

        val label = labelFor(packageName)
        val line = JSONObject().apply {
            put("t", stem)
            put("pkg", packageName)
            // Android 11+ の package visibility 制限で表示名を引けないことがある。
            // その場合 labelFor はパッケージ名を返すので、同じ値を二重に書かない。
            // 引くために QUERY_ALL_PACKAGES を足すことはしない（権限を増やさない方針）
            if (label != packageName) put("label", label)
        }.toString()

        runCatching { File(dayDir, FILE_NAME).appendText(line + "\n") }
            .onSuccess {
                lastDay = day
                lastPackage = packageName
            }
    }

    private fun labelFor(packageName: String): String = labels.getOrPut(packageName) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    companion object {
        const val FILE_NAME = "apps.jsonl"
    }
}
