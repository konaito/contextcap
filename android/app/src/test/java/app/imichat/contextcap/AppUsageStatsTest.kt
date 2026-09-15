package app.imichat.contextcap

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUsageStatsTest {

    private val root: File = Files.createTempDirectory("contextcap-usage").toFile()

    /// 日付ディレクトリごとの索引。テストでは JSON を経由せず直接組む
    private val indexes = mutableMapOf<String, AppUsageIndex>()

    private val readIndex: (File) -> AppUsageIndex = { dayDir ->
        indexes[dayDir.name] ?: AppUsageIndex(emptyList())
    }

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun write(day: String, stem: String, bytes: Int) {
        val dir = File(root, day).apply { mkdirs() }
        File(dir, "$stem.jpg").writeBytes(ByteArray(bytes))
    }

    /// 実機では `apps.jsonl` が日付ディレクトリの中にあるので、記録があれば必ずディレクトリも在る
    private fun index(day: String, vararg entries: Triple<String, String, String?>) {
        File(root, day).mkdirs()
        indexes[day] = AppUsageIndex(
            entries.map { AppUsageIndex.Entry(it.first, it.second, it.third) },
        )
    }

    private fun collect(excluded: Set<String> = emptySet()) =
        AppUsageStats.collect(root, excluded, readIndex)

    @Test
    fun `画像を所属アプリごとに数える`() {
        index("2026-08-13", Triple("100000_000", "com.a", "Alpha"), Triple("110000_000", "com.b", "Bravo"))
        write("2026-08-13", "100000_000", 100)
        write("2026-08-13", "103000_000", 100)
        write("2026-08-13", "110000_000", 300)

        val usage = collect()

        assertEquals(2, usage.size)
        val alpha = usage.first { it.packageName == "com.a" }
        assertEquals(2, alpha.count)
        assertEquals(200L, alpha.bytes)
        assertEquals("Alpha", alpha.label)
        val bravo = usage.first { it.packageName == "com.b" }
        assertEquals(1, bravo.count)
        assertEquals(300L, bravo.bytes)
    }

    @Test
    fun `記録より前の画像は不明としてまとまる`() {
        index("2026-08-13", Triple("110000_000", "com.a", "Alpha"))
        write("2026-08-13", "100000_000", 100)

        val usage = collect()

        assertEquals(1, usage.size)
        assertNull(usage[0].packageName)
        assertEquals("不明", usage[0].displayName)
    }

    @Test
    fun `除外アプリは画像が 0 枚でも行として残る`() {
        // 「全部消したうえで撮影も止めた」状態。行が消えると解除できなくなる
        index("2026-08-13", Triple("100000_000", "com.a", "Alpha"))
        write("2026-08-13", "100000_000", 100)

        val usage = collect(excluded = setOf("com.b"))

        val bravo = usage.first { it.packageName == "com.b" }
        assertEquals(0, bravo.count)
        assertEquals(0L, bravo.bytes)
    }

    @Test
    fun `画像が残っている除外アプリの行を二重に作らない`() {
        index("2026-08-13", Triple("100000_000", "com.a", "Alpha"))
        write("2026-08-13", "100000_000", 100)

        val usage = collect(excluded = setOf("com.a"))

        assertEquals(1, usage.count { it.packageName == "com.a" })
        assertEquals(1, usage.first { it.packageName == "com.a" }.count)
    }

    @Test
    fun `画像を消しても apps_jsonl に記録が残っていれば表示名は復元される`() {
        // 画像だけ削除された日。索引は残っているので名前が引ける
        index("2026-08-13", Triple("100000_000", "com.a", "Alpha"))

        val usage = collect(excluded = setOf("com.a"))

        assertEquals("Alpha", usage.first { it.packageName == "com.a" }.label)
    }

    @Test
    fun `名前を引けない除外アプリはパッケージ名で出す`() {
        val usage = collect(excluded = setOf("com.ghost"))

        val ghost = usage.first { it.packageName == "com.ghost" }
        assertNull(ghost.label)
        assertEquals("com.ghost", ghost.displayName)
    }

    @Test
    fun `枚数の多い順に並ぶ`() {
        index(
            "2026-08-13",
            Triple("100000_000", "com.a", "Alpha"),
            Triple("110000_000", "com.b", "Bravo"),
        )
        write("2026-08-13", "100000_000", 100)
        write("2026-08-13", "110000_000", 100)
        write("2026-08-13", "111000_000", 100)

        val usage = collect(excluded = setOf("com.zero"))

        assertEquals(listOf("com.b", "com.a", "com.zero"), usage.map { it.packageName })
    }

    @Test
    fun `deleteAll は対象アプリの画像だけ消す`() {
        index(
            "2026-08-13",
            Triple("100000_000", "com.a", "Alpha"),
            Triple("110000_000", "com.b", "Bravo"),
        )
        write("2026-08-13", "100000_000", 100)
        write("2026-08-13", "110000_000", 100)

        val deleted = AppUsageStats.deleteAll(root, "com.a", readIndex)

        assertEquals(1, deleted)
        assertTrue(!File(root, "2026-08-13/100000_000.jpg").exists())
        assertTrue(File(root, "2026-08-13/110000_000.jpg").exists())
    }
}
