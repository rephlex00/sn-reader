package dev.reader.formats.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import dev.reader.engine.BookMetadata
import dev.reader.formats.ResourceSource
import java.io.File

class ComicCoverExtractor(
    private val maxWidthPx: Int = 240,
    private val maxHeightPx: Int = 360,
) {
    enum class CoverOutcome { EXTRACTED, GENERATED }

    fun extract(source: ResourceSource, metadata: BookMetadata, destination: File): CoverOutcome {
        val path = metadata.coverHref
        val bitmap = path?.let { decodeThumbnail(source, it) }
        return if (bitmap != null) {
            writePng(bitmap, destination)
            bitmap.recycle()
            CoverOutcome.EXTRACTED
        } else {
            writePng(placeholder(), destination)
            CoverOutcome.GENERATED
        }
    }

    private fun decodeThumbnail(source: ResourceSource, path: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            source.open(path)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxWidthPx, maxHeightPx)
            }
            val sampled = source.open(path)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            scaleToFit(sampled, maxWidthPx, maxHeightPx)
        } catch (e: Exception) {
            null
        }
    }

    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        while (w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2
        return s
    }

    private fun scaleToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height, 1f)
        if (scale >= 1f) return bitmap
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun placeholder(): Bitmap =
        Bitmap.createBitmap(maxWidthPx, maxHeightPx, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(0xDD, 0xDD, 0xDD))
        }

    private fun writePng(bitmap: Bitmap, destination: File) {
        destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
