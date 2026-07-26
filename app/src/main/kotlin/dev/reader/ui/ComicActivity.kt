package dev.reader.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import dev.reader.R
import dev.reader.ReaderApplication
import dev.reader.data.BookmarkEntity
import dev.reader.formats.comic.ComicDocument
import dev.reader.formats.comic.ComicException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Image-based comic reader. Portrait-locked (manifest). Reuses the text reader's tap zones, EPD
 *  refresher and page-turn-driven prefetch, but draws bitmaps, not text. */
open class ComicActivity : AppCompatActivity() {

    private lateinit var pageView: ComicPageView
    private lateinit var overlay: View
    private lateinit var titleView: TextView
    private lateinit var readout: TextView
    private lateinit var directionButton: TextView
    private lateinit var bookmarkButton: ImageView
    private lateinit var bookmarksPanel: ComicBookmarksPanel
    private val decoder = ComicPageDecoder()

    private var document: ComicDocument? = null
    private var bookPath: String = ""
    private var pageCount: Int = 0
    private var currentPage: Int = 0
    protected var rtl: Boolean = false
    private var currentBitmap: Bitmap? = null
    private var prefetch: Pair<Int, Bitmap>? = null
    private var opening = false
    private var bookmarks: List<BookmarkEntity> = emptyList()

    private val app get() = application as ReaderApplication
    private val dao get() = app.database.bookDao()
    private val bookmarkDao get() = app.database.bookmarkDao()

    // ---- Test seams ----
    val pagesShownForTest = mutableListOf<Int>()
    val currentPageForTest: Int get() = currentPage
    val rtlForTest: Boolean get() = rtl
    val bookmarkedPagesForTest: List<Int> get() = bookmarks.map { it.spineIndex }
    fun onTapForTest(zone: TapZone) = onTap(zone)
    fun toggleDirectionForTest() = toggleDirection()
    fun toggleBookmarkForTest() = toggleBookmark()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageView = ComicPageView(this).apply {
            epd = EinkController.forContext(this@ComicActivity)
            onTap = ::onTap
        }

        // Wrap the page in a container so the overlay can draw ABOVE it, mirroring
        // ReaderActivity's container shape: pageView is added first, the inflated overlay second,
        // so it sits on top while page-area taps still fall through to pageView's onTap.
        val container = FrameLayout(this)
        container.addView(pageView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        overlay = layoutInflater.inflate(R.layout.overlay_comic, container, false)
        container.addView(overlay)

        titleView = overlay.findViewById(R.id.comic_title)
        readout = overlay.findViewById(R.id.comic_readout)
        // Literata + tabular numerals, same as ReaderActivity.scrubberView: the readout's digits
        // must not shift width as they change, and the XML's default sans doesn't carry tabular
        // figures. 16sp/black stay as set in overlay_comic.xml — only the face and figure style
        // change here.
        readout.typeface = ResourcesCompat.getFont(this, R.font.literata)
        readout.fontFeatureSettings = "tnum"
        directionButton = overlay.findViewById(R.id.comic_direction_button)
        bookmarkButton = overlay.findViewById(R.id.comic_bookmark_button)

        overlay.findViewById<View>(R.id.comic_back).setOnClickListener { finish() }
        directionButton.setOnClickListener { toggleDirection() }
        bookmarkButton.setOnClickListener { toggleBookmark() }
        overlay.findViewById<View>(R.id.comic_bookmarks_button).setOnClickListener {
            bookmarksPanel.show(bookmarks); bookmarksPanel.visibility = View.VISIBLE
        }

        bookmarksPanel = ComicBookmarksPanel(this) { page ->
            bookmarksPanel.visibility = View.GONE
            if (page in 0 until pageCount) showPage(page)
        }.apply { visibility = View.GONE }
        container.addView(bookmarksPanel, FrameLayout.LayoutParams(-1, -1))
        setContentView(container)
        pageView.doOnLayout { openComic() }
    }

