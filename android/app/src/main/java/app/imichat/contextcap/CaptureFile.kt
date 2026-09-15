package app.imichat.contextcap

import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// 保存ファイルの命名規約: <root>/YYYY-MM-DD/HHmmss_SSS[.gN].jpg
/// 撮影時刻はファイル属性ではなくパスから解釈する（再圧縮でファイルが作り直されても不変）。
object CaptureFile {
    const val DAY_FORMAT = "yyyy-MM-dd"
    const val TIME_FORMAT = "HHmmss_SSS"

    // SimpleDateFormat はスレッドセーフでないため都度生成する。
    // lenient を切らないと "notadate" のような文字列を誤って解釈する
    private fun parser() = SimpleDateFormat("$DAY_FORMAT $TIME_FORMAT", Locale.US).apply {
        isLenient = false
    }

    fun dayDirName(date: Date): String = SimpleDateFormat(DAY_FORMAT, Locale.US).format(date)

    fun timeStem(date: Date): String = SimpleDateFormat(TIME_FORMAT, Locale.US).format(date)

    /// 過去に段階圧縮していた頃の接尾辞。**新規ファイルには付かない。**
    /// 2026-08-18 に再圧縮を廃止したが、既に .gN が付いたファイルが残っている環境では
    /// これを読めないと撮影時刻の解釈と OCR の同一性判定が壊れる。消さないこと。
    val LEGACY_GENERATION_SUFFIXES = listOf(1 to "g1", 2 to "g2", 3 to "g3")

    /// "162303_273.jpg" → 0, "162303_273.g2.jpg" → 2
    fun generationOf(file: File): Int {
        val stem = file.name.substringBeforeLast('.')
        for ((gen, suffix) in LEGACY_GENERATION_SUFFIXES) {
            if (stem.endsWith(".$suffix")) return gen
        }
        return 0
    }

    /// gen 接尾辞を除いた基底名（"162303_273.g1" → "162303_273"）
    fun baseNameOf(file: File): String {
        val stem = file.name.substringBeforeLast('.')
        for ((_, suffix) in LEGACY_GENERATION_SUFFIXES) {
            if (stem.endsWith(".$suffix")) return stem.dropLast(suffix.length + 1)
        }
        return stem
    }

    /// 親ディレクトリ名 + 基底名から撮影時刻を解釈する。規約外の名前なら null
    fun captureDateOf(file: File): Date? {
        val day = file.parentFile?.name ?: return null
        return try {
            parser().parse("$day ${baseNameOf(file)}")
        } catch (e: ParseException) {
            null
        }
    }
}
