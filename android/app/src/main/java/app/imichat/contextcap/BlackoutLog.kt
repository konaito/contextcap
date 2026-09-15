package app.imichat.contextcap

import java.io.File
import java.util.Date

/// 情報のない画面（DRM 保護・AOD）が続いた区間を日付ごとの JSONL に残す。
///
/// **画像は保存しないが、観測できなかったという事実は残す。** 2 時間の映画なら
/// 720 枚の真っ黒な JPEG が 2 行になる。`apps.jsonl` と突き合わせれば
/// 「この区間は何のアプリで、画面は取れていない」まで復元できる。
///
/// `apps.jsonl` と同じ変化点方式で、状態が変わった瞬間だけ 1 行書く。
/// 日付が変わった時は、同じ状態でもその日の最初の 1 行を書く（日ごとに読めるようにするため）。
///
/// 書く値は `HHmmss_SSS` と固定の状態名だけなので、JSON は組み立てで足りる
/// （`org.json` を使うと JVM unit test から検証できなくなる）。
class BlackoutLog {

    private var lastDay: String? = null
    private var lastUninformative: Boolean? = null

    /// 撮影 1 枚分。状態が前回と同じなら何も書かない。
    fun record(dayDir: File, date: Date, stem: String, uninformative: Boolean) {
        val day = CaptureFile.dayDirName(date)
        val wasBlack = lastUninformative == true
        val dayRolled = lastDay != null && day != lastDay

        // 黒に入った時と、黒のまま日付が変わった時に start。黒から抜けた時だけ end。
        // 起動直後の 1 枚目が情報ありでも end を書かない（区間が始まっていない）
        val needStart = uninformative && (!wasBlack || dayRolled)
        val needEnd = !uninformative && wasBlack
        if (!needStart && !needEnd) {
            lastDay = day
            lastUninformative = uninformative
            return
        }

        val state = if (uninformative) STATE_START else STATE_END
        val line = """{"t":"$stem","state":"$state"}"""
        runCatching { File(dayDir, FILE_NAME).appendText(line + "\n") }
            .onSuccess {
                lastDay = day
                lastUninformative = uninformative
            }
    }

    companion object {
        const val FILE_NAME = "blackouts.jsonl"
        const val STATE_START = "start"
        const val STATE_END = "end"
    }
}
