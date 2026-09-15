package app.imichat.contextcap

import java.io.File

/// 保存済みスクショを新しい順に取り出す。
///
/// **全件走査をしない**のが要件。日付ディレクトリ名を降順に辿り、必要な件数が揃った時点で打ち切る。
/// `StatsStore` のフルスキャンは 1 件あたり約 0.05ms かかり、10 万件で 5 秒（ANR 閾値）に達する。
/// 閲覧のたびにそれを払うわけにはいかないので、こちらは見る分だけ読む。
object CaptureBrowser {

    /// 最新 `limit` 件を新しい順に返す
    fun latest(root: File, limit: Int): List<File> {
        if (limit <= 0) return emptyList()

        val dayDirs = root.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?: return emptyList()

        val result = mutableListOf<File>()
        for (dir in dayDirs) {
            val files = dir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() == "jpg" }
                // gen 接尾辞を除いた基底名（HHmmss_SSS）で並べる。
                // 同じ時刻に世代違いが並ぶことはないが、接尾辞込みで並べると順序が狂う
                ?.sortedByDescending { CaptureFile.baseNameOf(it) }
                ?: continue

            for (file in files) {
                result.add(file)
                if (result.size >= limit) return result
            }
        }
        return result
    }
}
