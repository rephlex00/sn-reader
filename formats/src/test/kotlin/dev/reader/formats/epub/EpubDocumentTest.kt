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
            // Pin a real cache hit first, so this test can't pass merely because the
            // cache never hit at all (isNotSameInstanceAs alone wouldn't catch that).
            val firstAgain = it.chapter(0, config)
            assertThat(firstAgain).isSameInstanceAs(first)

            val second = it.chapter(0, config.copy(textSizePx = 64f))
            assertThat(second).isNotSameInstanceAs(first)
        }
    }

    @Test
    fun `rejects a spine index out of range`() {
        openStandard().use {
            val tooHigh = runCatching { it.chapter(99, config) }.exceptionOrNull()
            assertThat(tooHigh).isInstanceOf(IllegalArgumentException::class.java)

            val negative = runCatching { it.chapter(-1, config) }.exceptionOrNull()
            assertThat(negative).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `a cache hit within the capacity returns the same instance`() {
        val file = temp.newFile("multi.epub")
        multiChapterEpub(file, chapterCount = 3).close()

        EpubDocument.open(file, measurer).use { doc ->
            val first = doc.chapter(0, config)
            doc.chapter(1, config)
            doc.chapter(2, config)
            // 0 is still within the cap-3 cache alongside 1 and 2, so this must hit.
            val secondLook = doc.chapter(0, config)
            assertThat(secondLook).isSameInstanceAs(first)
        }
    }

    @Test
    fun `exceeding the cache capacity evicts the eldest chapter`() {
        val file = temp.newFile("multi.epub")
        multiChapterEpub(file, chapterCount = 4).close()

        EpubDocument.open(file, measurer).use { doc ->
            val chapter0First = doc.chapter(0, config)
            doc.chapter(1, config)
            doc.chapter(2, config)
            // Cache cap is 3; requesting a 4th distinct chapter must evict the eldest (0).
            doc.chapter(3, config)

            val chapter0Second = doc.chapter(0, config)
            assertThat(chapter0Second).isNotSameInstanceAs(chapter0First)
        }
    }

    @Test
    fun `a config change still clears the entire cache`() {
        val file = temp.newFile("multi.epub")
        multiChapterEpub(file, chapterCount = 3).close()

        EpubDocument.open(file, measurer).use { doc ->
            val chapter0First = doc.chapter(0, config)
            val chapter1First = doc.chapter(1, config)

            val newConfig = config.copy(textSizePx = 64f)
            val chapter0Second = doc.chapter(0, newConfig)
            val chapter1Second = doc.chapter(1, newConfig)

            assertThat(chapter0Second).isNotSameInstanceAs(chapter0First)
            assertThat(chapter1Second).isNotSameInstanceAs(chapter1First)
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
    fun `a zip without a container reports NotAnEpub`() {
        // This is a perfectly valid zip (ZipFile construction succeeds); the failure
        // here comes from EpubPackageParser's missing-container.xml path, not from
        // open()'s constructor-wrapping try below. See the next test for that case.
        val file = temp.newFile("nope.epub")
        buildEpub(file) { entry("hello.txt", "not a book") }.close()

        val e = runCatching { EpubDocument.open(file, measurer) }.exceptionOrNull()
        assertThat(e).isInstanceOf(EpubException.NotAnEpub::class.java)
    }

    @Test
    fun `non-zip bytes report NotAnEpub instead of a raw ZipException`() {
        // A .txt renamed .epub, or a truncated download: ZipFile construction itself
        // throws here, exercising open()'s constructor-wrapping try.
        val file = temp.newFile("garbage.epub")
        file.writeBytes("this is not a zip file at all".toByteArray())

        val e = runCatching { EpubDocument.open(file, measurer) }.exceptionOrNull()
        assertThat(e).isInstanceOf(EpubException.NotAnEpub::class.java)
        assertThat(e).isNotInstanceOf(java.util.zip.ZipException::class.java)
    }

    @Test
    fun `an oversized chapter entry surfaces as EpubException, not a raw IOException`() {
        val file = temp.newFile("bomb.epub")
        buildEpub(file) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Bomb</dc:title></metadata>
  <manifest><item id="ch1" href="huge.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""")
            // Highly compressible: tiny on disk, ~17 MB once inflated — a miniature
            // decompression bomb standing in for a hostile chapter file, same technique
            // as ZipResourceSourceTest's "readText rejects an entry beyond the size cap".
            entry("OEBPS/huge.xhtml", "a".repeat(17 * 1024 * 1024))
        }.close()

        EpubDocument.open(file, measurer).use { doc ->
            val e = runCatching { doc.chapter(0, config) }.exceptionOrNull()
            assertThat(e).isInstanceOf(EpubException.Malformed::class.java)
            assertThat(e).isNotInstanceOf(java.io.IOException::class.java)
        }
    }
}
