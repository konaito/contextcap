package app.imichat.contextcap

import java.io.File

/// 日付ディレクトリに残っている OCR テキストの一覧。
///
/// 画像は `Retention` が容量上限で消すが、`ocr.jsonl` は消さない。
/// 実機（2026-10-21）では画像が 10 日分しか残っていないのに、テキストは 65 日分あった。
/// 「記録している期間」を画像だけで数えると、残っているテキストが見えなくなる。
///
/// ディレクトリ名と `ocr.jsonl` の有無・サイズだけを見る。jsonl の中身は読まない
/// （65 日分で 164MB あり、ホーム画面の 5 秒周期で読む量ではない）。
object TextArchive {

    data class Day(
        /// `YYYY-MM-DD`
        val name: String,
        val dir: File,
        val bytes: Long,
    )

    /// テキストが残っている日を新しい順に返す
    fun days(root: File): List<Day> =
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val file = File(dir, OcrLog.FILE_NAME)
                if (file.isFile) Day(dir.name, dir, file.length()) else null
            }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /// 同じ文字が続いた区間。5 秒おきに撮るので、同じ画面を見ている間は同じ文字が何十行も並ぶ
    data class Passage(
        val firstStem: String,
        val lastStem: String,
        val count: Int,
        val text: String,
        /// 区間内で画像がまだ残っている最も新しい 1 枚。全部消えていれば null
        val imageStem: String?,
    )

    /// 1 日分の OCR 結果を、文字が変わるところで区切る。文字の無い画面（empty / failed）は除く
    fun passages(entries: List<OcrLog.Entry>, imageStems: Set<String>): List<Passage> {
        val out = ArrayList<Passage>()
        for (e in entries) {
            if (e.status != "ok" || e.text.isBlank()) continue
            val image = e.timeStem.takeIf { it in imageStems }
            val last = out.lastOrNull()
            if (last != null && last.text == e.text) {
                out[out.size - 1] = last.copy(
                    lastStem = e.timeStem,
                    count = last.count + 1,
                    imageStem = image ?: last.imageStem,
                )
            } else {
                out.add(Passage(e.timeStem, e.timeStem, 1, e.text, image))
            }
        }
        return out
    }

    /// 日付ディレクトリに残っている画像。基底名（`HHmmss_SSS`）→ ファイル名。
    /// 旧形式の `.gN` 接尾辞付きも同じ基底名で引ける。stat しないよう名前だけで見る
    fun images(dayDir: File): Map<String, String> =
        dayDir.list()
            ?.filter { it.lowercase().endsWith(".jpg") }
            ?.associateBy { CaptureFile.baseNameOf(File(it)) }
            ?: emptyMap()

    /// "093015_123" → "09:30:15"
    fun clockText(stem: String): String =
        if (stem.length >= 6) "${stem.substring(0, 2)}:${stem.substring(2, 4)}:${stem.substring(4, 6)}" else stem

    /// 「65 日分（2026-08-14〜）」。1 日も無ければ "—"
    fun spanText(days: List<Day>): String {
        if (days.isEmpty()) return "—"
        val first = days.minOf { it.name }
        return "${days.size} 日分（$first〜）"
    }
}
