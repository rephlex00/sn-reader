package dev.reader.ui

import android.graphics.Bitmap
import android.util.LruCache
import java.io.InputStream

/** Thumbnail target for a hovered page during scrub: `inSampleSize` ≈ 8 against a typical comic
 *  page lands here (see [ComicPageDecoder.computeSampleSize]/[computeSampleSize]). */
private const val PREVIEW_WIDTH_PX = 159
private const val PREVIEW_HEIGHT_PX = 219

/**
 * On-demand comic-page previews for the timeline's floating thumbnail, per the design spec's "no
 * pre-generation, no disk, no toggle" answer: unlike [PreviewStripStore] (EPUB, which must paginate
 * and render text to produce a preview), a comic page is already an image, so decoding it directly
 * at thumbnail size is cheap enough (~10-30 ms per the spec) to do the moment a page is hovered.
 *
 * Cancellation is the CALLER's job, not this class's: [preview] is a plain suspend function with no
 * internal job bookkeeping, so cancelling the coroutine that is calling it (e.g. because the drag
 * moved to a different page before this decode finished) cancels the decode for free at its
 * [ComicPageDecoder]-owned `withContext(Dispatchers.Default)` boundary.
 *
 * The [cache] bounds by entry COUNT, not bytes, unlike [BookGridAdapter]'s cover cache: at a fixed
 * thumbnail size (~159x219 ARGB_8888, ~139 KB each) [cacheSize] entries is already a small, known
 * byte budget (~1.1 MB at the default 8), so there is no growing-library axis to protect against.
 * [cacheSize] must be at least 2: at 1, every new page would evict (and recycle) the one entry
 * already on screen the instant a second page is decoded, before the caller has a chance to move on
 * from it.
 *
 * [entryRemoved] recycles EVERY bitmap it displaces, unconditionally — including the REPLACE path
 * (two `put` calls landing on the same key, not just an eviction past [cacheSize]). That is the
 * real caller contract, and it is stricter than "the LRU only evicts the least-recently-used entry"
 * sounds: if two [preview] calls for the SAME page are ever in flight at once, the second call's
 * `put` recycles the bitmap the first call already produced — even if a caller is already showing
 * it in an `ImageView` — because `entryRemoved` has no way to know the old value is still on
 * screen. Concretely, this loader is only safe to call the way [ComicActivity]'s scrubber does it:
 * cancel any in-flight [preview] job for the previous hover BEFORE launching a new one, so that
 * `withContext`'s prompt-cancellation guarantee discards a superseded decode's result before it
 * ever reaches `cache.put` — never fire two overlapping [preview] calls for the same page and trust
 * LRU ordering to save you, because it will not.
 */
class ComicPreviewLoader(private val decoder: ComicPageDecoder, private val cacheSize: Int = 8) {

    init {
        require(cacheSize >= 2) { "cacheSize must be >= 2 (was $cacheSize) — see the class KDoc" }
    }

    private val cache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue) oldValue.recycle()
        }
    }

    /** The cached thumbnail for [page] if present, otherwise decodes one at preview size and caches
     *  it. Null on a missing/unreadable page ([streamProvider] returning null, or an undecodable
     *  stream) — never throws for that case. */
    suspend fun preview(page: Int, streamProvider: () -> InputStream?): Bitmap? {
        cache.get(page)?.let { return it }
        val bitmap = decoder.decode(streamProvider, PREVIEW_WIDTH_PX, PREVIEW_HEIGHT_PX) ?: return null
        cache.put(page, bitmap)
        return bitmap
    }

    /** Recycles and drops every cached preview. Called from `onDestroy`. */
    fun clear() = cache.evictAll()
}
