package dev.reader.formats.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.reader.engine.BookMetadata
import dev.reader.formats.ResourceSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/** A reasonable default grid-cell footprint; Task 5's actual cell size may differ. */
private const val DEFAULT_MAX_WIDTH_PX = 240
private const val DEFAULT_MAX_HEIGHT_PX = 360

/** Whether [EpubCoverExtractor.extract] found real cover art, or had to draw a placeholder. */
enum class CoverOutcome { EXTRACTED, GENERATED }

/**
 * Produces a cached, on-disk thumbnail for one book: the embedded cover image if the EPUB
 * declares one and it decodes, or a generated typographic placeholder (title on a plain
 * background) if it doesn't. This is the first image decoding in the project —
 * [BookMetadata.coverHref] has been parsed since Plan 1 and never once opened before this.
 *
 * **Never decodes a full-resolution source image into memory.** [BitmapFactory.Options
 * .inJustDecodeBounds] reads only the header first; [sampleSizeFor] then picks a
 * power-of-two downsample factor for the real decode. That factor alone can still
 * overshoot the target (Android's documented algorithm deliberately rounds down to the
 * nearest power of two that stays *at or above* the requested size), so a final
 * `Bitmap.createScaledBitmap` trims the sampled result to exactly [maxWidthPx] x
 * [maxHeightPx] — never up. Either way, a 9.8 MB book carrying a multi-megapixel cover
 * never has that cover fully decoded here.
 *
 * **Grayscale, always.** The target panel is grayscale, so an RGB thumbnail would be 4x
 * the storage and bandwidth for information the display physically cannot show. The
 * decoded bitmap is reduced to one 8-bit luma byte per pixel and written as a real
 * single-channel (`color type 0`, bit depth 8) PNG — never [Bitmap.Config.ARGB_8888] on
 * disk. That PNG is hand-encoded in [encodeGrayscalePng] rather than produced via
 * [Bitmap.compress]: the first attempt here used [Bitmap.Config.ALPHA_8] +
 * `compress(PNG, ...)`, which is the config Android's own docs point at for an
 * alpha-only/single-channel bitmap — but empirically, under Robolectric's native Skia
 * shadow, `compress` on an ALPHA_8 bitmap returns `false` and writes zero bytes. Rather
 * than depend on a device-specific quirk either way, this writes the PNG format directly:
 * a minimal grayscale IHDR/IDAT/IEND with no filtering, deflated with [Deflater]. Any
 * standard decoder — including a plain `BitmapFactory.decodeFile` with no special
 * options, exactly how a later loader will read it back — reads color type 0 natively and
 * hands back R == G == B pixels.
 *
 * **Decoding never throws.** A corrupt or truncated cover image — `BitmapFactory.decode*`
 * returning null is the most common failure shape, not an exception — falls back to the
 * generated placeholder rather than propagating; a broken cover must not abort a library
 * sync. This covers everything up through [decodeDownsampled] and the sampled decode
 * itself. It does not cover [writeGrayscalePng]'s final `destination.writeBytes` call:
 * that's a real disk write and can still throw `IOException` (a full disk, a missing
 * parent directory) like any other file write. The `:data` module's indexer already wraps
 * each book's processing in its own `catch (e: Exception)`, so a single bad destination
 * degrades that one book to a `Failure` rather than aborting the whole sync — but `extract`
 * itself is not unconditionally exception-free.
 */
class EpubCoverExtractor(
    private val maxWidthPx: Int = DEFAULT_MAX_WIDTH_PX,
    private val maxHeightPx: Int = DEFAULT_MAX_HEIGHT_PX,
) {

    /**
     * Writes a grayscale PNG thumbnail to [destination] (overwritten if already present)
     * and reports whether it came from the book's own cover art or a generated
     * placeholder. [destination]'s parent directory must already exist — this never
     * creates directories, and never writes anywhere but the exact file given (never the
     * user's `/Document`; the caller is expected to pass a path under `context.filesDir`).
     */
    fun extract(source: ResourceSource, metadata: BookMetadata, destination: File): CoverOutcome {
        val decoded = metadata.coverHref?.let { href -> decodeDownsampled(source, href) }
        val outcome = if (decoded != null) CoverOutcome.EXTRACTED else CoverOutcome.GENERATED
        val bitmap = decoded ?: generatePlaceholder(metadata.title)
        writeGrayscalePng(bitmap, destination)
        return outcome
    }

    /**
     * Reads the cover entry (via [readBytesChecked], the binary sibling of the text-entry
     * cap that every other untrusted read in this package goes through), decodes its
     * bounds only, computes a downsample factor, and only then decodes for real. Returns
     * null — never throws — for a missing entry, an oversized entry the size cap rejects,
     * or bytes that don't decode as an image at all.
     */
    private fun decodeDownsampled(source: ResourceSource, href: String): Bitmap? {
        val bytes = try {
            readBytesChecked(source, href)
        } catch (e: EpubException.Malformed) {
            null // an oversized/unreadable cover entry degrades to the generated placeholder
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null // not decodable as an image at all — BitmapFactory reports this via -1, not a throw
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxWidthPx, maxHeightPx)
        }
        val sampled = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            // Deliberately scoped to only this one call, not a blanket guard around the
            // rest of the function: this is the sole allocation still sized by untrusted
            // input (the source image's real dimensions, before sampling shrinks them).
            // Should be unreachable given the sampling above, but a hostile or bizarre
            // image must still degrade to the placeholder rather than crash the sync.
            // Everything below this point (scaleToFit, grayscale conversion, PNG
            // encoding) operates on the already-bounded `sampled` result, so it has no
            // equivalent OOM risk and is intentionally left uncaught.
            null
        } ?: return null

        // inSampleSize is a power-of-two floor: Android's own documented algorithm
        // deliberately overshoots (it picks the largest sample size for which the result
        // is still >= the requested size), so the sampled decode can still land well
        // above maxWidthPx x maxHeightPx — this trims it to the exact target, and only
        // ever shrinks further, never enlarges a cover that was already smaller.
        return scaleToFit(sampled, maxWidthPx, maxHeightPx)
    }

    /** One `Canvas`, once: the title on a plain background, cached to disk like any other cover. */
    private fun generatePlaceholder(title: String): Bitmap {
        val bitmap = Bitmap.createBitmap(maxWidthPx, maxHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.LTGRAY)

        val horizontalPadding = maxWidthPx / 10
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.DKGRAY
            textSize = (maxWidthPx / 9f).coerceAtLeast(8f)
        }
        val layout = StaticLayout.Builder
            .obtain(title, 0, title.length, paint, (maxWidthPx - 2 * horizontalPadding).coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

        canvas.save()
        canvas.translate(horizontalPadding.toFloat(), ((maxHeightPx - layout.height) / 2f).coerceAtLeast(0f))
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    private fun writeGrayscalePng(bitmap: Bitmap, destination: File) {
        val gray = toGrayscaleBytes(bitmap)
        destination.writeBytes(encodeGrayscalePng(bitmap.width, bitmap.height, gray))
    }
}

