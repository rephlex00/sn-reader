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
import java.util.concurrent.ConcurrentHashMap

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
    //
    // A ConcurrentHashMap (not a plain map) because [paginate] runs off the main thread
    // for background prefetch while [chapter] runs on it: two threads reaching [cssFor]
    // for chapters sharing a stylesheet would otherwise structurally corrupt a plain map.
    // cssFor inserts via computeIfAbsent, which parses each distinct key exactly once even
    // under concurrency (the "parsed once for the life of the document" guarantee above
    // survives the move off a single thread). The stored [CssRules] is deeply immutable,
    // so a value published by one thread is safe for another to read.
    private val cssCache = ConcurrentHashMap<Pair<List<String>, List<String>>, CssRules>()

    override val metadata: BookMetadata get() = pkg.metadata
    override val spineSize: Int get() = pkg.spine.size

    /**
     * Per-chapter weights for a whole-book progress estimate: each spine chapter's uncompressed
     * XHTML byte size, in spine order (`0L` for a spine id missing from the manifest or archive).
     * Read from the zip central directory via [ResourceSource.size] — no chapter is paginated to
     * weigh it, so this stays cheap enough to read at open time. Computed once (the archive is
     * immutable) and reused. Length equals [spineSize].
     *
     * Thread-safe: Kotlin's `by lazy` defaults to [LazyThreadSafetyMode.SYNCHRONIZED], so the
     * initializer runs under a lock exactly once and all threads then observe the published result.
     */
    val chapterWeights: List<Long> by lazy {
        pkg.spine.map { idref -> pkg.manifest[idref]?.href?.let(source::size) ?: 0L }
    }

    /**
     * Measures and paginates chapter [spineIndex]. Cached per config (bounded to the
     * current chapter and a neighbour on each side), so paging within a chapter costs
     * nothing after the first call.
     *
     * NOT thread-safe: [cacheConfig] and [cache] are unsynchronized. Callers must confine
     * all calls to a single thread. Background prefetch must call the pure [paginate] off the
     * main thread and publish through [publish] on a main-thread hop — never [chapter] — because
     * the cache is a `LinkedHashMap(accessOrder = true)` where even a read mutates link order.
     */
    fun chapter(spineIndex: Int, config: RenderConfig): PaginatedChapter {
        require(spineIndex in pkg.spine.indices) {
            "spineIndex $spineIndex out of range 0..${pkg.spine.lastIndex}"
        }
        if (cacheConfig != config) {
            cache.clear()
            cacheConfig = config
        }
        return cache.getOrPut(spineIndex) { paginate(spineIndex, config) }
    }

    /**
     * Measures and paginates chapter [spineIndex] under [config] WITHOUT touching the chapter
     * cache — a pure function of its inputs (and the immutable archive). This is the compute half
     * of [chapter], split out so a background prefetch can run it off the main thread (StaticLayout
     * construction is off-main-thread-safe) and then hand the result to [publish] on a main-thread
     * hop.
     *
     * Thread-safe by construction: it is safe to call concurrently with itself and with a
     * main-thread [chapter]/page turn, because it shares NO mutable state across threads. Every
     * input it reads is either immutable or independently concurrent —
     *  - [pkg] (manifest/spine) and [config] are immutable;
     *  - [source] wraps a `java.util.zip.ZipFile`, whose `getEntry`/`getInputStream` serve
     *    independent streams safely to concurrent readers;
     *  - the [XhtmlBlockParser] is constructed fresh per [readBlocks] call, so its documented
     *    per-parse mutable state (baseline, colour memo) is confined to the calling thread — no
     *    instance is ever shared;
     *  - [measurer] and [paginator] hold no mutable instance state (fresh `TextPaint`/builder per
     *    call);
     *  - [cssCache] is a [ConcurrentHashMap] holding deeply-immutable [CssRules] values.
     * It reads no chapter-cache state and writes none, so a settings change or page turn racing it
     * cannot observe a partial result. Publishing the result is [publish]'s job, and that touches
     * the (unsynchronized) chapter cache, so it — like [chapter] — stays main-thread only.
     */
    fun paginate(spineIndex: Int, config: RenderConfig): PaginatedChapter {
        require(spineIndex in pkg.spine.indices) {
            "spineIndex $spineIndex out of range 0..${pkg.spine.lastIndex}"
        }
        val blocks = readBlocks(spineIndex, config)
        val measured = measurer.measure(blocks, config)
        val pages = if (blocks.isEmpty()) {
            emptyList()
        } else {
            paginator.paginate(measured, config.contentHeightPx)
        }
        return PaginatedChapter(measured, pages)
    }

    /**
     * Publishes a [paginate] result into the cache, but ONLY if [config] still matches the cache's
     * current config — i.e. the reader has not changed a typography setting since the background
     * prefetch began (which would have made this result stale). Returns true if published. Main
     * thread only, like [chapter], since it touches [cache]/[cacheConfig]. A no-op if the entry is
     * already cached (a real read raced ahead) or the config moved on.
     */
    fun publish(spineIndex: Int, config: RenderConfig, chapter: PaginatedChapter): Boolean {
        if (cacheConfig != config) return false
        if (cache.containsKey(spineIndex)) return false
        cache[spineIndex] = chapter
        return true
    }

    /**
     * Whether chapter [spineIndex] is already paginated under [config] — a read-only cache peek so a
     * background prefetch can skip re-paginating a neighbour that is already cached (the common case
     * after a forward read, where the previous chapter is still resident). Main thread only, like
     * [chapter]/[publish], since it reads [cache]/[cacheConfig]; `containsKey` (unlike `get`) does
     * NOT reorder the access-ordered cache, so a peek never disturbs LRU eviction.
     */
    fun isPaginated(spineIndex: Int, config: RenderConfig): Boolean =
        cacheConfig == config && cache.containsKey(spineIndex)

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
        // The builder (and AndroidTextMeasurer) can't reach the zip — ResourceSource is
        // private to this class — so the image bytes are resolved HERE and carried on the
        // block. The parser already resolved each Block.Image.href to an archive path
        // (resolveHref against the chapter), exactly as coverHref is pre-resolved for
        // EpubCoverExtractor, so this reads it directly. An unresolvable/oversized entry
        // degrades to null bytes (the renderer then draws nothing) rather than throwing.
        // A fresh parser per call, never a shared field: XhtmlBlockParser is documented NOT
        // thread-safe (per-parse baseline + colour memo), and [paginate] can run on a background
        // prefetch thread concurrently with a main-thread chapter load. A per-call instance
        // confines that mutable state to the calling thread — the parser's "confine to one thread"
        // contract — at the cost only of a per-chapter colour memo instead of a per-document one
        // (colour parsing is a trivial regex, still memoized within the chapter).
        return XhtmlBlockParser().parse(doc, item.href, css, config.inferHeadings).map { block ->
            if (block is Block.Image) block.copy(bytes = resolveImageBytes(block.href)) else block
        }
    }

    /**
     * Reads an inline image's bytes through [readBytesChecked] — the same size-capped binary
     * read the cover extractor uses — translating the oversized/unreadable case to null
     * rather than letting it abort the chapter. Decoding and downsampling happen later, in
     * the builder; this only fetches the raw entry.
     */
    private fun resolveImageBytes(href: String): ByteArray? =
        try {
            readBytesChecked(source, href)
        } catch (e: EpubException.Malformed) {
            null // an oversized/unreadable image entry degrades to no image
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

        // computeIfAbsent (not getOrPut): its read-modify-write is atomic per key on a
        // ConcurrentHashMap, so a stylesheet shared by two chapters paginating on two threads is
        // parsed exactly once, never raced into a corrupt map. The mapping function only reads the
        // archive and parses CSS — it never touches cssCache — so it cannot deadlock the bin lock.
        return cssCache.computeIfAbsent(cacheKey) {
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
