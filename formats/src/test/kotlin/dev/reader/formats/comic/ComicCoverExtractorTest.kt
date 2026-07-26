package dev.reader.formats.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import dev.reader.engine.BookMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicCoverExtractorTest {
    private fun pngBytes(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    @Test fun `extracts the first page as a downscaled cover`() {
        val cbz = File.createTempFile("cover", ".cbz").also { it.deleteOnExit() }
        buildCbz(cbz, mapOf("001.png" to pngBytes(1200, 1800), "002.png" to pngBytes(1200, 1800)))
        val dest = File.createTempFile("thumb", ".png").also { it.deleteOnExit() }
        val outcome = dev.reader.formats.ZipResourceSource(cbz).use { src ->
            ComicCoverExtractor().extract(src, BookMetadata(title = "X", coverHref = "001.png"), dest)
        }
        assertThat(outcome).isEqualTo(ComicCoverExtractor.CoverOutcome.EXTRACTED)
        val decoded = BitmapFactory.decodeFile(dest.path)
        assertThat(decoded.width).isAtMost(240)
        assertThat(decoded.height).isAtMost(360)
    }

    @Test fun `a missing cover entry generates a placeholder, never throws`() {
        val cbz = File.createTempFile("nocover", ".cbz").also { it.deleteOnExit() }
        buildCbz(cbz, mapOf("001.png" to pngBytes(10, 10)))
        val dest = File.createTempFile("thumb2", ".png").also { it.deleteOnExit() }
        val outcome = dev.reader.formats.ZipResourceSource(cbz).use { src ->
            ComicCoverExtractor().extract(src, BookMetadata(title = "X", coverHref = "missing.png"), dest)
        }
        assertThat(outcome).isEqualTo(ComicCoverExtractor.CoverOutcome.GENERATED)
    }

    // Task 22: comic covers were written as 4-channel ARGB PNGs for a display that can only
    // ever show gray — same waste the EPUB extractor already avoids by hand-encoding a
    // single-channel PNG. See EpubCoverExtractorTest's identical assertion for the offset
    // derivation: 8-byte signature + 4-byte length + 4-byte "IHDR" type + 4-byte width +
    // 4-byte height + 1-byte bit depth = byte 25, IHDR's colour type, which must be 0
    // (grayscale).
    @Test fun `the stored thumbnail is a grayscale PNG, not RGB or RGBA`() {
        val cbz = File.createTempFile("cover", ".cbz").also { it.deleteOnExit() }
        buildCbz(cbz, mapOf("001.png" to pngBytes(1200, 1800)))
        val dest = File.createTempFile("thumb", ".png").also { it.deleteOnExit() }
        dev.reader.formats.ZipResourceSource(cbz).use { src ->
            ComicCoverExtractor().extract(src, BookMetadata(title = "X", coverHref = "001.png"), dest)
        }

        val fileBytes = dest.readBytes()
        assertThat(fileBytes[25].toInt()).isEqualTo(0)
    }

    @Test fun `a generated placeholder cover is also a grayscale PNG`() {
        val cbz = File.createTempFile("nocover", ".cbz").also { it.deleteOnExit() }
        buildCbz(cbz, mapOf("001.png" to pngBytes(10, 10)))
        val dest = File.createTempFile("thumb2", ".png").also { it.deleteOnExit() }
        dev.reader.formats.ZipResourceSource(cbz).use { src ->
            ComicCoverExtractor().extract(src, BookMetadata(title = "X", coverHref = "missing.png"), dest)
        }

        val fileBytes = dest.readBytes()
        assertThat(fileBytes[25].toInt()).isEqualTo(0)
    }
}
