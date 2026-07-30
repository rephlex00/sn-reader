package dev.reader.formats

import com.google.common.truth.Truth.assertThat
import dev.reader.engine.Block
import dev.reader.engine.RenderConfig
import dev.reader.formats.epub.buildEpub
import dev.reader.formats.mobi.TestMobi
import dev.reader.formats.render.AndroidTextMeasurer
import dev.reader.formats.render.SpannedChapterBuilder
import dev.reader.formats.render.TypefaceProvider
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The same book in both formats must reach the reader as the same book.
 *
 * This is the requirement stated as a test rather than as a comment: a MOBI and an EPUB carrying
 * identical prose must produce the same chapter count, the same Contents, the same paragraphs and
 * the same pagination — because everything the reader shows is derived from those, and a
 * difference in any of them is a difference the person holding the device would see.
 *
 * It is deliberately written against [ReflowableDocuments], the way production opens a book, so
 * it also pins that the factory routes each file to the right parser.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobiEpubParityTest {

    @get:Rule val temp = TemporaryFolder()

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

    private val chapterOne = listOf(
        "It was a bright cold day in April, and the clocks were striking thirteen.",
        "Winston Smith slipped quickly through the glass doors of Victory Mansions.",
    )
    private val chapterTwo = listOf(
        "The hallway smelt of boiled cabbage and old rag mats.",
        "At one end of it a coloured poster had been tacked to the wall.",
    )

    /** The two chapters as an EPUB: real spine, real nav document. */
    private fun epub(): File {
        val file = temp.newFile("book.epub")
        buildEpub(file) {
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", dev.reader.formats.epub.CONTAINER_XML)
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Parity</dc:title><dc:creator>A. Author</dc:creator><dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>""",
            )
            entry(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>
<nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops"><ol>
<li><a href="c1.xhtml">CHAPTER ONE</a></li><li><a href="c2.xhtml">CHAPTER TWO</a></li>
</ol></nav></body></html>""",
            )
            entry("OEBPS/c1.xhtml", page(chapterOne))
            entry("OEBPS/c2.xhtml", page(chapterTwo))
        }.close()
        return file
    }

    private fun page(paragraphs: List<String>) =
        """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>""" +
            paragraphs.joinToString("") { "<p>$it</p>" } +
            "</body></html>"

    /**
     * The same two chapters as a MOBI, written the way a real one is: one stream, a guide
     * pointing at a TOC page whose anchors are byte offsets, and body text in `<blockquote>`.
     */
    private fun mobi(): File {
        val head = "<html><head><guide><reference type=\"toc\" filepos=%08d /></guide></head><body>"
        fun chapter(paragraphs: List<String>) =
            "<div>" + paragraphs.joinToString("") { "<blockquote width=\"2em\">$it</blockquote>" } +
                "<mbp:pagebreak/></div>"
        val one = chapter(chapterOne)
        val two = chapter(chapterTwo)
        val headLen = String.format(head, 0).toByteArray(Charsets.UTF_8).size
        val oneAt = headLen
        val twoAt = oneAt + one.toByteArray(Charsets.UTF_8).size
        val tocAt = twoAt + two.toByteArray(Charsets.UTF_8).size
        val toc = "<div><p><a filepos=%08d>CHAPTER ONE</a></p><p><a filepos=%08d>CHAPTER TWO</a></p></div>"
            .format(oneAt, twoAt)
        val html = String.format(head, tocAt) + one + two + toc + "</body></html>"
        return TestMobi.write(temp.newFile("book.mobi"), html, title = "Parity", recordSize = 256)
    }

    private fun paragraphsOf(doc: ReflowableDocument, chapter: Int): List<String> {
        val method = ReflowableDocument::class.java
            .getDeclaredMethod("readBlocks", Int::class.javaPrimitiveType, RenderConfig::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val blocks = method.invoke(doc, chapter, config) as List<Block>
        return blocks.filterIsInstance<Block.Paragraph>().map { it.text.text }
    }

    @Test
    fun `both formats present the same chapters, contents and prose`() {
        ReflowableDocuments.open(epub(), measurer).use { asEpub ->
            ReflowableDocuments.open(mobi(), measurer).use { asMobi ->
                assertThat(asMobi.spineSize).isEqualTo(asEpub.spineSize)
                assertThat(asMobi.toc.map { it.title }).isEqualTo(asEpub.toc.map { it.title })
                assertThat(asMobi.toc.map { it.spineIndex }).isEqualTo(asEpub.toc.map { it.spineIndex })
                for (i in 0 until asEpub.spineSize) {
                    assertThat(paragraphsOf(asMobi, i)).isEqualTo(paragraphsOf(asEpub, i))
                }
            }
        }
    }

    @Test
    fun `both formats paginate to the same pages under the same typography`() {
        // Same prose, same RenderConfig, so the reader must count the same pages in each — the
        // measurable form of "you cannot tell which format you are reading".
        ReflowableDocuments.open(epub(), measurer).use { asEpub ->
            ReflowableDocuments.open(mobi(), measurer).use { asMobi ->
                for (i in 0 until asEpub.spineSize) {
                    assertThat(asMobi.paginate(i, config).pages.size)
                        .isEqualTo(asEpub.paginate(i, config).pages.size)
                }
            }
        }
    }

    @Test
    fun `both formats name the book the same way`() {
        ReflowableDocuments.open(epub(), measurer).use { asEpub ->
            ReflowableDocuments.open(mobi(), measurer).use { asMobi ->
                assertThat(asMobi.metadata.title).isEqualTo(asEpub.metadata.title)
                assertThat(asMobi.metadata.author).isEqualTo(asEpub.metadata.author)
            }
        }
    }

    @Test
    fun `the factory routes by extension, and by bytes when the extension says nothing`() {
        val mobiFile = mobi()
        val renamed = File(mobiFile.parentFile, "extensionless").also { mobiFile.copyTo(it) }
        ReflowableDocuments.open(renamed, measurer).use {
            assertThat(it.spineSize).isEqualTo(2) // opened as a MOBI on its PalmDB signature alone
        }
    }

    @Test
    fun `every chapter of both formats is paginable, so neither can strand the reader`() {
        ReflowableDocuments.open(epub(), measurer).use { asEpub ->
            ReflowableDocuments.open(mobi(), measurer).use { asMobi ->
                for (doc in listOf(asEpub, asMobi)) {
                    for (i in 0 until doc.spineSize) {
                        assertThat(doc.paginate(i, config).pages).isNotEmpty()
                    }
                    assertThat(doc.chapterWeights).hasSize(doc.spineSize)
                }
            }
        }
    }
}
