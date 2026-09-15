package app.imichat.contextcap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUsageIndexTest {

    private fun index(vararg pairs: Pair<String, String>) =
        AppUsageIndex(pairs.map { AppUsageIndex.Entry(it.first, it.second, null) })

    @Test
    fun `記録が無ければ常に不明`() {
        assertNull(index().packageAt("120000_000"))
    }

    @Test
    fun `最初の記録より前の画像は不明`() {
        val i = index("120000_000" to "com.a")
        assertNull(i.packageAt("115959_999"))
    }

    @Test
    fun `記録と同じ時刻はその記録のアプリ`() {
        val i = index("120000_000" to "com.a")
        assertEquals("com.a", i.packageAt("120000_000"))
    }

    @Test
    fun `記録の間の画像は直前の記録のアプリ`() {
        val i = index(
            "120000_000" to "com.a",
            "121000_000" to "com.b",
        )
        assertEquals("com.a", i.packageAt("120500_000"))
        assertEquals("com.b", i.packageAt("121500_000"))
    }

    @Test
    fun `最後の記録より後はずっとそのアプリ`() {
        val i = index(
            "120000_000" to "com.a",
            "121000_000" to "com.b",
        )
        assertEquals("com.b", i.packageAt("235959_999"))
    }

    @Test
    fun `同じアプリに戻った記録も正しく引ける`() {
        val i = index(
            "120000_000" to "com.a",
            "121000_000" to "com.b",
            "122000_000" to "com.a",
        )
        assertEquals("com.a", i.packageAt("120500_000"))
        assertEquals("com.b", i.packageAt("121500_000"))
        assertEquals("com.a", i.packageAt("122500_000"))
    }

    @Test
    fun `記録が時刻順に並んでいなくても正しく引ける`() {
        // 追記の順序が乱れたファイルを読んでも壊れないこと
        val i = index(
            "122000_000" to "com.c",
            "120000_000" to "com.a",
            "121000_000" to "com.b",
        )
        assertEquals("com.a", i.packageAt("120500_000"))
        assertEquals("com.b", i.packageAt("121500_000"))
        assertEquals("com.c", i.packageAt("122500_000"))
    }

    @Test
    fun `表示名は引ける時だけ返る`() {
        val i = AppUsageIndex(
            listOf(
                AppUsageIndex.Entry("120000_000", "com.a", "Alpha"),
                AppUsageIndex.Entry("121000_000", "com.b", null),
            ),
        )
        assertEquals("Alpha", i.labelFor("com.a"))
        assertNull(i.labelFor("com.b"))
        assertNull(i.labelFor("com.unknown"))
    }
}
