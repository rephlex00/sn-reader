package dev.reader.formats.epub

import dev.reader.engine.Block
import dev.reader.engine.BookMetadata
import dev.reader.engine.MeasuredChapter
import dev.reader.engine.Page
import dev.reader.engine.Paginator
import dev.reader.engine.RenderConfig
import dev.reader.engine.TextMeasurer
import dev.reader.engine.TocEntry
import dev.reader.formats.Document
import dev.reader.formats.ResourceSource
import dev.reader.formats.ZipResourceSource
import org.jsoup.Jsoup
import org.jsoup.nodes.Document as JsoupDocument
import java.io.File

/** One chapter, measured and sliced into pages. */
data class PaginatedChapter(val measured: MeasuredChapter, val pages: List<Page>)

/**
 * Chapters worth holding at once: the current chapter plus a neighbour on each side.
 * A later plan's background pagination is expected to want exactly this window.
 */
private const val CHAPTER_CACHE_CAPACITY = 3

/**
 * Bounds the chapter cache to [CHAPTER_CACHE_CAPACITY] entries, evicting least-recently-used.
 * Each retained entry pins a `StaticLayout` + `Spanned` over a full chapter's text, so an
 * unbounded cache would retain every chapter of a book read straight through for the whole
 * session — on an e-ink device with modest RAM that is a real leak.
 */
private class LruChapterCache :
    LinkedHashMap<Int, PaginatedChapter>(CHAPTER_CACHE_CAPACITY, 0.75f, /* accessOrder = */ true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PaginatedChapter>): Boolean =
        size > CHAPTER_CACHE_CAPACITY
}

