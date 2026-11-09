package com.example.screenshotposter

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

object ImageProcessor {

    /** 幅 targetWidth に縮小。元がそれ以下なら同じインスタンスをそのまま返す。 */
    fun downscale(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width <= targetWidth) return src
        val h = (src.height.toLong() * targetWidth / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, h, true)
    }

    fun toJpeg(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(128 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private const val SIG = 32

    /** 差分検知用の 32x32 輝度シグネチャ。 */
    fun signature(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, SIG, SIG, true)
        val px = IntArray(SIG * SIG)
        small.getPixels(px, 0, SIG, 0, 0, SIG, SIG)
        if (small !== bmp) small.recycle()
        return IntArray(px.size) { i ->
            val c = px[i]
            (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
    }

    fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        if (a.size != b.size) return Double.MAX_VALUE
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

    fun kb(bytes: Int): Int = (bytes / 1024.0).roundToInt()
}
