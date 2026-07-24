package dev.reader.library

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import dev.reader.data.BookMetadataResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ComicMetadataExtractorTest {
    private fun buildCbz(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    private fun png(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    @Test fun `reads title from ComicInfo and writes a cover`() {
        val ctx = RuntimeEnvironment.getApplication()
        val cbz = File(ctx.filesDir, "berserk.cbz")
        buildCbz(cbz, mapOf(
            "001.png" to png(400, 600),
            "ComicInfo.xml" to "<ComicInfo><Series>Berserk</Series><Number>3</Number></ComicInfo>".toByteArray(),
        ))
        val result = ComicMetadataExtractor(ctx).extract(cbz)
        assertThat(result).isInstanceOf(BookMetadataResult.Success::class.java)
        val success = result as BookMetadataResult.Success
        assertThat(success.title).isEqualTo("Berserk #3")
        assertThat(success.coverPath).isNotNull()
        assertThat(File(success.coverPath!!).exists()).isTrue()
    }

    @Test fun `a RAR archive fails with the specific message, never throws`() {
        val ctx = RuntimeEnvironment.getApplication()
        val cbr = File(ctx.filesDir, "x.cbr")
        cbr.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
        val result = ComicMetadataExtractor(ctx).extract(cbr)
        assertThat(result).isInstanceOf(BookMetadataResult.Failure::class.java)
        assertThat((result as BookMetadataResult.Failure).reason).contains("RAR")
    }
}
