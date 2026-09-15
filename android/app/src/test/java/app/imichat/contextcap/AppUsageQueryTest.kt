package app.imichat.contextcap

import kotlin.test.Test
import kotlin.test.assertEquals

class AppUsageQueryTest {

    private val items = listOf(
        AppUsage(packageName = "com.google.chrome", label = "Chrome", count = 10, bytes = 100),
        AppUsage(packageName = "jp.naver.line.android", label = "LINE", count = 5, bytes = 50),
        AppUsage(packageName = "com.example.bank", label = null, count = 0, bytes = 0),
        AppUsage(packageName = null, label = null, count = 3, bytes = 30),
    )

    private fun names(query: String) =
        AppUsageQuery.filter(items, query).map { it.displayName }

    @Test
    fun `空のクエリは全部返す`() {
        assertEquals(4, AppUsageQuery.filter(items, "").size)
        assertEquals(4, AppUsageQuery.filter(items, "   ").size)
    }

    @Test
    fun `表示名の部分一致で引ける`() {
        assertEquals(listOf("Chrome"), names("hro"))
    }

    @Test
    fun `大文字小文字は区別しない`() {
        assertEquals(listOf("Chrome"), names("CHROME"))
        assertEquals(listOf("LINE"), names("line"))
    }

    @Test
    fun `表示名を引けてもパッケージ名で引ける`() {
        assertEquals(listOf("LINE"), names("naver"))
    }

    @Test
    fun `表示名を引けないアプリはパッケージ名で引ける`() {
        assertEquals(listOf("com.example.bank"), names("bank"))
    }

    @Test
    fun `前後の空白は無視する`() {
        assertEquals(listOf("Chrome"), names("  chrome  "))
    }

    @Test
    fun `不明の行は「不明」で引ける`() {
        assertEquals(listOf("不明"), names("不明"))
    }

    @Test
    fun `一致しなければ空`() {
        assertEquals(emptyList(), names("該当なし"))
    }

    @Test
    fun `渡された並び順のまま返す`() {
        assertEquals(listOf("Chrome", "com.example.bank"), names("com."))
    }
}