    open fun openComic() {
        if (document != null || opening) return
        opening = true
        val path = intent.getStringExtra(ReaderActivity.EXTRA_BOOK_PATH)
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { path?.let { File(it).takeIf(File::isFile) } }
            if (file == null) { finishMissing(); return@launch }
            val opened = withContext(Dispatchers.IO) {
                try { Result.success(ComicDocument.open(file)) }
                catch (e: ComicException) { Result.failure(e) }
            }
            val doc = opened.getOrElse { e ->
                showMessage(e.message ?: getString(android.R.string.dialog_alert_title)); finish(); return@launch
            }
            document = doc
            titleView.text = doc.metadata.title
            pageCount = doc.spineSize
            bookPath = file.path
            val stored = withContext(Dispatchers.IO) { dao.getByPath(file.path) }
            rtl = stored?.rightToLeftOverride ?: doc.readingDirectionRtl
            updateDirectionLabel()
            val start = (stored?.spineIndex ?: 0).coerceIn(0, pageCount - 1)
            showPage(start)
            loadBookmarks()
            opening = false
        }
    }

    private fun loadBookmarks() {
        lifecycleScope.launch {
            bookmarks = withContext(Dispatchers.IO) { bookmarkDao.bookmarksFor(bookPath) }
            updateBookmarkLabel()
        }
    }

    /**
     * Adds a bookmark for the current page, or removes the one already on it. Mirrors
     * BookmarksPanel.toggleCurrentPage(): [BookmarkEntity.bookPath] is a CASCADE foreign key to
     * `books.path` with enforcement ON, but LibraryActivity's background [LibraryIndexer] sync
     * runs on a scope cancelled at ON_DESTROY, not ON_STOP, so it can keep running — and delete
     * this book's `books` row via `deleteByPaths` — while this reader is foregrounded. The
     * library-membership check comes first so a lost book degrades to a message instead of an
     * FK violation; the try/catch is a backstop for the race where the sync deletes the row
     * between that check and this write. On the guard path the in-memory [bookmarks] list — and
     * so the toggle label — is refreshed too, so a book lost mid-session doesn't keep showing a
     * bookmark that the cascade already deleted underneath it.
     */
    private fun toggleBookmark() {
        val existing = bookmarks.firstOrNull { it.spineIndex == currentPage }
        val fraction = if (pageCount > 0) (currentPage + 1).toFloat() / pageCount else 0f
        lifecycleScope.launch {
            val inLibrary = withContext(Dispatchers.IO) { dao.getByPath(bookPath) != null }
            if (!inLibrary) {
                bookmarks = withContext(Dispatchers.IO) { bookmarkDao.bookmarksFor(bookPath) }
                updateBookmarkLabel()
                showMessage(getString(R.string.error_book_not_indexed))
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    if (existing != null) bookmarkDao.deleteById(existing.id)
                    else bookmarkDao.insert(BookmarkEntity(
                        bookPath = bookPath, spineIndex = currentPage, charOffset = 0,
                        progressFraction = fraction, createdAtMs = System.currentTimeMillis(),
                    ))
                }
                bookmarks = withContext(Dispatchers.IO) { bookmarkDao.bookmarksFor(bookPath) }
                updateBookmarkLabel()
            } catch (e: CancellationException) {
                // The Activity was destroyed mid-write: let structured-concurrency cancellation
                // propagate rather than swallowing it into a toast on a dying screen.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, getString(R.string.error_save_bookmark), e)
                showMessage(getString(R.string.error_save_bookmark))
            }
        }
    }

    private fun updateBookmarkLabel() {
        // The bookmark control is a glyph (comic_bookmark_button), not text, so the add/remove
        // state lives in its content description for accessibility rather than in visible text.
        val bookmarked = bookmarks.any { it.spineIndex == currentPage }
        bookmarkButton.contentDescription =
            getString(if (bookmarked) R.string.bookmark_remove else R.string.bookmark_add)
    }

    private fun onTap(zone: TapZone) {
        if (overlay.visibility == View.VISIBLE) { toggleChrome(); return }
        when (zone) {
            TapZone.TOGGLE_OVERLAY -> toggleChrome()
            else -> {
                val forward = if (rtl) zone == TapZone.PREVIOUS else zone == TapZone.NEXT
                val target = if (forward) currentPage + 1 else currentPage - 1
                if (target in 0 until pageCount) showPage(target)
            }
        }
    }

    private fun showPage(index: Int) {
        val doc = document ?: return
        lifecycleScope.launch {
            val pre = prefetch
            prefetch = null
            val bmp = if (pre != null && pre.first == index) {
                pre.second
            } else {
                pre?.second?.recycle()
                decoder.decode({ doc.openPage(index) }, reqWidth(), reqHeight())
            }
            val old = currentBitmap
            currentBitmap = bmp
            pageView.show(bmp)
            pageView.fullRefresh()
            if (old != null && old !== bmp) old.recycle()
            currentPage = index
            pagesShownForTest += index
            updateReadout()
            updateBookmarkLabel()
            persist(index)
            prefetchNeighbor()
        }
    }

    private fun prefetchNeighbor() {
        val doc = document ?: return
        val next = if (rtl) currentPage - 1 else currentPage + 1
        if (next !in 0 until pageCount) return
        lifecycleScope.launch {
            val bmp = decoder.decode({ doc.openPage(next) }, reqWidth(), reqHeight()) ?: return@launch
            if (prefetch?.first != next) { prefetch?.second?.recycle(); prefetch = next to bmp }
            else bmp.recycle()
        }
    }

    private fun persist(index: Int) {
        val fraction = if (pageCount > 0) (index + 1).toFloat() / pageCount else 0f
        app.positionWriteScope.launch {
            dao.updatePosition(bookPath, index, 0, fraction, System.currentTimeMillis())
        }
    }

    private fun toggleDirection() {
        rtl = !rtl
        app.positionWriteScope.launch { dao.updateRtlOverride(bookPath, rtl) }
        prefetch?.second?.recycle(); prefetch = null
        prefetchNeighbor()
        updateDirectionLabel()
    }

    private fun updateDirectionLabel() {
        directionButton.text = getString(if (rtl) R.string.comic_dir_rtl else R.string.comic_dir_ltr)
    }

    private fun toggleChrome() {
        if (overlay.visibility == View.VISIBLE) {
            overlay.visibility = View.GONE
            pageView.epd.exitFastMode()
        } else {
            pageView.epd.enterFastMode()
            overlay.visibility = View.VISIBLE
        }
    }

    private fun updateReadout() {
        readout.text = getString(R.string.comic_page_readout, currentPage + 1, pageCount)
    }

    private fun reqWidth() = pageView.width.takeIf { it > 0 } ?: 1404
    private fun reqHeight() = pageView.height.takeIf { it > 0 } ?: 1872

    private fun finishMissing() { showMessage(getString(R.string.error_book_missing)); finish() }
    private fun showMessage(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    override fun onStop() {
        if (bookPath.isNotEmpty()) persist(currentPage)
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        if (::pageView.isInitialized) pageView.epd.exitFastMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
        prefetch?.second?.recycle()
        document?.close()
    }

    companion object {
        private const val TAG = "ComicActivity"
    }
}
