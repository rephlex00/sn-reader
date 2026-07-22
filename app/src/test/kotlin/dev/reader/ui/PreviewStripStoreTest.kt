package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.engine.RenderConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class PreviewStripStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val store = PreviewStripStore(context)

    // Small viewport so Robolectric bitmap work stays fast; still portrait (column count 1).
    private fun config(textSizePx: Float = 24f) = RenderConfig(
        fontFamily = "serif", textSizePx = textSizePx, lineSpacingMultiplier = 1.2f,
        marginPx = 20, justified = false, hyphenated = false,
        viewportWidthPx = 300, viewportHeightPx = 400,
    )

    @Test
    fun `generate writes thumbnails and a valid index, and stripFor returns it`() = runBlocking {
        val book = multiChapterEpub(tempFolder.newFile("book.epub"))
        val cfg = config()

        assertThat(store.stripFor(book, cfg)).isNull()
        store.generate(book, cfg)

        val index = store.stripFor(book, cfg)!!
        assertThat(index.entries).isNotEmpty()
        assertThat(index.configHash).isEqualTo(configHash(cfg))
        for (e in index.entries) {
            assertThat(store.thumbnailFile(book, cfg, e).exists()).isTrue()
            assertThat(store.thumbnailFile(book, cfg, e).length()).isGreaterThan(0L)
        }
        // Entries ascend by fraction — nearestEntry's binary search depends on it.
        assertThat(index.entries.map { it.fraction }).isInOrder()
    }

    @Test
    fun `a config change invalidates the strip`() = runBlocking {
        val book = multiChapterEpub(tempFolder.newFile("book.epub"))
        store.generate(book, config())

        assertThat(store.stripFor(book, config(textSizePx = 30f))).isNull()
    }

    @Test
    fun `a changed book file invalidates the strip`() = runBlocking {
        val book = multiChapterEpub(tempFolder.newFile("book.epub"))
        val cfg = config()
        store.generate(book, cfg)

        book.appendBytes(ByteArray(64))
        assertThat(store.stripFor(book, cfg)).isNull()
    }

    @Test
    fun `a directory without an index is invisible — partial generation is ignored`() = runBlocking {
        val book = multiChapterEpub(tempFolder.newFile("book.epub"))
        val cfg = config()
        store.generate(book, cfg)
        // Simulate a crash between thumbnails and index: delete just the index.
        val index = store.stripFor(book, cfg)!!
        val dir = store.thumbnailFile(book, cfg, index.entries.first()).parentFile!!
        java.io.File(dir, "index").delete()

        assertThat(store.stripFor(book, cfg)).isNull()
    }

    @Test
    fun `cancellation mid-generate leaves no index behind`() = runBlocking {
        val book = multiChapterEpub(tempFolder.newFile("book.epub"))
        val cfg = config()
        val job: Job = launch { store.generate(book, cfg) }
        // Cancel promptly; whether any thumbnails were written yet, no index may exist.
        job.cancelAndJoin()

        assertThat(store.stripFor(book, cfg)).isNull()
    }

    @Test
    fun `eviction deletes oldest strips until under budget, sparing the kept book`() = runBlocking {
        val bookA = multiChapterEpub(tempFolder.newFile("a.epub"))
        val bookB = multiChapterEpub(tempFolder.newFile("b.epub"))
        val cfg = config()
        store.generate(bookA, cfg)
        Thread.sleep(20) // distinct dir mtimes
        store.generate(bookB, cfg)

        store.evictOverBudget(capBytes = 1L, keep = bookB)

        assertThat(store.stripFor(bookA, cfg)).isNull()
        assertThat(store.stripFor(bookB, cfg)).isNotNull()
    }

    /**
     * A three-chapter EPUB with enough body text per chapter to paginate to several pages each —
     * exercises openings AND fills in [samplePlan]. Minimal copy of the shape `ReaderActivityTest`
     * uses (`tocEpub`/`multiPageEpub`); their builders are private to that file, so this is inlined
     * here rather than reaching across module test sources. No nav document — PreviewStripStore
     * never touches [dev.reader.engine.TocEntry].
     */
    private fun multiChapterEpub(file: File): File {
        fun chapterBody(label: String) = buildString {
            repeat(30) {
                append("<p>$label paragraph $it carries enough words to lay out into a line or two ")
                append("on the test viewport, so that thirty of them together span several pages ")
                append("once paginated at a small viewport size.</p>")
            }
        }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""",
            )
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Multi Chapter Book</dc:title></metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
    <itemref idref="ch3"/>
  </spine>
</package>""",
            )
            entry("OEBPS/ch1.xhtml", "<html><body><h1>One</h1>${chapterBody("One")}</body></html>")
            entry("OEBPS/ch2.xhtml", "<html><body><h1>Two</h1>${chapterBody("Two")}</body></html>")
            entry("OEBPS/ch3.xhtml", "<html><body><h1>Three</h1>${chapterBody("Three")}</body></html>")
        }
        return file
    }
}
