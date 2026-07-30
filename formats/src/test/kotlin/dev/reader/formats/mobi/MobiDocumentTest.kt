package dev.reader.formats.mobi

import com.google.common.truth.Truth.assertThat
import dev.reader.engine.Block
import dev.reader.engine.RenderConfig
import dev.reader.formats.render.AndroidTextMeasurer
import dev.reader.formats.render.SpannedChapterBuilder
import dev.reader.formats.render.TypefaceProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobiDocumentTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val measurer = AndroidTextMeasurer(SpannedChapterBuilder(), TypefaceProvider.Platform)
    private val config = RenderConfig(
        fontFamily = "serif",
        textSizePx = 32f,
        lineSpacingMultiplier = 1.4f,
        marginPx = 40,
        justified = false,
        hyphenated = false,
        viewportWidthPx = 1404,
        viewportHeightPx = 1872,
    )

    /**
     * A book shaped like the real thing: a guide pointing at a TOC page whose anchors are byte
     * offsets, `<mbp:pagebreak/>` between chapters, and blockquote-as-paragraph throughout.
     */
    private fun tocBook(): String {
        val head = "<html><head><guide><reference type=\"toc\" title=\"Contents\" filepos=%08d /></guide></head><body>"
        val one = "<div><p height=\"1em\"><font size=\"7\"><b>CHAPTER ONE</b></font></p>" +
            "<blockquote height=\"0pt\" width=\"2em\">First chapter body text.</blockquote>" +
            "<mbp:pagebreak/></div>"
        val two = "<div><p height=\"1em\"><font size=\"7\"><b>CHAPTER TWO</b></font></p>" +
            "<blockquote height=\"0pt\" width=\"2em\">Second chapter body text.</blockquote>" +
            "<mbp:pagebreak/></div>"
        // Two passes: the head's filepos values must be the real byte offsets, which are only
        // known once the head's own length is fixed. Rendering it twice with the same width
        // (%08d) keeps every offset stable between the passes.
        val headLen = String.format(head, 0).toByteArray(Charsets.UTF_8).size
        val oneAt = headLen
        val twoAt = oneAt + one.toByteArray(Charsets.UTF_8).size
        val tocAt = twoAt + two.toByteArray(Charsets.UTF_8).size
        val toc = "<div><p><a filepos=%08d>CHAPTER ONE</a></p><p><a filepos=%08d>CHAPTER TWO</a></p></div>"
            .format(oneAt, twoAt)
        return String.format(head, tocAt) + one + two + toc + "</body></html>"
    }

    @Test
    fun `chapters come from the book's own table of contents`() {
        val file = TestMobi.write(tempFolder.newFile("book.mobi"), tocBook(), recordSize = 128)
        MobiDocument.open(file, measurer).use { doc ->
            assertThat(doc.spineSize).isEqualTo(2)
            assertThat(doc.toc.map { it.title }).containsExactly("CHAPTER ONE", "CHAPTER TWO").inOrder()
            assertThat(doc.toc.map { it.spineIndex }).containsExactly(0, 1).inOrder()
        }
    }

    @Test
    fun `a chapter reads only its own text`() {
        val file = TestMobi.write(tempFolder.newFile("book.mobi"), tocBook(), recordSize = 128)
        MobiDocument.open(file, measurer).use { doc ->
            val first = doc.paginate(0, config).measured
            val second = doc.paginate(1, config).measured
            assertThat(first.lineCount).isGreaterThan(0)
            assertThat(second.lineCount).isGreaterThan(0)
            // The TOC page is navigation, not reading matter, so it is not a chapter of its own.
            assertThat(doc.spineSize).isEqualTo(2)
        }
    }

    @Test
    fun `blockquote is the paragraph, not a pull-quote`() {
        // The defect this guards is total: mobi7 writes body text as blockquote, so mapping it
        // the way EPUB does renders an entire novel as one quotation.
        val file = TestMobi.write(tempFolder.newFile("book.mobi"), tocBook(), recordSize = 128)
        MobiDocument.open(file, measurer).use { doc ->
            val blocks = doc.blocksForTest(0, config)
            assertThat(blocks.filterIsInstance<Block.Quote>()).isEmpty()
            assertThat(blocks.filterIsInstance<Block.Paragraph>()).isNotEmpty()
        }
    }

    @Test
    fun `a big short line becomes a heading when inference is on`() {
        val file = TestMobi.write(tempFolder.newFile("book.mobi"), tocBook(), recordSize = 128)
        MobiDocument.open(file, measurer).use { doc ->
            val headings = doc.blocksForTest(0, config.copy(inferHeadings = true))
                .filterIsInstance<Block.Heading>()
            assertThat(headings.map { it.text.text }).contains("CHAPTER ONE")
        }
    }

    @Test
    fun `chapter weights sum to roughly the book's text`() {
        val file = TestMobi.write(tempFolder.newFile("book.mobi"), tocBook(), recordSize = 128)
        MobiDocument.open(file, measurer).use { doc ->
            assertThat(doc.chapterWeights).hasSize(doc.spineSize)
            assertThat(doc.chapterWeights.all { it > 0 }).isTrue()
        }
    }

    @Test
    fun `metadata comes from EXTH`() {
        val file = TestMobi.write(
            tempFolder.newFile("book.mobi"),
            tocBook(),
            title = "Assassin's Apprentice",
            author = "Robin Hobb",
            recordSize = 128,
        )
        MobiDocument.open(file, measurer).use { doc ->
            assertThat(doc.metadata.title).isEqualTo("Assassin's Apprentice")
            assertThat(doc.metadata.author).isEqualTo("Robin Hobb")
        }
    }

    @Test
    fun `an uncompressed book reads the same as a compressed one`() {
        val html = tocBook()
        val packed = TestMobi.write(tempFolder.newFile("a.mobi"), html, compressed = true, recordSize = 128)
        val raw = TestMobi.write(tempFolder.newFile("b.mobi"), html, compressed = false, recordSize = 128)
        MobiDocument.open(packed, measurer).use { a ->
            MobiDocument.open(raw, measurer).use { b ->
                assertThat(b.spineSize).isEqualTo(a.spineSize)
                assertThat(b.blocksForTest(0, config)).isEqualTo(a.blocksForTest(0, config))
            }
        }
    }

    @Test
    fun `a chapter spanning several records is joined in order`() {
        // recordSize 64 forces every chapter across multiple records, which is where an
        // off-by-one in the record-range maths shows up as missing or duplicated text.
        val file = TestMobi.write(tempFolder.newFile("book.mobi"), tocBook(), recordSize = 64)
        MobiDocument.open(file, measurer).use { doc ->
            val text = doc.blocksForTest(0, config)
                .filterIsInstance<Block.Paragraph>()
                .joinToString(" ") { it.text.text }
            assertThat(text).contains("First chapter body text.")
            assertThat(text).doesNotContain("Second chapter")
        }
    }

    @Test
    fun `a book with no table of contents falls back to page breaks`() {
        val html = "<html><body><div>Front matter.</div><mbp:pagebreak/>" +
            "<div>Chapter one.</div><mbp:pagebreak/><div>Chapter two.</div></body></html>"
        val file = TestMobi.write(tempFolder.newFile("nav.mobi"), html, recordSize = 128)
        MobiDocument.open(file, measurer).use { doc ->
            assertThat(doc.spineSize).isEqualTo(3)
            assertThat(doc.toc).isEmpty() // page breaks carry no names to show in Contents
        }
    }

    @Test
    fun `a book with neither is still readable as one chapter`() {
        val file = TestMobi.write(
            tempFolder.newFile("flat.mobi"),
            "<html><body><p>Just one run of prose with no structure at all.</p></body></html>",
            recordSize = 128,
        )
        MobiDocument.open(file, measurer).use { doc ->
            assertThat(doc.spineSize).isEqualTo(1)
            assertThat(doc.paginate(0, config).pages).isNotEmpty()
        }
    }

    @Test
    fun `an encrypted book is refused by name`() {
        val file = TestMobi.write(tempFolder.newFile("drm.mobi"), tocBook(), encryption = 1)
        val e = runCatching { MobiDocument.open(file, measurer) }.exceptionOrNull()
        assertThat(e).isInstanceOf(MobiException.DrmProtected::class.java)
        assertThat(e).hasMessageThat().contains("encrypted")
    }

    @Test
    fun `a KF8 boundary is refused as an unsupported variant, not as a broken book`() {
        val boundary = byteArrayOf(0, 0, 0, 42)
        val file = TestMobi.write(
            tempFolder.newFile("kf8.mobi"),
            tocBook(),
            exth = mapOf(121 to boundary),
        )
        val e = runCatching { MobiDocument.open(file, measurer) }.exceptionOrNull()
        assertThat(e).isInstanceOf(MobiException.UnsupportedVariant::class.java)
        assertThat(e).hasMessageThat().contains("AZW3")
    }

    @Test
    fun `a file that is not a MOBI at all is refused`() {
        val file = tempFolder.newFile("nope.mobi").apply { writeText("this is plain text") }
        assertThat(runCatching { MobiDocument.open(file, measurer) }.exceptionOrNull())
            .isInstanceOf(MobiException.NotAMobi::class.java)
    }

    @Test
    fun `opening a bad book leaves no file handle behind`() {
        // The open path must close the container on every failure, or a library scan over a
        // folder of damaged books exhausts the descriptor table.
        val file = tempFolder.newFile("drm.mobi")
        TestMobi.write(file, tocBook(), encryption = 2)
        repeat(200) {
            runCatching { MobiDocument.open(file, measurer) }
        }
        // Reaching here without an IOException about too many open files is the assertion.
        assertThat(runCatching { MobiDocument.open(file, measurer) }.exceptionOrNull())
            .isInstanceOf(MobiException.DrmProtected::class.java)
    }
}

/** Reaches the blocks a chapter produces, which production only ever consumes through measure. */
internal fun MobiDocument.blocksForTest(spineIndex: Int, config: RenderConfig): List<Block> {
    val method = MobiDocument::class.java.superclass
        .getDeclaredMethod("readBlocks", Int::class.javaPrimitiveType, RenderConfig::class.java)
    method.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return method.invoke(this, spineIndex, config) as List<Block>
}
