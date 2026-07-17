package dev.reader.formats.epub

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import com.google.common.truth.Truth.assertThat
import dev.reader.engine.RenderConfig
import dev.reader.formats.render.AndroidMeasuredChapter
import dev.reader.formats.render.AndroidTextMeasurer
import dev.reader.formats.render.SpannedChapterBuilder
import dev.reader.formats.render.TypefaceProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun `chapterWeights are the per-chapter byte sizes in spine order`() {
        val short = "<html><body><p>tiny</p></body></html>"
        val long = "<html><body><p>" + "padding ".repeat(500) + "</p></body></html>"
        val file = temp.newFile("weighted.epub")
        buildEpub(file) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>W</dc:title></metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>""")
            entry("OEBPS/ch1.xhtml", short)
            entry("OEBPS/ch2.xhtml", long)
        }.close()

        EpubDocument.open(file, measurer).use { doc ->
            val weights = doc.chapterWeights
            assertThat(weights).hasSize(2)
            assertThat(weights[0]).isEqualTo(short.toByteArray().size.toLong())
            assertThat(weights[1]).isEqualTo(long.toByteArray().size.toLong())
            assertThat(weights[1]).isGreaterThan(weights[0])
        }
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
    fun `paginate returns a chapter without touching the cache`() {
        openStandard().use {
            // A pure compute: it must NOT cache. Proven by a subsequent chapter() still being a
            // cold miss — if paginate had populated the cache, chapter() would return that instance.
            val computed = it.paginate(0, config)
            val cached = it.chapter(0, config)
            assertThat(cached).isNotSameInstanceAs(computed)
            // ...and it produces the same result a chapter() miss would (same page structure).
            assertThat(computed.pages).isEqualTo(cached.pages)
        }
    }

    @Test
    fun `publish caches a paginate result only when the config still matches`() {
        openStandard().use {
            // Seed cacheConfig by touching chapter 0, then publish a precomputed chapter 1.
            it.chapter(0, config)
            val precomputed = it.paginate(1, config)
            assertThat(it.publish(1, config, precomputed)).isTrue()
            // Now chapter(1) is a hit returning exactly the published instance — no recompute.
            assertThat(it.chapter(1, config)).isSameInstanceAs(precomputed)
        }
    }

    @Test
    fun `publish discards a result whose config is stale`() {
        openStandard().use {
            it.chapter(0, config) // cacheConfig = config
            val staleConfig = config.copy(textSizePx = 64f)
            val precomputed = it.paginate(1, staleConfig)
            // The reader changed typography since the prefetch began: cacheConfig != staleConfig.
            assertThat(it.publish(1, staleConfig, precomputed)).isFalse()
            // chapter(1) under the CURRENT config recomputes, never returning the stale instance.
            assertThat(it.chapter(1, config)).isNotSameInstanceAs(precomputed)
        }
    }

    @Test
    fun `publish is a no-op when the chapter is already cached`() {
        openStandard().use {
            val real = it.chapter(0, config)
            val precomputed = it.paginate(0, config)
            assertThat(it.publish(0, config, precomputed)).isFalse()
            assertThat(it.chapter(0, config)).isSameInstanceAs(real)
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

    // --- Inline images (Task 5): readBlocks resolves each Block.Image.href to bytes, and the
    // builder decodes them to an ImageSpan. A present, decodable image now paginates its
    // chapter to >= 1 page (it used to drop to zero); a missing/undecodable one degrades. ---

    /** A real, decodable PNG of the given size — solid red. */
    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.RED)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun imageOnlyChapterEpub(file: java.io.File, imageEntry: Pair<String, ByteArray>?) = buildEpub(file) {
        entry("META-INF/container.xml", CONTAINER_XML)
        entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Cover First</dc:title></metadata>
  <manifest>
    <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="cover"/><itemref idref="ch1"/></spine>
</package>""")
        entry("OEBPS/cover.xhtml", """<html><body><img src="img/cover.png"/></body></html>""")
        entry("OEBPS/ch1.xhtml", "<html><body><p>First chapter.</p></body></html>")
        imageEntry?.let { (name, bytes) -> entry(name, bytes) }
    }

    @Test
    fun `an inline image resolves to bytes and paginates its chapter to a page`() {
        val file = temp.newFile("cover-first.epub")
        imageOnlyChapterEpub(file, "OEBPS/img/cover.png" to pngBytes(600, 900)).close()

        EpubDocument.open(file, measurer).use { doc ->
            // The cover-image-first chapter used to paginate to ZERO pages (image dropped).
            // With the image resolved and rendered it is now one page — the image.
            val cover = doc.chapter(0, config)
            assertThat(cover.pages).isNotEmpty()
            val text = (cover.measured as AndroidMeasuredChapter).layout.text as Spanned
            assertThat(text.getSpans(0, text.length, android.text.style.ImageSpan::class.java)).hasLength(1)
        }
    }

    @Test
    fun `an image whose entry is missing degrades to no image and does not throw`() {
        val file = temp.newFile("broken-image.epub")
        // The <img> points at OEBPS/img/cover.png, but no such entry is written.
        imageOnlyChapterEpub(file, imageEntry = null).close()

        EpubDocument.open(file, measurer).use { doc ->
            // Unresolvable href -> null bytes -> the image renders nothing (no ImageSpan), no
            // throw. This is the pre-image-rendering behavior for that chapter: the image
            // contributes no text, so the image-only chapter lays out as blank exactly as
            // before. The text chapter still reads.
            val cover = doc.chapter(0, config)
            val text = (cover.measured as AndroidMeasuredChapter).layout.text as Spanned
            assertThat(text.getSpans(0, text.length, android.text.style.ImageSpan::class.java)).isEmpty()
            assertThat(doc.chapter(1, config).pages).isNotEmpty()
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

    // --- Fix wave 1, finding 3: nothing pinned the stylesheet-loading plumbing end to
    // end. Every unit test above would still pass if extractStylesheetRefs matched
    // nothing at all — this project has already shipped exactly that failure mode once. ---

    @Test
    fun `css emphasis from a linked external stylesheet survives end-to-end to the laid-out text`() {
        val file = temp.newFile("styled.epub")
        buildEpub(file) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Styled</dc:title></metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""")
            entry(
                "OEBPS/text/ch1.xhtml",
                """<html><head><link rel="stylesheet" href="../styles/s.css"/></head>""" +
                    """<body><p>A <span class="italic">word</span>.</p></body></html>""",
            )
            entry("OEBPS/styles/s.css", ".italic { font-style: italic }")
        }.close()

        EpubDocument.open(file, measurer).use { doc ->
            val chapter = doc.chapter(0, config)
            val text = (chapter.measured as AndroidMeasuredChapter).layout.text as Spanned
            val italicSpans = text.getSpans(0, text.length, StyleSpan::class.java)
                .filter { it.style == Typeface.ITALIC }

            assertThat(italicSpans).isNotEmpty()
            val span = italicSpans.first()
            assertThat(text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString())
                .isEqualTo("word")
        }
    }

    // --- Fix wave A, M3: the css cache was keyed by joinToString(" "), so an href
    // containing a space collided with a different chapter's two-href list and reused
    // the wrong CssRules. ---

    @Test
    fun `stylesheet cache does not collide when an href contains a space`() {
        val file = temp.newFile("collide.epub")
        buildEpub(file) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Collide</dc:title></metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
</package>""")
            // Chapter 1 references ONE stylesheet whose name contains a space —
            // "OEBPS/x OEBPS/y.css", which doesn't exist, so it contributes no rules.
            // Under the old joined-string key this is indistinguishable from chapter 2's
            // TWO stylesheets ["OEBPS/x", "OEBPS/y.css"], so chapter 2 wrongly reused
            // chapter 1's empty rules and lost its emphasis.
            entry(
                "OEBPS/ch1.xhtml",
                """<html><head><link rel="stylesheet" href="x OEBPS/y.css"/></head>""" +
                    """<body><p>Plain.</p></body></html>""",
            )
            entry(
                "OEBPS/ch2.xhtml",
                """<html><head><link rel="stylesheet" href="x"/><link rel="stylesheet" href="y.css"/></head>""" +
                    """<body><p>A <span class="italic">word</span>.</p></body></html>""",
            )
            entry("OEBPS/x", ".italic { font-style: italic }")
            entry("OEBPS/y.css", "p { color: black }")
        }.close()

        EpubDocument.open(file, measurer).use { doc ->
            doc.chapter(0, config) // populates the cache under the would-be colliding key
            val chapter = doc.chapter(1, config)
            val text = (chapter.measured as AndroidMeasuredChapter).layout.text as Spanned
            val italicSpans = text.getSpans(0, text.length, StyleSpan::class.java)
                .filter { it.style == Typeface.ITALIC }

            assertThat(italicSpans).isNotEmpty()
        }
    }

    // --- Fix wave A, M5: rel is a space-separated token list; rel="stylesheet alternate"
    // must still be treated as a stylesheet (we don't implement alternate selection). ---

    @Test
    fun `a multi-token rel attribute still loads the stylesheet`() {
        val file = temp.newFile("multitoken-rel.epub")
        buildEpub(file) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Multi Rel</dc:title></metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""")
            entry(
                "OEBPS/text/ch1.xhtml",
                """<html><head><link rel="stylesheet alternate" href="../styles/s.css"/></head>""" +
                    """<body><p>A <span class="italic">word</span>.</p></body></html>""",
            )
            entry("OEBPS/styles/s.css", ".italic { font-style: italic }")
        }.close()

        EpubDocument.open(file, measurer).use { doc ->
            val chapter = doc.chapter(0, config)
            val text = (chapter.measured as AndroidMeasuredChapter).layout.text as Spanned
            val italicSpans = text.getSpans(0, text.length, StyleSpan::class.java)
                .filter { it.style == Typeface.ITALIC }

            assertThat(italicSpans).isNotEmpty()
        }
    }

    @Test
    fun `a chapter referencing a missing stylesheet degrades to no emphasis rather than throwing`() {
        val file = temp.newFile("missing-css.epub")
        buildEpub(file) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Missing CSS</dc:title></metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""")
            entry(
                "OEBPS/text/ch1.xhtml",
                """<html><head><link rel="stylesheet" href="../styles/missing.css"/></head>""" +
                    """<body><p>A <span class="italic">word</span>.</p></body></html>""",
            )
        }.close()

        EpubDocument.open(file, measurer).use { doc ->
            val chapter = runCatching { doc.chapter(0, config) }.getOrThrow()
            val text = (chapter.measured as AndroidMeasuredChapter).layout.text as Spanned
            val italicSpans = text.getSpans(0, text.length, StyleSpan::class.java)
                .filter { it.style == Typeface.ITALIC }

            assertThat(italicSpans).isEmpty()
            assertThat(text.toString()).contains("word")
        }
    }

    // --- Task 6b, Part A: paginate() is safe to call off the main thread. This corroborates the
    // by-construction race-freedom (immutable pkg, concurrent ZipFile, per-call parser, concurrent
    // cssCache holding immutable CssRules) by hammering paginate() from many threads while the test
    // thread — standing in for "main" — drives chapter() through its single-thread-confined cache.
    // A pre-fix build (shared XhtmlBlockParser, plain-map cssCache) fails this with a corrupted
    // parse or a ConcurrentModificationException/NPE. ---

    /**
     * A multi-chapter EPUB engineered to make BOTH shared-mutable-state races observable in the
     * paginated output, not merely as a rare exception:
     *  - Each chapter links its OWN stylesheet with a DISTINCT absolute `body` font-size baseline
     *    ((10 + i)pt), plus a `.big` class sized in absolute px. The parser mines that baseline into
     *    its mutable `baselinePx`, and every `.big` span's rendered size is `24px / baseline` — so a
     *    shared parser whose `baselinePx` is overwritten by a concurrent parse of a different chapter
     *    yields the WRONG span size, hence different measurement and different pages. Distinct
     *    stylesheets also give [EpubDocument.cssCache] many keys inserted concurrently, so a plain
     *    (non-concurrent) map can corrupt/throw on resize.
     *  - Distinct coloured emphasis classes exercise the parser's `colorMemo`.
     * Chapter bodies vary in length so paginations differ per chapter.
     */
    private fun styledMultiChapterEpub(file: java.io.File, chapterCount: Int) = buildEpub(file) {
        entry("META-INF/container.xml", CONTAINER_XML)
        val items = (0 until chapterCount).joinToString("\n") { i ->
            """<item id="ch$i" href="ch$i.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val itemrefs = (0 until chapterCount).joinToString("\n") { i -> """<itemref idref="ch$i"/>""" }
        entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Stress</dc:title></metadata>
  <manifest>
$items
  </manifest>
  <spine>
$itemrefs
  </spine>
</package>""")
        for (i in 0 until chapterCount) {
            // A DISTINCT absolute baseline per chapter — this is what makes a shared `baselinePx`
            // corrupt the OUTPUT (via `.big`'s size ratio), not just the colour cache.
            entry(
                "OEBPS/ch$i.css",
                """
                body { font-size: ${10 + i}pt }
                .big { font-size: 24px }
                .a { color: #808080; font-style: italic }
                .b { color: navy; font-weight: bold }
                .c { color: rgb(64, 64, 64); text-decoration: underline }
                """.trimIndent(),
            )
            // Length varies with i so paginations differ; every chapter uses the coloured classes
            // and a `.big` absolute-px span so a fresh parser resolves colour and baseline each parse.
            val paras = (0..(i + 3)).joinToString("") { p ->
                """<p>Chapter $i paragraph $p with <span class="big">BIG</span> <span class="a">alpha</span>, """ +
                    """<span class="b">beta</span> and <span class="c">gamma</span> emphasis. """ +
                    "More words to fill the page. ".repeat(p + 1) + "</p>"
            }
            entry(
                "OEBPS/ch$i.xhtml",
                """<html><head><link rel="stylesheet" href="ch$i.css"/></head><body>$paras</body></html>""",
            )
        }
    }

    @Test
    fun `paginate is safe to call concurrently and matches the single-threaded result`() {
        val chapterCount = 6
        val file = temp.newFile("stress.epub")
        styledMultiChapterEpub(file, chapterCount).close()

        EpubDocument.open(file, measurer).use { doc ->
            // Single-threaded baseline: the pages and laid-out text each chapter MUST produce.
            // Captured before any concurrency so it is the trusted reference.
            fun layoutText(chapter: PaginatedChapter) =
                (chapter.measured as AndroidMeasuredChapter).layout.text.toString()
            val baselinePages = (0 until chapterCount).map { doc.paginate(it, config).pages }
            val baselineText = (0 until chapterCount).map { layoutText(doc.paginate(it, config)) }

            val threads = 8
            val iterations = 40
            val errors = CopyOnWriteArrayList<Throwable>()
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)

            // Worker threads only ever call paginate() — the operation under test. They race each
            // other AND the chapter() loop below on cssCache and on constructing parsers.
            val futures = (0 until threads).map { t ->
                pool.submit {
                    start.await()
                    repeat(iterations) { r ->
                        val i = (t + r) % chapterCount
                        try {
                            val result = doc.paginate(i, config)
                            if (result.pages != baselinePages[i]) {
                                error("chapter $i pages diverged under concurrency")
                            }
                            if (layoutText(result) != baselineText[i]) {
                                error("chapter $i laid-out text diverged under concurrency")
                            }
                        } catch (e: Throwable) {
                            errors.add(e)
                        }
                    }
                }
            }

            start.countDown()
            // The test thread stands in for the main thread: it alone drives chapter(), so the
            // unsynchronized chapter cache stays single-thread-confined exactly as in production,
            // while the workers hammer paginate() concurrently.
            repeat(iterations * threads) { r ->
                try {
                    doc.chapter(r % chapterCount, config)
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }

            futures.forEach { it.get(60, TimeUnit.SECONDS) }
            pool.shutdown()
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue()

            if (errors.isNotEmpty()) throw AssertionError("${errors.size} concurrent failure(s); first:", errors.first())
        }
    }
}
