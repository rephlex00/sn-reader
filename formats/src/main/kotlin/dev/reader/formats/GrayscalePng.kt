package dev.reader.formats

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Writes [bitmap] to [destination] (overwritten if already present) as a hand-encoded
 * single-channel (`color type 0`, bit depth 8) grayscale PNG — never
 * [Bitmap.Config.ARGB_8888] on disk. Shared by every cover extractor in this module (EPUB,
 * comic): the target panel is grayscale, so an RGB or RGBA thumbnail would be 3-4x the
 * storage and bandwidth for information the display physically cannot show.
 *
 * This bypasses [Bitmap.compress]: the first attempt at this used
 * [Bitmap.Config.ALPHA_8] + `compress(PNG, ...)`, which is the config Android's own docs
 * point at for an alpha-only/single-channel bitmap — but empirically, under Robolectric's
 * native Skia shadow, `compress` on an ALPHA_8 bitmap returns `false` and writes zero
 * bytes. Rather than depend on a device-specific quirk either way, this writes the PNG
 * format directly. Any standard decoder — including a plain `BitmapFactory.decodeFile`
 * with no special options, exactly how a later loader will read it back — reads color type
 * 0 natively and hands back R == G == B pixels.
 */
internal fun writeGrayscalePng(bitmap: Bitmap, destination: File) {
    val gray = toGrayscaleBytes(bitmap)
    destination.writeBytes(encodeGrayscalePng(bitmap.width, bitmap.height, gray))
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
internal fun toGrayscaleBytes(source: Bitmap): ByteArray {
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
 * deflated), `IEND`. See [writeGrayscalePng] for why this bypasses [Bitmap.compress].
 */
internal fun encodeGrayscalePng(width: Int, height: Int, gray: ByteArray): ByteArray {
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
