package app.imichat.contextcap

import java.io.File
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/// 保存済みスクショの枚数・期間・容量を集計する。
/// 生成時にフルスキャンし、以降は撮影ごとに増分更新する。
class StatsStore(val root: File) {

    var count: Int = 0
        private set
    var totalBytes: Long = 0
        private set
    var firstDate: Date? = null
        private set
    var lastDate: Date? = null
        private set

    init {
        rescan()
    }

    /// ディレクトリをフルスキャンして実測値に合わせる。
    ///
    /// 日付のパースは最古と最新の 2 回だけにする。`<YYYY-MM-DD>/<HHmmss_SSS>` は文字列順が
    /// そのまま時刻順なので、1 枚ずつ SimpleDateFormat に通す必要がない（通すと走査時間の
    /// 4 割がパースになっていた）。形式に合わない名前だけ従来どおり個別に解釈する
    fun rescan() {
        var newCount = 0
        var newBytes = 0L
        var minKey: String? = null
        var maxKey: String? = null
        var minFile: File? = null
        var maxFile: File? = null
        var oddFirst: Date? = null
        var oddLast: Date? = null

        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "jpg" }
            .forEach { file ->
                newCount += 1
                newBytes += file.length()
                val day = file.parentFile?.name.orEmpty()
                val stem = CaptureFile.baseNameOf(file)
                if (DAY_PATTERN.matches(day) && STEM_PATTERN.matches(stem)) {
                    val key = "$day $stem"
                    if (minKey.let { it == null || key < it }) {
                        minKey = key
                        minFile = file
                    }
                    if (maxKey.let { it == null || key > it }) {
                        maxKey = key
                        maxFile = file
                    }
                } else {
                    val captured = CaptureFile.captureDateOf(file) ?: Date(file.lastModified())
                    val first = oddFirst
                    val last = oddLast
                    if (first == null || captured.before(first)) oddFirst = captured
                    if (last == null || captured.after(last)) oddLast = captured
                }
            }

        val parsedFirst = minFile?.let { CaptureFile.captureDateOf(it) }
        val parsedLast = maxFile?.let { CaptureFile.captureDateOf(it) }

        count = newCount
        totalBytes = newBytes
        firstDate = listOfNotNull(parsedFirst, oddFirst).minOrNull()
        lastDate = listOfNotNull(parsedLast, oddLast).maxOrNull()
    }

    /// 撮影 1 枚分の増分更新
    fun recordCapture(bytes: Long, date: Date) {
        count += 1
        totalBytes += bytes
        if (firstDate == null) firstDate = date
        lastDate = date
    }

    /// Retention が消した分の差し引き。フルスキャンし直さないためにある。
    /// 43,519 枚で 1 回十数秒かかる走査を、削除のたびに撮影 worker で回していた。
    /// firstDate は更新しない（ここでは最古の残りが分からない）。常駐側が使うのは
    /// 容量判定の totalBytes だけで、画面の表示は `CaptureSnapshot` が別にスキャンする
    fun recordDeletion(count: Int, bytes: Long) {
        this.count = (this.count - count).coerceAtLeast(0)
        totalBytes = (totalBytes - bytes).coerceAtLeast(0)
    }

    // MARK: - 表示用フォーマット

    val countText: String
        get() = "${NumberFormat.getIntegerInstance(Locale.US).format(count)} 枚"

    val sizeText: String get() = formatBytes(totalBytes)

    val durationText: String
        get() {
            val first = firstDate ?: return "—"
            val last = lastDate ?: return "—"
            if (!last.after(first)) return "—"

            val seconds = (last.time - first.time) / 1000
            val days = seconds / 86_400
            val hours = (seconds % 86_400) / 3_600
            val minutes = (seconds % 3_600) / 60
            return when {
                days > 0 -> "${days}日と${hours}時間"
                hours > 0 -> "${hours}時間${minutes}分"
                else -> "${minutes}分"
            }
        }

    companion object {
        /// 文字列順が時刻順になる名前の形（`CaptureFile.DAY_FORMAT` / `TIME_FORMAT`）
        private val DAY_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
        private val STEM_PATTERN = Regex("""\d{6}_\d{3}""")

        /// macOS 版の ByteCountFormatter(.file) に合わせて 1000 進で表示する
        fun formatBytes(bytes: Long): String {
            if (bytes < 1000) return "$bytes bytes"
            val units = listOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1000
            var index = 0
            while (value >= 1000 && index < units.size - 1) {
                value /= 1000
                index += 1
            }
            return String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }
}
