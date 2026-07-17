package dev.reader.formats

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.InputStream
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
        // ZipOutputStream records a truthful uncompressed size in the central directory,
        // so this trips the declared-size fast path, not the readCapped running-total
        // backstop below. Pin that explicitly so this test can't pass via either path.
        assertThat(thrown.message).contains("declares size")
    }

    @Test
    fun `readCapped aborts an unbounded stream once the running total exceeds the cap`() {
        // A stream that never signals EOF — the declared-size fast path can't help here
        // because there is no zip entry / declared size at all; this is the backstop that
        // must catch a decompression bomb whose declared size understates reality.
        val unbounded = object : InputStream() {
            override fun read(): Int = 'a'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte())
                return len
            }
        }

        val thrown = try {
            readCapped(unbounded, "bomb.txt")
            null
        } catch (e: IOException) {
            e
        }

        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("bomb.txt")
        assertThat(thrown.message).contains("while reading")
    }

    @Test
    fun `size returns an entry's uncompressed byte length`() {
        val file = temp.newFile("sized.zip")
        val content = "twelve bytes"  // 12 ASCII bytes
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("a.txt"))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }

        val size = ZipResourceSource(file).use { it.size("a.txt") }

        assertThat(size).isEqualTo(content.toByteArray().size.toLong())
    }

    @Test
    fun `size returns zero for a missing entry rather than throwing`() {
        val file = temp.newFile("empty.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("present.txt"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }

        val size = ZipResourceSource(file).use { it.size("absent.txt") }

        assertThat(size).isEqualTo(0L)
    }
}
