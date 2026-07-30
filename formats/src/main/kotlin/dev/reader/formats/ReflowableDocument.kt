package dev.reader.formats

import dev.reader.engine.Block
import dev.reader.engine.MeasuredChapter
import dev.reader.engine.Page
import dev.reader.engine.Paginator
import dev.reader.engine.RenderConfig
import dev.reader.engine.TextMeasurer

/** One chapter, measured and sliced into pages. */
data class PaginatedChapter(val measured: MeasuredChapter, val pages: List<Page>)

/**
 * Chapters worth holding at once: the current chapter plus a neighbour on each side.
 * The background prefetch wants exactly this window.
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

/**
 * A book of reflowable text: chapters that are measured and paginated to the reader's current
 * typography, as opposed to a paged format (a comic) whose pages are fixed pictures.
 *
 * This class exists because the measuring, caching and pagination around a chapter turned out to
 * be entirely format-neutral. Everything below operates on [Block]s and a [RenderConfig]; nothing
 * here knows what a spine, an OPF or a PalmDB record is. A format supplies exactly one thing —
 * [readBlocks], "give me chapter N as a list of blocks" — and inherits pagination, the LRU cache,
 * the off-main-thread prefetch protocol, and with them page turns, marks, previews and progress.
 *
 * `Document`'s own note said a shared render method "would be a fiction until a second format
 * exists to constrain it". MOBI is that second format, and this is the shape the two of them
 * agreed on: not a render method, but a block source with a cache in front of it.
 *
 * **Threading.** [paginate] is pure and safe to call from any thread; [chapter], [publish] and
 * [isPaginated] touch the unsynchronized cache and are main-thread only. Subclasses must keep
 * [readBlocks] thread-safe — see its contract.
 */
abstract class ReflowableDocument(protected val measurer: TextMeasurer) : Document {

    private val paginator = Paginator()

    // Only the current config's chapters are worth holding; a settings change invalidates all of
    // them, and the locator puts the reader back on the same sentence anyway. Within a config,
    // only a small neighbourhood of chapters is worth holding either — see LruChapterCache.
    private var cacheConfig: RenderConfig? = null
    private val cache = LruChapterCache()

    /**
     * Per-chapter weights for a whole-book progress estimate, in chapter order, length equal to
     * [spineSize]. Must be cheap: it is read at open time, and no chapter may be paginated to
     * weigh it. A format with no better answer may return uniform weights.
     */
    abstract val chapterWeights: List<Long>

    /**
     * Chapter [spineIndex] as blocks, under [config]. The one thing a reflowable format must
     * supply, and the only place format knowledge lives.
     *
     * MUST be thread-safe and free of shared mutable state: [paginate] calls this from the
     * background prefetch thread concurrently with a main-thread chapter load. Construct any
     * stateful helper (a parser, a decompressor) fresh per call rather than holding it in a field.
     *
     * A chapter that cannot be read is an empty list, not an exception: a broken chapter in an
     * otherwise readable book shows as a blank page the reader can turn past.
     */
    protected abstract fun readBlocks(spineIndex: Int, config: RenderConfig): List<Block>

    /**
     * Measures and paginates chapter [spineIndex]. Cached per config (bounded to the current
     * chapter and a neighbour on each side), so paging within a chapter costs nothing after the
     * first call.
     *
     * NOT thread-safe: [cacheConfig] and [cache] are unsynchronized. Callers must confine all
     * calls to a single thread. Background prefetch must call the pure [paginate] off the main
     * thread and publish through [publish] on a main-thread hop — never this — because the cache
     * is a `LinkedHashMap(accessOrder = true)` where even a read mutates link order.
     */
    fun chapter(spineIndex: Int, config: RenderConfig): PaginatedChapter {
        requireChapter(spineIndex)
        if (cacheConfig != config) {
            cache.clear()
            cacheConfig = config
        }
        return cache.getOrPut(spineIndex) { paginate(spineIndex, config) }
    }

    /**
     * Measures and paginates chapter [spineIndex] under [config] WITHOUT touching the chapter
     * cache — a pure function of its inputs (and the immutable file). This is the compute half of
     * [chapter], split out so a background prefetch can run it off the main thread (StaticLayout
     * construction is off-main-thread-safe) and then hand the result to [publish] on a main-thread
     * hop. Safe to call concurrently with itself and with a main-thread [chapter]: it reads no
     * cache state and writes none, so a settings change or page turn racing it cannot observe a
     * partial result.
     */
    fun paginate(spineIndex: Int, config: RenderConfig): PaginatedChapter {
        requireChapter(spineIndex)
        val blocks = readBlocks(spineIndex, config)
        val measured = measurer.measure(blocks, config)
        val pages = if (blocks.isEmpty()) emptyList() else paginator.paginate(measured, config.contentHeightPx)
        return PaginatedChapter(measured, pages)
    }

    /**
     * Publishes a [paginate] result into the cache, but ONLY if [config] still matches the cache's
     * current config — i.e. the reader has not changed a typography setting since the background
     * prefetch began (which would have made this result stale). Returns true if published. Main
     * thread only, like [chapter], since it touches the cache. A no-op if the entry is already
     * cached (a real read raced ahead) or the config moved on.
     */
    fun publish(spineIndex: Int, config: RenderConfig, chapter: PaginatedChapter): Boolean {
        if (cacheConfig != config) return false
        if (cache.containsKey(spineIndex)) return false
        cache[spineIndex] = chapter
        return true
    }

    /**
     * Whether chapter [spineIndex] is already paginated under [config] — a read-only cache peek so
     * a background prefetch can skip re-paginating a neighbour that is already cached. Main thread
     * only; `containsKey` (unlike `get`) does NOT reorder the access-ordered cache, so a peek never
     * disturbs LRU eviction.
     */
    fun isPaginated(spineIndex: Int, config: RenderConfig): Boolean =
        cacheConfig == config && cache.containsKey(spineIndex)

    private fun requireChapter(spineIndex: Int) =
        require(spineIndex in 0 until spineSize) {
            "spineIndex $spineIndex out of range 0..${spineSize - 1}"
        }
}
