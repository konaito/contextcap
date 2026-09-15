package app.imichat.contextcap

import org.json.JSONObject
import java.io.File

/// アプリごとの枚数と容量。
/// `packageName` が null の行は「記録より前 / apps.jsonl が無い日」の画像をまとめたもの。
data class AppUsage(
    val packageName: String?,
    val label: String?,
    val count: Int,
    val bytes: Long,
) {
    val displayName: String get() = label ?: packageName ?: "不明"
}

/// 保存済みの画像をアプリ別に集計する。
///
/// **全件走査になる**。実測で 1 件あたり約 0.05ms なので 10 万件で 5 秒。
/// UI スレッドから呼ばないこと。キャッシュは持たない（遅くなってから考える）。
///
/// `readIndex` を外から差し込めるのは、`apps.jsonl` のパースが `org.json`（Android 依存）で、
/// JVM の unit test では動かないため。走査と集計だけを Android なしで検証する。
object AppUsageStats {

    /// 日付ディレクトリごとに `apps.jsonl` を読み、その日の画像を所属アプリへ振り分ける。
    ///
    /// `excluded`（撮影しないアプリ）は**画像が 1 枚も無くても 0 枚の行として返す**。
    /// 行が消えると「どれを止めたか」が分からなくなり、解除もできなくなる。
    fun collect(
        root: File,
        excluded: Set<String> = emptySet(),
        readIndex: (File) -> AppUsageIndex = ::readIndexFile,
    ): List<AppUsage> {
        val counts = mutableMapOf<String?, Int>()
        val bytes = mutableMapOf<String?, Long>()
        val labels = mutableMapOf<String, String>()

        root.listFiles()?.filter { it.isDirectory }?.forEach { dayDir ->
            val index = readIndex(dayDir)
            // 画像の有無と関係なく、その日に記録された表示名を全部拾う。
            // 画像を全部消したアプリの名前を残すのはここだけ
            labels.putAll(index.knownLabels)
            dayDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() == "jpg" }
                ?.forEach { file ->
                    val pkg = index.packageAt(CaptureFile.baseNameOf(file))
                    counts[pkg] = (counts[pkg] ?: 0) + 1
                    bytes[pkg] = (bytes[pkg] ?: 0L) + file.length()
                }
        }

        // 画像が残っていない除外アプリを 0 枚として補う
        excluded.forEach { pkg -> counts.putIfAbsent(pkg, 0) }

        return counts.map { (pkg, count) ->
            AppUsage(
                packageName = pkg,
                label = pkg?.let { labels[it] },
                count = count,
                bytes = bytes[pkg] ?: 0L,
            )
        }.sortedWith(ORDER)
    }

    /// 一覧の並び。0 枚の行が複数あっても順序が揺れないよう、同数なら表示名で決める。
    /// 表示名を後から補った時も同じ並びで出せるよう外へ出してある
    val ORDER: Comparator<AppUsage> =
        compareByDescending<AppUsage> { it.count }.thenBy { it.displayName }

    /// 指定パッケージの画像を全部消す。消した枚数を返す。
    /// `packageName` が null なら「不明」の画像が対象。
    ///
    /// `apps.jsonl` は消さない。表示名と「何を見ていたか」の記録は画像と別の価値がある。
    fun deleteAll(
        root: File,
        packageName: String?,
        readIndex: (File) -> AppUsageIndex = ::readIndexFile,
    ): Int {
        var deleted = 0
        root.listFiles()?.filter { it.isDirectory }?.forEach { dayDir ->
            val index = readIndex(dayDir)
            dayDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() == "jpg" }
                ?.forEach { file ->
                    if (index.packageAt(CaptureFile.baseNameOf(file)) == packageName) {
                        if (file.delete()) deleted += 1
                    }
                }
        }
        return deleted
    }

    /// 日付ディレクトリの `apps.jsonl` を読む。無ければ空の index（全部「不明」になる）
    private fun readIndexFile(dayDir: File): AppUsageIndex {
        val file = File(dayDir, ForegroundAppLog.FILE_NAME)
        if (!file.isFile) return AppUsageIndex(emptyList())

        val entries = runCatching {
            file.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching {
                    val json = JSONObject(line)
                    AppUsageIndex.Entry(
                        stem = json.getString("t"),
                        packageName = json.getString("pkg"),
                        label = if (json.has("label")) json.getString("label") else null,
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())

        return AppUsageIndex(entries)
    }
}
