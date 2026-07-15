package dev.reader.formats.epub

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
            val blocks = readBlocks(spineIndex)
            val measured = measurer.measure(blocks, config)
            val pages = if (blocks.isEmpty()) {
                emptyList()
            } else {
                paginator.paginate(measured, config.contentHeightPx)
            }
            PaginatedChapter(measured, pages)
        }
    }

    private fun readBlocks(spineIndex: Int): List<dev.reader.engine.Block> {
        // Unreachable by construction: EpubPackageParser.parseSpine already filters the
        // spine down to idrefs present in the manifest, so this lookup can never miss.
        // Kept as defense-in-depth, not as a live case.
        val item = pkg.manifest[pkg.spine[spineIndex]] ?: return emptyList()
        // A chapter listed in the spine but absent from the archive is a broken book, not
        // a crashing one: show it as empty and let the reader move on. readTextChecked
        // (rather than source.readText directly) is what translates a zip-bombed chapter
        // into a typed EpubException instead of a raw IOException escaping this method.
        val xhtml = readTextChecked(source, item.href) ?: return emptyList()
        return blockParser.parse(xhtml, item.href)
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
