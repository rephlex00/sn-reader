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
 *
 * [entryRemoved] recycles evicted bitmaps so the LRU doesn't leak native memory as the drag moves
 * across many pages. This is safe for the bitmap the caller is CURRENTLY showing (the most recently
 * hovered page) because that entry was just read or written and is therefore the most-recently-used
 * one in the cache — [LruCache] only evicts least-recently-used entries once [cacheSize] is
 * exceeded, so the one entry actually on screen is the last to go.
 */
class ComicPreviewLoader(private val decoder: ComicPageDecoder, private val cacheSize: Int = 8) {

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
