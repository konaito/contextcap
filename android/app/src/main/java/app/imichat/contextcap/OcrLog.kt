package app.imichat.contextcap

import java.io.File

/// OCR 結果は日付ディレクトリの `ocr.jsonl` に 1 行ずつ追記する。
/// `apps.jsonl` / `blackouts.jsonl` と同じ規約に揃えてあるので、回収は `adb pull` のまま。
///
/// なぜ SQLite ではないか: 解析は回収後に Mac 側でやる。端末に索引を持つ理由が無く、
/// jsonl なら追記が原子的に近く、途中で落ちても最後の 1 行を捨てるだけで済む。
/// `StatsStore` は `*.jpg` しか数えないので、容量集計にも影響しない。
///
/// **`org.json` と `android.util.Log` は使わない。** 使うと JVM unit test から検証できなくなる
/// （`BlackoutLog` と同じ理由）。JSON は組み立てと最小限のパースで足りる。
///
/// 1 行の形（`conf` は持たない — macOS 側の実測で「読んだ行の平均確信度」は行数が増えるほど
/// 下がる非単調な値で、取りこぼし率の代理変数にならないと分かっているため）:
///
///   {"t":"HHmmss_SSS","gen":0,"w":1080,"h":2400,"bytes":95000,
///    "lines":42,"ms":320,"status":"ok","text":"..."}
object OcrLog {
    const val FILE_NAME = "ocr.jsonl"

    data class Entry(
        val timeStem: String,
        val gen: Int,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val lines: Int,
        val ms: Long,
        /// "ok" | "empty"（文字が 1 つも無い）| "failed"
        val status: String,
        val text: String,
        val err: String? = null,
    )

    fun append(dayDir: File, entry: Entry) {
        val sb = StringBuilder(entry.text.length + 128)
        // `t` は必ず先頭に置く。indexedStems がここだけを安く読むため
        sb.append("""{"t":"""").append(entry.timeStem).append('"')
        sb.append(""","gen":""").append(entry.gen)
        sb.append(""","w":""").append(entry.width)
        sb.append(""","h":""").append(entry.height)
        sb.append(""","bytes":""").append(entry.bytes)
        sb.append(""","lines":""").append(entry.lines)
        sb.append(""","ms":""").append(entry.ms)
        sb.append(""","status":"""").append(entry.status).append('"')
        sb.append(""","text":"""").append(escape(entry.text)).append('"')
        entry.err?.let { sb.append(""","err":"""").append(escape(it)).append('"') }
        sb.append('}')
        // 書けなくても落とさない。取りこぼした分は次回の reconcile が拾い直す
        runCatching { File(dayDir, FILE_NAME).appendText(sb.append('\n').toString()) }
    }

    /// その日の OCR 済み timeStem。`Retention` の削除ガードと `OcrIndexer` の追いつきが使う。
    /// status が failed でも「読もうとした」記録なので含める（無限に再試行しないため）。
    ///
    /// `t` は `HHmmss_SSS`（SimpleDateFormat の出力）なので引用符もバックスラッシュも
    /// 含まない。だから行頭の固定位置を正規表現で拾えば足りる。JSON 全体をパースしない。
    fun indexedStems(dayDir: File): Set<String> {
        val file = File(dayDir, FILE_NAME)
        if (!file.isFile) return emptySet()
        val out = HashSet<String>()
        runCatching {
            file.forEachLine { line ->
                // 途中で落ちた最後の 1 行が壊れていることがある。合わない行は捨てる
                STEM_PATTERN.find(line)?.groupValues?.get(1)?.let { out.add(it) }
            }
        }
        return out
    }

    private val STEM_PATTERN = Regex("""^\{"t":"([^"]+)"""")

    /// JSON 文字列としての最小限のエスケープ。OCR テキストには改行も引用符も入る
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                // 制御文字はそのまま出すと JSON として壊れる
                c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
