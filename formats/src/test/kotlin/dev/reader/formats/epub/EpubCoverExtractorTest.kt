package dev.reader.formats.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import dev.reader.engine.BookMetadata
import java.io.ByteArrayOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * This is the first image decoding in the project — [EpubCoverExtractor] is the only
 * place `BookMetadata.coverHref` (parsed since Plan 1) is ever actually opened.
 *
 * `@GraphicsMode(NATIVE)` gives real Skia bitmap decode/compress under Robolectric,
 * exactly as [dev.reader.formats.render.AndroidTextMeasurerTest] already relies on for
 * real `StaticLayout` behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubCoverExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val maxWidthPx = 100
    private val maxHeightPx = 150
    private val extractor = EpubCoverExtractor(maxWidthPx = maxWidthPx, maxHeightPx = maxHeightPx)

    /** A real, decodable JPEG of the given size — solid red, so grayscale conversion is checkable. */
    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.RED)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        return out.toByteArray()
    }

    private fun decodeBounds(file: java.io.File): BitmapFactory.Options =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(file.path, it) }

    @Test
    fun `a real cover extracts and downscales`() {
        val epubFile = tempFolder.newFile("book.epub")
        val source = buildEpub(epubFile) {
            entry("images/cover.jpg", jpegBytes(800, 1200))
        }
        val metadata = BookMetadata(title = "Real Cover Book", coverHref = "images/cover.jpg")
        val destination = tempFolder.newFile("cover-out.png")

        val outcome = extractor.extract(source, metadata, destination)

        assertThat(outcome).isEqualTo(CoverOutcome.EXTRACTED)
        val bounds = decodeBounds(destination)
        assertThat(bounds.outWidth).isAtMost(maxWidthPx)
        assertThat(bounds.outHeight).isAtMost(maxHeightPx)
        // Actually downscaled, not just bounded by coincidence.
        assertThat(bounds.outWidth).isLessThan(800)
        assertThat(bounds.outHeight).isLessThan(1200)
    }

    @Test
    fun `an oversized cover does not decode at full resolution`() {
        // A 9.8 MB book can carry a multi-megapixel cover — this stands in for one.
        val epubFile = tempFolder.newFile("book.epub")
        val source = buildEpub(epubFile) {
            entry("images/cover.jpg", jpegBytes(3000, 4500))
        }
        val metadata = BookMetadata(title = "Huge Cover Book", coverHref = "images/cover.jpg")
        val destination = tempFolder.newFile("cover-out.png")

        val outcome = extractor.extract(source, metadata, destination)

        assertThat(outcome).isEqualTo(CoverOutcome.EXTRACTED)
        val bounds = decodeBounds(destination)
        assertThat(bounds.outWidth).isAtMost(maxWidthPx)
        assertThat(bounds.outHeight).isAtMost(maxHeightPx)
    }

    @Test
    fun `a corrupt cover image falls back to a generated cover instead of throwing`() {
        val epubFile = tempFolder.newFile("book.epub")
        val source = buildEpub(epubFile) {
            entry("images/cover.jpg", "not-really-a-jpeg")
        }
        val metadata = BookMetadata(title = "Broken Cover Book", coverHref = "images/cover.jpg")
        val destination = tempFolder.newFile("cover-out.png")

        val outcome = extractor.extract(source, metadata, destination)

        assertThat(outcome).isEqualTo(CoverOutcome.GENERATED)
        val bounds = decodeBounds(destination)
        assertThat(bounds.outWidth).isGreaterThan(0)
        assertThat(bounds.outHeight).isGreaterThan(0)
    }

    @Test
    fun `a book with no cover href gets a generated typographic cover`() {
        val epubFile = tempFolder.newFile("book.epub")
        val source = buildEpub(epubFile) {
            entry("dummy.txt", "unused")
        }
        val metadata = BookMetadata(title = "No Cover Here", coverHref = null)
        val destination = tempFolder.newFile("cover-out.png")

        val outcome = extractor.extract(source, metadata, destination)

        assertThat(outcome).isEqualTo(CoverOutcome.GENERATED)
        assertThat(destination.exists()).isTrue()
        val bounds = decodeBounds(destination)
        assertThat(bounds.outWidth).isGreaterThan(0)
        assertThat(bounds.outHeight).isGreaterThan(0)
    }

    @Test
    fun `the stored thumbnail is grayscale, not RGB`() {
        val epubFile = tempFolder.newFile("book.epub")
        val source = buildEpub(epubFile) {
            entry("images/cover.jpg", jpegBytes(400, 600))
        }
        val metadata = BookMetadata(title = "Color Cover Book", coverHref = "images/cover.jpg")
        val destination = tempFolder.newFile("cover-out.png")

        extractor.extract(source, metadata, destination)

        val bitmap = BitmapFactory.decodeFile(destination.path)
        assertThat(bitmap).isNotNull()
        for (x in intArrayOf(0, bitmap.width / 2, bitmap.width - 1)) {
            for (y in intArrayOf(0, bitmap.height / 2, bitmap.height - 1)) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                assertThat(r).isEqualTo(g)
                assertThat(g).isEqualTo(b)
            }
        }

        // R == G == B only proves the file *renders* gray — an ARGB_8888 PNG drawn through
        // a saturation-zeroing ColorMatrix (exactly the alternative the class doc rejects)
        // would decode identically. Since bytes-on-disk is the entire justification for
        // hand-rolling the encoder instead of `Bitmap.compress`, assert the on-disk format
        // directly: byte 25 is IHDR's colour type, which must be 0 (grayscale). Offset
        // derivation: 8-byte signature + 4-byte length + 4-byte "IHDR" type + 4-byte width +
        // 4-byte height + 1-byte bit depth = 25.
        val fileBytes = destination.readBytes()
        assertThat(fileBytes[25].toInt()).isEqualTo(0)
    }

    @Test
    fun `a generated cover is also grayscale`() {
        val epubFile = tempFolder.newFile("book.epub")
        val source = buildEpub(epubFile) { entry("dummy.txt", "unused") }
        val metadata = BookMetadata(title = "Plain Title", coverHref = null)
        val destination = tempFolder.newFile("cover-out.png")

        extractor.extract(source, metadata, destination)

        val bitmap = BitmapFactory.decodeFile(destination.path)
        assertThat(bitmap).isNotNull()
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertThat(Color.red(pixel)).isEqualTo(Color.green(pixel))
        assertThat(Color.green(pixel)).isEqualTo(Color.blue(pixel))
    }
}
