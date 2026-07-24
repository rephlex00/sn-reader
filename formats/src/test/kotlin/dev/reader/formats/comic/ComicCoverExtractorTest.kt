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

@RunWith(RobolectricTestRunner::class)
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
}
