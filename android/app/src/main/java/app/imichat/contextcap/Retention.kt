package app.imichat.contextcap

import java.io.File
import java.util.Date

data class RetentionResult(
    var deleted: Int = 0,
    var freedBytes: Long = 0,
    /// OCR がまだ済んでいないので消せなかった枚数。0 でないなら OCR が詰まっている
    var blockedByOcr: Int = 0,
)

/// 保持期間を過ぎた画像を削除する。**再圧縮はしない。**
///
/// なぜ段階再圧縮（旧 Compactor）を捨てたか:
/// 段階圧縮は「テキストが無いから画像を捨てられない」という前提の産物だった。
/// OCR が撮影に先行するなら、テキストが本体になって画像は一時キャッシュになる。
/// そして再圧縮には実害しかない — macOS 版の同一画像の統制実験（60 枚・行数分位で層化）で、
/// 圧縮しても OCR は速くならず（時間は解像度ではなく行数に比例）テキストだけが壊れた:
///
///   段            平均行数   文字保持   行近似一致(≥0.85)
///   gen0（等倍）    101.0     1.000        1.000
///   g1 (1920/q60)   97.3     0.948        0.751
///   g2 (1280/q50)   88.5     0.891        0.568
///   g3 (800/q40)    24.1     0.258        0.056
///
/// 容量の見積り（2026-08-18 実測・1080x2400 の端末）: 1 枚 95.3KB。
/// 10GB には約 10.5 万枚入る。理論最大 8,640 枚/日 でも 823MB/日 なので 12 日分。
/// 観測レート（802 枚/日）なら 130 日分。macOS 版（1 枚 813KB / 4 日）より桁が違うので、
/// 保持日数も別の値にしてある。
///
/// **削除は不可逆なので、OCR が済んでいないファイルは絶対に消さない。**
/// 消せずに容量が上限を超え続ける場合は `blockedByOcr` で表に出す。黙って消さない。
///
/// 走査・判断を Android 依存なしで単体テストできるよう、`now` と `budgetBytes` を注入する。
class Retention(
    private val root: File,
    private val budgetBytes: Long,
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(
        val file: File,
        val size: Long,
        val time: Long,
        val dayDir: File,
        val stem: String,
    )

    /// 削除パスを 1 回実行する。消すものが無ければ null。
    fun sweep(): RetentionResult? {
        val entries = scan()
        if (entries.isEmpty()) return null

        val result = RetentionResult()
        var total = entries.sumOf { it.size }
        val cutoff = now() - retentionDays * DAY_MS

        // OCR 済みの集合は日付ディレクトリごとに 1 回だけ読む（1 枚ずつ読むと jsonl を
        // 何千回もパースすることになる）
        val indexedByDay = HashMap<String, Set<String>>()

        // 古い順に見る。消す条件は「OCR 済み」かつ「保持期間外 または 容量超過」
        for (entry in entries.sortedBy { it.time }) {
            val tooOld = entry.time < cutoff
            val overBudget = total > budgetBytes
            if (!tooOld && !overBudget) break

            val indexed = indexedByDay.getOrPut(entry.dayDir.name) {
                OcrLog.indexedStems(entry.dayDir)
            }
            if (entry.stem !in indexed) {
                // まだ読めていない。消したらテキストごと永久に失われる
                result.blockedByOcr++
                continue
            }
            if (!entry.file.delete()) continue
            total -= entry.size
            result.deleted++
            result.freedBytes += entry.size
        }

        if (result.deleted == 0 && result.blockedByOcr == 0) return null
        if (result.deleted > 0) removeEmptyDayDirectories()
        return result
    }

    private fun scan(): List<Entry> {
        val dayDirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val out = ArrayList<Entry>()
        for (dayDir in dayDirs) {
            val files = dayDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: continue
            for (file in files) {
                // 撮影時刻はファイル属性ではなくパスから読む（保存形式の契約）
                val date: Date? = CaptureFile.captureDateOf(file)
                out.add(Entry(
                    file = file,
                    size = file.length(),
                    time = date?.time ?: file.lastModified(),
                    dayDir = dayDir,
                    stem = CaptureFile.baseNameOf(file),
                ))
            }
        }
        return out
    }

    /// jpg が 1 枚も無くなった日でも、apps.jsonl / blackouts.jsonl / ocr.jsonl は残す。
    /// テキストと「何を見ていたか」は画像が無くても正解データになるので消さない。
    /// 完全に空になったディレクトリだけ片付ける。
    private fun removeEmptyDayDirectories() {
        val dayDirs = root.listFiles { f -> f.isDirectory } ?: return
        for (dir in dayDirs) {
            if (dir.listFiles()?.isEmpty() == true) dir.delete()
        }
    }

    companion object {
        /// 1 枚 95.3KB・理論最大 8,640 枚/日 = 823MB/日。14 日で 11.5GB になるので
        /// 上限（既定 10GB）側でも削られる。観測レートなら十分収まる
        const val DEFAULT_RETENTION_DAYS = 14
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
