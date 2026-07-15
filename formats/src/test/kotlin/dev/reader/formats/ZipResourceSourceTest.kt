package dev.reader.formats

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipResourceSourceTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `readText returns small entries normally`() {
        val file = temp.newFile("small.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("small.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }

        val text = ZipResourceSource(file).use { it.readText("small.txt") }

        assertThat(text).isEqualTo("hello")
    }

    @Test
    fun `readText rejects an entry beyond the size cap`() {
        val file = temp.newFile("bomb.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("huge.txt"))
            // Highly compressible: tiny on disk, ~17 MB once inflated — a miniature
            // decompression bomb standing in for a hostile chapter file.
            val chunk = ByteArray(1024) { 'a'.code.toByte() }
            repeat(17 * 1024) { zip.write(chunk) }
            zip.closeEntry()
        }

        val source = ZipResourceSource(file)
        val thrown = try {
            source.use { it.readText("huge.txt") }
            null
        } catch (e: IOException) {
            e
        }

        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("huge.txt")
    }
}