/**
 * The standard power-of-two downsample factor (Android's own documented pattern): the
 * largest `inSampleSize` for which the halved dimensions are still at least the requested
 * size, so the real decode lands close to — never far below — the target footprint.
 *
 * The inner loop condition is deliberately `||`, not the `&&` in some versions of Android's
 * own snippet: with `&&`, an extreme aspect ratio (e.g. a 30000x200 cover against a 240x360
 * cell) never samples at all, because the already-small dimension (200) fails its half of
 * the test on the very first check and the loop body never runs — leaving the enormous
 * dimension (30000) fully unsampled and decoded at full resolution (measured at 22.9 MB
 * ARGB for that exact input). `||` keeps doubling as long as *either* dimension is still
 * oversized, so both axes get bounded; [scaleToFit] already trims whatever overshoot is
 * left in the other axis, exactly as it does for the common case.
 */
internal fun sampleSizeFor(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight || halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

/**
 * Fits [bitmap] within [maxWidth] x [maxHeight], preserving aspect ratio, shrinking only —
 * a cover already smaller than the target (or an exact fit) is returned unchanged rather
 * than upscaled.
 */
private fun scaleToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
    val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height, 1f)
    if (scale >= 1f) return bitmap
    val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}

/**
 * Reduces [source] to one 8-bit ITU-R BT.601 luma byte per pixel, row-major, no padding —
 * not just discarding color while staying in ARGB_8888, which would keep paying the
 * 4-byte-per-pixel cost this whole conversion exists to avoid.
 *
 * Alpha is composited on WHITE before the luma is kept: the grayscale PNG written out has
 * no alpha channel, so simply dropping alpha would turn a transparent pixel (ARGB
 * 0x00000000 — transparent *black*, the value PNG covers with alpha routinely carry)
 * into solid black. On paper-white e-ink, transparent regions must read as page
 * background, not ink.
 */
private fun toGrayscaleBytes(source: Bitmap): ByteArray {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    val gray = ByteArray(width * height)
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val luma = (r * 77 + g * 151 + b * 28) shr 8
        // Composite on white: full alpha keeps luma exactly, zero alpha lands on 255.
        gray[i] = ((luma * a + 255 * (255 - a)) / 255).toByte()
    }
    return gray
}

/**
 * Hand-encodes [width] x [height] of 8-bit grayscale pixel data ([gray], row-major, one
 * byte per pixel, no padding) as a minimal, standard PNG: signature, `IHDR` (color type 0 —
 * grayscale, bit depth 8), one `IDAT` (every scanline prefixed with filter-type 0/None,
 * deflated), `IEND`. See the class doc for why this bypasses [Bitmap.compress].
 */
private fun encodeGrayscalePng(width: Int, height: Int, gray: ByteArray): ByteArray {
    val raw = ByteArrayOutputStream(height * (width + 1))
    for (y in 0 until height) {
        raw.write(0) // filter type: None
        raw.write(gray, y * width, width)
    }

    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    deflater.setInput(raw.toByteArray())
    deflater.finish()
    val compressed = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (!deflater.finished()) {
        val n = deflater.deflate(buffer)
        compressed.write(buffer, 0, n)
    }
    deflater.end()

    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) // PNG signature

    val ihdr = ByteArrayOutputStream()
    writeBigEndianInt(ihdr, width)
    writeBigEndianInt(ihdr, height)
    ihdr.write(8) // bit depth
    ihdr.write(0) // color type: grayscale
    ihdr.write(0) // compression method (only one is defined)
    ihdr.write(0) // filter method (only one is defined)
    ihdr.write(0) // interlace method: none
    writePngChunk(out, "IHDR", ihdr.toByteArray())
    writePngChunk(out, "IDAT", compressed.toByteArray())
    writePngChunk(out, "IEND", ByteArray(0))

    return out.toByteArray()
}

private fun writeBigEndianInt(out: ByteArrayOutputStream, value: Int) {
    out.write((value ushr 24) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write(value and 0xFF)
}

private fun writePngChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
    writeBigEndianInt(out, data.size)
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    out.write(typeBytes)
    out.write(data)
    val crc = CRC32()
    crc.update(typeBytes)
    crc.update(data)
    writeBigEndianInt(out, crc.value.toInt())
}
