package dev.reader.library

import com.google.common.truth.Truth.assertThat
import dev.reader.data.BookMetadataResult
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * The shelf side of format transparency: a MOBI must index with a title, an author and a cover
 * file, exactly as an EPUB does, or a library of both reads as two different libraries.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobiMetadataExtractorTest {

    @get:Rule val temp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val extractor = MobiMetadataExtractor(context)

    @Test
    fun `title and author come off the header`() {
        val file = writeMobi(temp.newFile("book.mobi"), title = "Ship of Magic", author = "Robin Hobb")
        val result = extractor.extract(file)
        assertThat(result).isInstanceOf(BookMetadataResult.Success::class.java)
        val success = result as BookMetadataResult.Success
        assertThat(success.title).isEqualTo("Ship of Magic")
        assertThat(success.author).isEqualTo("Robin Hobb")
    }

    @Test
    fun `a cover file is written, so the grid has a board to draw`() {
        val file = writeMobi(temp.newFile("book.mobi"), title = "Mad Ship", author = "Robin Hobb")
        val success = extractor.extract(file) as BookMetadataResult.Success
        assertThat(success.coverPath).isNotNull()
        val cover = File(success.coverPath!!)
        assertThat(cover.isFile).isTrue()
        // A placeholder is still a cover — a book with no art must not leave an empty board.
        assertThat(cover.length()).isGreaterThan(0L)
    }

    @Test
    fun `an encrypted book fails by name rather than throwing`() {
        val file = writeMobi(temp.newFile("drm.mobi"), title = "T", author = "A", encryption = 1)
        val result = extractor.extract(file)
        assertThat(result).isInstanceOf(BookMetadataResult.Failure::class.java)
        assertThat((result as BookMetadataResult.Failure).reason).contains("encrypted")
    }

    @Test
    fun `a file that is not a MOBI fails rather than throwing`() {
        val file = temp.newFile("junk.mobi").apply { writeText("not a mobi at all") }
        assertThat(extractor.extract(file)).isInstanceOf(BookMetadataResult.Failure::class.java)
    }

    /** A minimal stored-text MOBI with EXTH title/author. Mirrors `:formats`' TestMobi. */
    private fun writeMobi(
        file: File,
        title: String,
        author: String,
        encryption: Int = 0,
    ): File {
        val html = "<html><body><p>One short chapter of prose.</p></body></html>"
        val text = html.toByteArray(Charsets.UTF_8)
        fun bshort(o: ByteArrayOutputStream, v: Int) { o.write((v shr 8) and 0xff); o.write(v and 0xff) }
        fun bint(o: ByteArrayOutputStream, v: Int) {
            o.write((v shr 24) and 0xff); o.write((v shr 16) and 0xff)
            o.write((v shr 8) and 0xff); o.write(v and 0xff)
        }

        val exthBody = ByteArrayOutputStream()
        for ((type, value) in listOf(100 to author, 503 to title)) {
            val v = value.toByteArray(Charsets.UTF_8)
            bint(exthBody, type); bint(exthBody, v.size + 8); exthBody.write(v)
        }
        val exth = ByteArrayOutputStream().apply {
            write("EXTH".toByteArray(Charsets.US_ASCII))
            bint(this, 12 + exthBody.size()); bint(this, 2); write(exthBody.toByteArray())
            while (size() % 4 != 0) write(0)
        }.toByteArray()

        val headerLength = 232
        val record0 = ByteArrayOutputStream().apply {
            bshort(this, 1); bshort(this, 0)
            bint(this, text.size)
            bshort(this, 1); bshort(this, 4096); bshort(this, encryption); bshort(this, 0)
            val m = ByteArrayOutputStream()
            m.write("MOBI".toByteArray(Charsets.US_ASCII))
            bint(m, headerLength); bint(m, 2); bint(m, 65001); bint(m, 0); bint(m, 6)
            repeat(10) { bint(m, 0) }
            bint(m, 2)
            repeat(6) { bint(m, 0) }
            bint(m, 0)
            repeat(4) { bint(m, 0) }
            bint(m, 0x40)
            while (m.size() < headerLength) m.write(0)
            write(m.toByteArray()); write(exth)
        }.toByteArray()

        val records = listOf(record0, text)
        val out = ByteArrayOutputStream()
        val name = "Book".toByteArray(Charsets.US_ASCII)
        out.write(name); repeat(32 - name.size) { out.write(0) }
        out.write(ByteArray(28))
        out.write("BOOK".toByteArray(Charsets.US_ASCII)); out.write("MOBI".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(8))
        bshort(out, records.size)
        var offset = 78 + records.size * 8
        for (r in records) {
            bint(out, offset); out.write(0); out.write(0); out.write(0); out.write(0)
            offset += r.size
        }
        for (r in records) out.write(r)
        file.writeBytes(out.toByteArray())
        return file
    }
}
