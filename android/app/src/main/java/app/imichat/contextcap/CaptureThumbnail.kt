package app.imichat.contextcap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/// 保存済み JPEG を表示用に縮小デコードする。
///
/// 1080×2364 を素朴に読むと ARGB_8888 で 1 枚あたり 10MB 近くになる。
/// 表示に必要な大きさまで `inSampleSize` で落としてから読む。
object CaptureThumbnail {

    /// 長辺が `maxPixel` 以下になるよう縮小してデコードする。失敗したら null
    fun decode(file: File, maxPixel: Int): Bitmap? {
        if (maxPixel <= 0) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(longest, maxPixel)
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull()
    }

    /// エンコード済みの JPEG から直接縮小デコードする。
    /// 保存前の判定に使うので、ファイルに落とす前に呼べる形にしてある
    fun decode(jpeg: ByteArray, maxPixel: Int): Bitmap? {
        if (maxPixel <= 0 || jpeg.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(longest, maxPixel)
            }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        }.getOrNull()
    }

    /// 長辺 `longest` の画像を `maxPixel` 以下にするための inSampleSize（2 の冪）
    fun sampleSizeFor(longest: Int, maxPixel: Int): Int {
        if (longest <= 0 || maxPixel <= 0) return 1
        var sample = 1
        while (longest / (sample * 2) >= maxPixel) sample *= 2
        return sample
    }
}
