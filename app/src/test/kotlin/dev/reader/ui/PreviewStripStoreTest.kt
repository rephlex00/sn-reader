package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.engine.RenderConfig
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
    fun `generate reports each sampled chapter once, in ascending order`() = runBlocking {
        val book = multiChapterEpub(tempFolder.newFile("book.epub"))
        val done = mutableListOf<Int>()
        store.generate(book, config()) { spineIndex -> done += spineIndex }

        // Every chapter that got at least one thumbnail is reported exactly once, ascending.
        val index = store.stripFor(book, config())!!
        assertThat(done).isEqualTo(generatedChaptersOf(index).sorted())
        assertThat(done).isInOrder()
        assertThat(done.toSet().size).isEqualTo(done.size) // no duplicates
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
        // This is also the cancellation-safety invariant: generate() writes the index LAST, so a
        // job cancelled mid-generation (at any ensureActive() checkpoint) leaves exactly this
        // shape on disk — thumbnails maybe, index never — and stripFor must treat it as absent.
        // A prior version of this suite asserted that directly via launch{}.cancelAndJoin(), but
        // that race resolves before generate's body starts often enough to be flaky; this test
        // covers the same invariant deterministically.
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
    fun `cancelling one generation and immediately launching another leaves exactly one valid, complete strip`() =
        runBlocking {
            // Mirrors ReaderActivity.scheduleStripGeneration's relaunch (post-fix): the first
            // generator over this exact directory is cancelled and JOINED — guaranteed fully
            // stopped, not just asked to stop — before the second is launched over the same
            // (book, config) dir. Proves that sequence never leaves a corrupt/partial strip and
            // never lets an exception escape, regardless of how far the first generator got.
            val book = multiChapterEpub(tempFolder.newFile("book.epub"))
            val cfg = config()

            val first = launch { store.generate(book, cfg) }
            yield() // let it start real work (open the doc, begin measuring) before cutting it off
            first.cancelAndJoin()

            val second = launch { store.generate(book, cfg) }
            second.join()

            val index = store.stripFor(book, cfg)
            assertThat(index).isNotNull()
            assertThat(index!!.entries).isNotEmpty()
            for (e in index.entries) {
                val f = store.thumbnailFile(book, cfg, e)
                assertThat(f.exists()).isTrue()
                assertThat(f.length()).isGreaterThan(0L)
            }
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

    @Test
    fun `eviction is oldest-first by dir mtime, and a recent stripFor read spares a strip`() = runBlocking {
        val bookA = multiChapterEpub(tempFolder.newFile("a.epub"))
        val bookB = multiChapterEpub(tempFolder.newFile("b.epub"))
        val bookC = multiChapterEpub(tempFolder.newFile("c.epub"))
        val cfg = config()

        // Each stripFor() call below doubles as the size read AND (per its own contract) a touch
        // of that book's dir mtime — done right after each generate so it doesn't disturb the
        // generation order, only mirrors it.
        store.generate(bookA, cfg)
        val sizeA = strip(bookA, cfg, store.stripFor(bookA, cfg)!!)
        Thread.sleep(20) // distinct dir mtimes
        store.generate(bookB, cfg)
        val sizeB = strip(bookB, cfg, store.stripFor(bookB, cfg)!!)
        Thread.sleep(20)
        store.generate(bookC, cfg)
        val sizeC = strip(bookC, cfg, store.stripFor(bookC, cfg)!!)

        // Touch A's mtime so it is now the most-recently-used, leaving B as the oldest-untouched.
        Thread.sleep(20)
        assertThat(store.stripFor(bookA, cfg)).isNotNull()

        // Exactly enough room for the two survivors (A, C) but not B too — one deletion suffices,
        // and stops there.
        val cap = sizeA + sizeC

        store.evictOverBudget(capBytes = cap, keep = null)

        assertThat(store.stripFor(bookB, cfg)).isNull()
        assertThat(store.stripFor(bookA, cfg)).isNotNull()
        assertThat(store.stripFor(bookC, cfg)).isNotNull()
    }

    // Mirrors evictOverBudget's own size accounting exactly: the whole dir (thumbnails + index
    // file), not just the entries' thumbnail files, so a cap built from this total isn't quietly
    // undersized by the index file's few dozen bytes.
    private fun strip(book: File, cfg: RenderConfig, index: StripIndex): Long {
        val dir = store.thumbnailFile(book, cfg, index.entries.first()).parentFile!!
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
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
