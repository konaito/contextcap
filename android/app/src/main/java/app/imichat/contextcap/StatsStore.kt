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

    /// ディレクトリをフルスキャンして実測値に合わせる
    fun rescan() {
        var newCount = 0
        var newBytes = 0L
        var newFirst: Date? = null
        var newLast: Date? = null

        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "jpg" }
            .forEach { file ->
                newCount += 1
                newBytes += file.length()
                val captured = CaptureFile.captureDateOf(file) ?: Date(file.lastModified())
                val currentFirst = newFirst
                val currentLast = newLast
                if (currentFirst == null || captured.before(currentFirst)) newFirst = captured
                if (currentLast == null || captured.after(currentLast)) newLast = captured
            }

        count = newCount
        totalBytes = newBytes
        firstDate = newFirst
        lastDate = newLast
    }

    /// 撮影 1 枚分の増分更新
    fun recordCapture(bytes: Long, date: Date) {
        count += 1
        totalBytes += bytes
        if (firstDate == null) firstDate = date
        lastDate = date
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
