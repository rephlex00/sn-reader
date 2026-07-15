package dev.reader.formats.epub

import com.google.common.truth.Truth.assertThat
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
class EpubDocumentTest {

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

    private fun openStandard() = temp.newFile("book.epub")
        .also { standardEpub(it).close() }
        .let { EpubDocument.open(it, measurer) }

    @Test
    fun `exposes metadata from the package`() {
        openStandard().use { assertThat(it.metadata.title).isEqualTo("The Test Book") }
    }

    @Test
    fun `exposes the table of contents`() {
        openStandard().use { assertThat(it.toc.map { e -> e.title }).containsExactly("One", "Two") }
    }

    @Test
    fun `spine size is the chapter count`() {
        openStandard().use { assertThat(it.spineSize).isEqualTo(2) }
    }

    @Test
    fun `paginates a chapter into at least one page`() {
        openStandard().use {
            val chapter = it.chapter(0, config)
            assertThat(chapter.pages).isNotEmpty()
            assertThat(chapter.pages.first().index).isEqualTo(0)
        }
    }

    @Test
    fun `chapter content reaches the laid-out text`() {
        openStandard().use {
            val chapter = it.chapter(0, config)
            val text = (chapter.measured as dev.reader.formats.render.AndroidMeasuredChapter)
                .layout.text.toString()
            assertThat(text).contains("First chapter.")
        }
    }

    @Test
    fun `repeated requests with the same config return the cached chapter`() {
        openStandard().use {
            val first = it.chapter(0, config)
            val second = it.chapter(0, config)
            assertThat(second).isSameInstanceAs(first)
        }
    }

    @Test
    fun `changing the config re-paginates`() {
        openStandard().use {
            val first = it.chapter(0, config)
            val second = it.chapter(0, config.copy(textSizePx = 64f))
            assertThat(second).isNotSameInstanceAs(first)
        }
    }

    @Test
    fun `rejects a spine index out of range`() {
        openStandard().use {
            val e = runCatching { it.chapter(99, config) }.exceptionOrNull()
            assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `a missing chapter file yields an empty chapter rather than throwing`() {
        val file = temp.newFile("broken.epub")
        buildEpub(file) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Broken</dc:title></metadata>
  <manifest><item id="ch1" href="missing.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""")
        }.close()

        EpubDocument.open(file, measurer).use {
            assertThat(it.chapter(0, config).pages).isEmpty()
        }
    }

    @Test
    fun `opening a non-epub reports NotAnEpub`() {
        val file = temp.newFile("nope.epub")
        buildEpub(file) { entry("hello.txt", "not a book") }.close()

        val e = runCatching { EpubDocument.open(file, measurer) }.exceptionOrNull()
        assertThat(e).isInstanceOf(EpubException.NotAnEpub::class.java)
    }
}
