package dev.reader.formats.mobi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.reader.formats.epub.CoverOutcome
import dev.reader.formats.epub.sampleSizeFor
import dev.reader.formats.writeGrayscalePng
import java.io.File

/**
 * Produces the library's cached thumbnail for a MOBI, from the cover image the book carries in
 * its own image records — or a typographic placeholder when it carries none.
 *
 * Separate from `EpubCoverExtractor` only because the two formats hand over the bytes
 * differently: an EPUB names a path inside a zip, a MOBI names a record index. Everything after
 * "here are the bytes" — the bounds-then-real two-pass decode, the power-of-two downsample, the
 * grayscale PNG — is the shared code both use, so a cover looks identical whichever format it
 * came out of.
 */
internal class MobiCoverExtractor(
    private val maxWidthPx: Int = DEFAULT_MAX_WIDTH_PX,
    private val maxHeightPx: Int = DEFAULT_MAX_HEIGHT_PX,
) {

    /**
     * Writes a grayscale PNG thumbnail to [destination] (overwritten if present) and reports
     * whether it came from the book's own art. [destination]'s parent must already exist.
     */
    fun extract(coverBytes: ByteArray?, title: String, destination: File): CoverOutcome {
        val decoded = coverBytes?.let(::decodeDownsampled)
        val outcome = if (decoded != null) CoverOutcome.EXTRACTED else CoverOutcome.GENERATED
        val bitmap = decoded ?: placeholder(title)
        writeGrayscalePng(bitmap, destination)
        bitmap.recycle()
        return outcome
    }

    /**
     * Decodes bounds first, then decodes for real at a power-of-two sample size — the same
     * two-pass shape the other extractors use, so a 3000px cover never lands in memory at full
     * size. Returns null for anything that does not decode as an image.
     */
    private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxWidthPx, maxHeightPx)
        }
        val sampled = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            // Scoped to the one allocation still sized by untrusted input, exactly as
            // EpubCoverExtractor does: a bizarre image degrades to the placeholder, never a crash.
            null
        } ?: return null

        val scaled = scaleToFit(sampled, maxWidthPx, maxHeightPx)
        if (scaled !== sampled) sampled.recycle()
        return scaled
    }

    private fun scaleToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height, 1f)
        if (scale >= 1f) return bitmap
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /** The title on a plain ground, matching what an EPUB with no cover art gets. */
    private fun placeholder(title: String): Bitmap {
        val bitmap = Bitmap.createBitmap(maxWidthPx, maxHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.LTGRAY)
        val padding = maxWidthPx / 10
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.DKGRAY
            textSize = (maxWidthPx / 9f).coerceAtLeast(8f)
        }
        val layout = StaticLayout.Builder
            .obtain(title, 0, title.length, paint, (maxWidthPx - 2 * padding).coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate(padding.toFloat(), ((maxHeightPx - layout.height) / 2f).coerceAtLeast(0f))
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    private companion object {
        const val DEFAULT_MAX_WIDTH_PX = 240
        const val DEFAULT_MAX_HEIGHT_PX = 360
    }
}