class EpubDocument private constructor(
    private val source: ResourceSource,
    private val pkg: EpubPackage,
    private val measurer: TextMeasurer,
    override val toc: List<TocEntry>,
) : Document {

    private val blockParser = XhtmlBlockParser()
    private val paginator = Paginator()

    // Only the current config's chapters are worth holding; a settings change invalidates
    // all of them, and the locator puts the reader back on the same sentence anyway.
    // Within a config, only a small neighbourhood of chapters is worth holding too — see
    // LruChapterCache.
    private var cacheConfig: RenderConfig? = null
    private val cache = LruChapterCache()

    // Keyed by the exact list of stylesheet sources a chapter resolves to (see cssFor):
    // in the overwhelmingly common shape - one stylesheet shared by every chapter in the
    // book - every chapter maps to the same key, so the stylesheet is parsed exactly
    // once for the life of the document rather than once per chapter. The key is the
    // (hrefs, inline blocks) Pair of Lists (both have value equality), NOT a joined or
    // concatenated form: an href containing a space would make ["a b.css"] and
    // ["a", "b.css"] collide as joined strings, and flat concatenation would lose the
    // boundary between the href list and the inline-block list.
    private val cssCache = mutableMapOf<Pair<List<String>, List<String>>, CssRules>()

    override val metadata: BookMetadata get() = pkg.metadata
    override val spineSize: Int get() = pkg.spine.size

    /**
     * Measures and paginates chapter [spineIndex]. Cached per config (bounded to the
     * current chapter and a neighbour on each side), so paging within a chapter costs
     * nothing after the first call.
     *
     * NOT thread-safe: [cacheConfig] and [cache] are unsynchronized. Callers must confine
     * all calls to a single thread.
     */
    fun chapter(spineIndex: Int, config: RenderConfig): PaginatedChapter {
        require(spineIndex in pkg.spine.indices) {
            "spineIndex $spineIndex out of range 0..${pkg.spine.lastIndex}"
        }
        if (cacheConfig != config) {
            cache.clear()
            cacheConfig = config
        }
        return cache.getOrPut(spineIndex) {
            val blocks = readBlocks(spineIndex, config)
            val measured = measurer.measure(blocks, config)
            val pages = if (blocks.isEmpty()) {
                emptyList()
            } else {
                paginator.paginate(measured, config.contentHeightPx)
            }
            PaginatedChapter(measured, pages)
        }
    }

    private fun readBlocks(spineIndex: Int, config: RenderConfig): List<Block> {
        // Unreachable by construction: EpubPackageParser.parseSpine already filters the
        // spine down to idrefs present in the manifest, so this lookup can never miss.
        // Kept as defense-in-depth, not as a live case.
        val item = pkg.manifest[pkg.spine[spineIndex]] ?: return emptyList()
        // A chapter listed in the spine but absent from the archive is a broken book, not
        // a crashing one: show it as empty and let the reader move on. readTextChecked
        // (rather than source.readText directly) is what translates a zip-bombed chapter
        // into a typed EpubException instead of a raw IOException escaping this method.
        val xhtml = readTextChecked(source, item.href) ?: return emptyList()
        // Parsed once here and threaded through both cssFor (which mines <head>/<body>
        // for stylesheet references) and blockParser.parse (which walks <body> for
        // content) — a second Jsoup.parse of the same chapter would double the HTML
        // parsing cost of every chapter load for no benefit.
        val doc = Jsoup.parse(xhtml)
        val css = cssFor(doc, item.href)
        return blockParser.parse(doc, item.href, css, config.inferHeadings)
    }

    /**
     * Gathers every stylesheet [chapterPath]'s XHTML references - `<link rel="stylesheet">`
     * hrefs plus any inline `<style>` blocks - and parses them into one [CssRules],
     * mirroring how a browser cascades multiple stylesheets over one document (later
     * source wins on a tie). Keyed in [cssCache] by the resolved sources: a book where
     * every chapter shares one stylesheet resolves to the same key every time, so that
     * stylesheet is read and parsed exactly once for the life of this document, not once
     * per chapter. A missing or unreadable stylesheet contributes nothing rather than
     * failing the whole chapter.
     */
    private fun cssFor(doc: JsoupDocument, chapterPath: String): CssRules {
        val refs = extractStylesheetRefs(doc)
        if (refs.hrefs.isEmpty() && refs.inlineBlocks.isEmpty()) return CssRules.EMPTY

        val resolvedHrefs = refs.hrefs.map { resolveHref(chapterPath, it) }
        val cacheKey = resolvedHrefs to refs.inlineBlocks

        return cssCache.getOrPut(cacheKey) {
            val combined = buildString {
                for (href in resolvedHrefs) {
                    val text = try {
                        readTextChecked(source, href)
                    } catch (e: EpubException.Malformed) {
                        null // an oversized/unreadable stylesheet degrades to contributing nothing
                    }
                    if (text != null) {
                        append(text)
                        append('\n')
                    }
                }
                for (inline in refs.inlineBlocks) {
                    append(inline)
                    append('\n')
                }
            }
            CssRules.parse(combined)
        }
    }

    override fun close() = source.close()

    companion object {
        /** @throws EpubException if the file is not a readable, un-DRMed EPUB. */
        fun open(file: File, measurer: TextMeasurer): EpubDocument {
            // Constructing the source is itself a typed-error boundary: ZipFile throws
            // raw ZipException for a .txt renamed .epub or a truncated download, which
            // no caller catching EpubException would survive. It can also throw
            // SecurityException (e.g. a denied file-access manager on some platforms) —
            // translate that too, since the point of this try is that no raw exception
            // survives open().
            val source = try {
                ZipResourceSource(file)
            } catch (e: java.io.IOException) {
                throw EpubException.NotAnEpub("Not a readable EPUB archive: ${e.message}")
            } catch (e: SecurityException) {
                throw EpubException.NotAnEpub("Not a readable EPUB archive: ${e.message}")
            }
            try {
                val pkg = EpubPackageParser().parse(source)
                val toc = EpubTocParser().parse(source, pkg)
                return EpubDocument(source, pkg, measurer, toc)
            } catch (e: Throwable) {
                source.close()
                throw e
            }
        }
    }
}

/** The raw stylesheet sources a chapter's XHTML references, before resolving or parsing. */
private data class StylesheetRefs(val hrefs: List<String>, val inlineBlocks: List<String>)

/**
 * Finds every `<link rel="stylesheet">` href and `<style>` block anywhere in [doc] (head
 * or body — malformed EPUBs occasionally misplace one), in document order. Takes the
 * already-parsed [org.jsoup.nodes.Document] rather than re-parsing the chapter's raw
 * XHTML itself: [XhtmlBlockParser.parse] needs a full parse of its own to walk `<body>`'s
 * Block-producing content, so [readBlocks] parses once and passes the same [Document]
 * here rather than doubling the HTML-parsing cost of every chapter load.
 */
private fun extractStylesheetRefs(doc: JsoupDocument): StylesheetRefs {
    // `rel` is a space-separated token list (e.g. rel="stylesheet alternate"), not a
    // single value — match by token membership, case-insensitively, same as the
    // epub:type fix in EpubToc. We don't implement alternate-stylesheet *selection*;
    // a multi-token rel that includes "stylesheet" simply loads like any other.
    val hrefs = doc.select("link")
        .filter { "stylesheet" in it.attr("rel").lowercase().split(relTokenSeparator) }
        .mapNotNull { it.attr("href").takeIf(String::isNotBlank) }
    val inlineBlocks = doc.select("style").map { it.data() }.filter(String::isNotBlank)
    return StylesheetRefs(hrefs, inlineBlocks)
}

private val relTokenSeparator = Regex("\\s+")
