package dev.reader.ui

import android.graphics.Bitmap
import android.os.Looper
import android.widget.FrameLayout
import com.google.common.truth.Truth.assertThat
import dev.reader.data.BookEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Robolectric coverage for [BookGridAdapter]'s async bind/cancel/stale-paint machinery — the part
 * [BookGridAdapterTest]'s pure-function tests can't reach. Decode timing is made deterministic by
 * substituting [BookGridAdapter.decodeCover] via a test subclass (the same protected-open seam
 * pattern as the Activity tests).
 */
@RunWith(RobolectricTestRunner::class)
class BookGridAdapterBindTest {

    private val scope = CoroutineScope(Job())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `rebinding a holder cancels the decode still in flight for the previous book`() {
        val adapter = TestableAdapter(scope)
        adapter.blockDecode = CountDownLatch(1)
        adapter.submitList(listOf(bookRow("/a.epub", coverPath = "/covers/a.png"), bookRow("/b.epub", coverPath = "/covers/b.png")))
        val holder = adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()), BookGridAdapter.VIEW_TYPE_BOOK_TILE) as BookGridAdapter.BookTileViewHolder

        adapter.onBindViewHolder(holder, 0)
        assertThat(adapter.decodeEntered.await(5, TimeUnit.SECONDS)).isTrue()
        val firstJob = holder.job!!

        // A DiffUtil move reuses the holder for a different position without onViewRecycled —
        // the rebind itself must cancel whatever is still decoding for the old book.
        adapter.onBindViewHolder(holder, 1)

        assertThat(firstJob.isCancelled).isTrue()
        adapter.blockDecode!!.countDown()
    }

    @Test
    fun `a stale decode finishing after a rebind never paints into the cell`() {
        val adapter = TestableAdapter(scope)
        adapter.blockDecode = CountDownLatch(1)
        adapter.submitList(listOf(bookRow("/a.epub", coverPath = "/covers/a.png"), bookRow("/b.epub", coverPath = null)))
        val holder = adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()), BookGridAdapter.VIEW_TYPE_BOOK_TILE) as BookGridAdapter.BookTileViewHolder

        adapter.onBindViewHolder(holder, 0)
        assertThat(adapter.decodeEntered.await(5, TimeUnit.SECONDS)).isTrue()
        val staleJob = holder.job!!

        // Rebound to a coverless book while A's decode is still blocked: the cell is cleared and
        // must stay cleared no matter when A's decode finishes.
        adapter.onBindViewHolder(holder, 1)
        assertThat(holder.cover.drawable).isNull()

        adapter.blockDecode!!.countDown()
        idleUntil { staleJob.isCompleted }

        assertThat(holder.cover.drawable).isNull()
    }

    @Test
    fun `a same-path rebind with a new mtime stamps a new cache key`() {
        // The M5 regression pin, at the unit the fix changed: the holder is stamped with the full
        // (path, mtime) cache key, not the bare path, so a cover regenerated in place at the same
        // path — different bytes — can never satisfy a stale decode's paint guard.
        val adapter = TestableAdapter(scope)
        adapter.submitList(
            listOf(
                bookRow("/a.epub", coverPath = "/covers/a.png", modifiedAtMs = 1_000L),
                bookRow("/a2.epub", coverPath = "/covers/a.png", modifiedAtMs = 2_000L),
            ),
        )
        val holder = adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()), BookGridAdapter.VIEW_TYPE_BOOK_TILE) as BookGridAdapter.BookTileViewHolder

        adapter.onBindViewHolder(holder, 0)
        val firstKey = holder.boundCacheKey
        adapter.onBindViewHolder(holder, 1)
        val secondKey = holder.boundCacheKey

        assertThat(firstKey).isEqualTo(coverCacheKey("/covers/a.png", 1_000L))
        assertThat(secondKey).isEqualTo(coverCacheKey("/covers/a.png", 2_000L))
        assertThat(firstKey).isNotEqualTo(secondKey)
    }

    @Test
    fun `a book in list mode binds a text row and never touches the cover machinery`() {
        // The list-mode invariant: a book row must not enter the cover cache or launch a decode
        // coroutine, no matter that the book has a coverPath. blockDecode is armed so that if a
        // decode ever DID start it would block and the assertion below would still catch it (the
        // latch would have counted down).
        val adapter = TestableAdapter(scope)
        adapter.blockDecode = CountDownLatch(1)
        adapter.render(listOf(bookRow("/a.epub", coverPath = "/covers/a.png")), ViewMode.LIST)
        idleUntil { adapter.currentList.size == 1 }

        assertThat(adapter.getItemViewType(0)).isEqualTo(BookGridAdapter.VIEW_TYPE_BOOK_ROW)
        val holder = adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()), BookGridAdapter.VIEW_TYPE_BOOK_ROW)
        assertThat(holder).isInstanceOf(BookGridAdapter.BookRowViewHolder::class.java)

        adapter.onBindViewHolder(holder, 0)
        shadowOf(Looper.getMainLooper()).idle()

        // decodeCover was never called: the latch is untouched (a book tile bind would have
        // counted decodeEntered down to 0).
        assertThat(adapter.decodeEntered.count).isEqualTo(1L)
        adapter.blockDecode!!.countDown()
    }

    /** [BookGridAdapter] whose decode is latched, so tests control exactly when it finishes. */
    private class TestableAdapter(scope: CoroutineScope) : BookGridAdapter(scope, onBookClick = {}, onFolderClick = {}) {
        val decodeEntered = CountDownLatch(1)
        var blockDecode: CountDownLatch? = null

        override fun decodeCover(path: String): Bitmap? {
            decodeEntered.countDown()
            blockDecode?.await()
            return Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565)
        }
    }

    private fun idleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun bookRow(path: String, coverPath: String?, modifiedAtMs: Long = 1_700_000_000_000L) =
        LibraryRow.Book(book(path, coverPath, modifiedAtMs))

    private fun book(path: String, coverPath: String?, modifiedAtMs: Long = 1_700_000_000_000L) = BookEntity(
        path = path,
        sizeBytes = 1_000L,
        modifiedAtMs = modifiedAtMs,
        title = "T",
        author = null,
        coverPath = coverPath,
        spineIndex = 0,
        charOffset = 0,
        unreadable = false,
        unreadableReason = null,
        addedAtMs = 1_700_000_000_000L,
        lastOpenedAtMs = null,
    )
}
