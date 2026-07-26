package dev.reader.formats.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import dev.reader.engine.BookMetadata
import dev.reader.formats.ResourceSource
import dev.reader.formats.writeGrayscalePng
import java.io.File

/**
 * Produces a cached, on-disk thumbnail for one comic archive: a downsampled decode of the
 * cover page image if [BookMetadata.coverHref] resolves and decodes, or a generated plain
 * placeholder if it doesn't.
 *
 * **Never decodes a full-resolution source image into memory.** [decodeThumbnail] reads only
 * the header first ([BitmapFactory.Options.inJustDecodeBounds]), then [sampleSize] picks a
 * power-of-two downsample factor for the real decode. That factor is still just a floor —
 * Android's documented algorithm can overshoot the target — so [scaleToFit] trims the sampled
 * result down to exactly [maxWidthPx] x [maxHeightPx] afterward, same two-pass shape as
 * `EpubCoverExtractor.decodeDownsampled`.
 *
 * **Decoding never throws.** [decodeThumbnail] wraps the whole two-pass decode in
 * `catch (e: Exception)` and degrades to the generated placeholder on any decode failure — but
 * `OutOfMemoryError` is an `Error`, not an `Exception`, and would escape that catch. Keeping
 * the real allocation properly bounded (see [sampleSize]) is therefore load-bearing, not just
 * an optimization.
 *
 * **Grayscale, always.** Same reasoning as `EpubCoverExtractor`: the target panel is
 * grayscale, so a 4-channel ARGB thumbnail would be roughly 4x the storage and write cost
 * for information the display physically cannot show. The written PNG is single-channel
 * (`color type 0`, bit depth 8) via the shared [dev.reader.formats.writeGrayscalePng]
 * encoder — never [Bitmap.compress] onto an ARGB_8888 bitmap.
 */
class ComicCoverExtractor(
    private val maxWidthPx: Int = 240,
    private val maxHeightPx: Int = 360,
) {
    enum class CoverOutcome { EXTRACTED, GENERATED }

    fun extract(source: ResourceSource, metadata: BookMetadata, destination: File): CoverOutcome {
        val path = metadata.coverHref
        val bitmap = path?.let { decodeThumbnail(source, it) }
        return if (bitmap != null) {
            writeGrayscalePng(bitmap, destination)
            bitmap.recycle()
            CoverOutcome.EXTRACTED
        } else {
            val placeholderBitmap = placeholder()
            writeGrayscalePng(placeholderBitmap, destination)
            placeholderBitmap.recycle()
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
            val scaled = scaleToFit(sampled, maxWidthPx, maxHeightPx)
            if (scaled !== sampled) sampled.recycle()
            scaled
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The standard power-of-two downsample factor: the largest `inSampleSize` for which the
     * halved dimensions are still at least the requested size.
     *
     * The loop condition is `||`, not `&&`: with `&&`, an extreme aspect ratio (e.g. a
     * 30000x200 stitched double-page spread against a 240x360 cell) never samples at all,
     * because the already-small dimension (200) fails its half of the test on the very first
     * check and the loop body never runs — leaving the enormous dimension (30000) fully
     * unsampled and decoded at full resolution (~96 MB ARGB for that input, risking an
     * `OutOfMemoryError` the surrounding `catch (e: Exception)` in [decodeThumbnail] cannot
     * catch). `||` keeps doubling as long as *either* dimension is still oversized, so both
     * axes get bounded; [scaleToFit] trims whatever overshoot is left in the other axis.
     */
    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        while (w / (s * 2) >= reqW || h / (s * 2) >= reqH) s *= 2
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
}
