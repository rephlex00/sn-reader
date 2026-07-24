package dev.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Power-of-two sample size for decoding a page near the target. The loop uses `||`, not `&&`: it
 * keeps halving while EITHER axis is still at or above target, so an extreme-aspect page (a stitched
 * double-page spread, e.g. 30000×200) is downsampled on its long axis instead of decoding at full
 * native resolution and risking OutOfMemoryError. This mirrors EpubCoverExtractor's documented fix
 * for the same failure mode.
 */
fun computeSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
    if (srcW <= 0 || srcH <= 0) return 1
    var sample = 1
    while (srcW / (sample * 2) >= reqW || srcH / (sample * 2) >= reqH) sample *= 2
    return sample
}

/**
 * Decodes one comic page to a downsampled bitmap, off the main thread. Two passes: bounds only, then
 * pixels at the sample size nearest the panel. [grayscale] applies a saturation-0 luminance filter —
 * available for the on-hardware comparison the spec defers (the panel may convert better itself), but
 * off by default in v1.
 */
class ComicPageDecoder {
    suspend fun decode(
        streamProvider: () -> InputStream?,
        reqW: Int,
        reqH: Int,
        grayscale: Boolean = false,
    ): Bitmap? = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        streamProvider()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return@withContext null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
        }
        val decoded = streamProvider()?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@withContext null
        if (!grayscale) return@withContext decoded
        toGrayscale(decoded).also { if (it !== decoded) decoded.recycle() }
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
