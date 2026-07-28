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
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Maps a drag fraction in `[0,1]` to a 0-based page index for a [pageCount]-page comic — the
 * scrubber's whole-book position resolved to the page it names. Pure — JVM-tested; the testable
 * seam behind [ComicActivity.onScrubMoved]/[ComicActivity.onScrubCommitted].
 *
 * Chosen as the inverse of [comicProgressForPage] (`(index+1)/pageCount`), so a page shown at
 * progress P and a drag that lands back on P resolve to the SAME page — `roundToInt` on
 * `fraction * pageCount` undoes the `+1`/`pageCount` exactly at the fraction [comicProgressForPage]
 * itself produces, and degrades gracefully (clamped, never a divide-by-zero) off that grid.
 */
internal fun comicPageForFraction(fraction: Float, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    val raw = (fraction.coerceIn(0f, 1f) * pageCount).roundToInt() - 1
    return raw.coerceIn(0, pageCount - 1)
}

/**
 * The whole-book fraction for 0-based [pageIndex] of [pageCount] pages — the same `(index+1)/count`
 * formula already used for a bookmark's stored `progressFraction` and for [ComicActivity.persist]'s
 * library percentage, reused here so the scrubber thumb, a freshly bookmarked page and the stored
 * position all agree on where a page sits on the track. Pure — JVM-tested.
 */
internal fun comicProgressForPage(pageIndex: Int, pageCount: Int): Float {
    if (pageCount <= 0) return 0f
    return (pageIndex + 1).toFloat() / pageCount
}

/** Image-based comic reader. Portrait-locked (manifest). Reuses the text reader's tap zones, EPD
 *  refresher and page-turn-driven prefetch, but draws bitmaps, not text. */
open class ComicActivity : AppCompatActivity() {

    private lateinit var pageView: ComicPageView
    private lateinit var overlay: View
    private lateinit var titleView: TextView
    private lateinit var readout: TextView
    private lateinit var directionButton: TextView

    /** "Mark this page" / "Remove this mark", at the head of the marks surface. A cell now, not a
     *  toolbar glyph — so it can state which page it is about to act on. */
    private lateinit var bookmarkButton: TextView
    private lateinit var bookmarkSubject: TextView

    /** The bookmarks panel view (`comic_bookmarks_panel`): this Activity owns only its visibility —
     *  everything else (the list, the delete write, the empty state) belongs to [bookmarksPanel] —
     *  mirrors ReaderActivity's own `bookmarksPanel: View` / `bookmarks: BookmarksPanel` split. */
    private lateinit var bookmarksPanelView: View
    private lateinit var bookmarksPanel: ComicBookmarksPanel
    private val decoder = ComicPageDecoder()

    /**
     * The timeline beneath the readout — reused UNCHANGED from the EPUB reader (see the comic
     * chrome parity design doc): the trusted-lift gesture grammar it carries took several rounds of
     * on-device debugging against panel firmware that fabricates phantom lifts, and reimplementing
     * it for comics would reintroduce every misfire it fixed. Wired with an empty chapter list
     * ([ChapterScrubberView.setBook]'s `chapterStartFractions`) — comics have no chapters, and 358
     * page ticks would smear anyway — and [ChapterScrubberView.setGenerationStateVisible] `false`,
     * since a comic page has no generation phase to show pending-dots for.
     */
    private lateinit var chapterScrubber: ChapterScrubberView

    /** The ↩ control beside [chapterScrubber]: pops [jumpStack]. GONE whenever the stack is empty —
     *  see [updateBackControl]. */
    private lateinit var scrubberBackView: TextView

    /** The floating page-preview window over [chapterScrubber]: the on-demand decode of the page
     *  nearest the finger, blitted during a drag via [previewLoader]. GONE at rest — see
     *  [onScrubMoved]/[hidePreview]. Never the book page itself; [pageView] never repaints mid-drag. */
    private lateinit var scrubPreview: ImageView

    /** On-demand comic-page previews for [scrubPreview] (see its KDoc for the caller contract that
     *  makes overlapping decodes for the same page impossible). */
    private val previewLoader = ComicPreviewLoader(decoder)

    /** The one in-flight preview decode, cancelled by the next hover so a fast sweep never queues a
     *  backlog of decodes for pages the finger has already passed — mirrors ReaderActivity's own
     *  `previewDecodeJob`. This cancel-before-relaunch discipline is also what keeps
     *  [ComicPreviewLoader.preview] safe to call: see its KDoc. */
    private var previewDecodeJob: Job? = null

    /** The page [previewDecodeJob] currently targets, or the page already shown in [scrubPreview] —
     *  lets a same-page re-hover skip a redundant decode, and lets a superseded decode's resumed
     *  callback recognise it is stale and skip painting. */
    private var previewTargetPage: Int? = null

    /** Where the current scrub began, or null when no scrub is in flight — the ↩ push target on a
     *  commit that moves the page, and what [abandonScrub] restores the readout/thumb to. Mirrors
     *  ReaderActivity's `scrubOrigin`. */
    private var scrubOrigin: Int? = null

    /** The jump back-stack: a scrub commit that actually turns the page pushes the page being left;
     *  [onBackJump] pops. In-memory, per book-open — mirrors ReaderActivity's `jumpStack`. */
    private val jumpStack = JumpStack<Int>()

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
    internal fun toggleChromeForTest() = toggleChrome()
    internal fun onScrubStartForTest() = onScrubStart()
    internal fun onScrubMoveForTest(fraction: Float) = onScrubMoved(fraction)
    internal fun onScrubCommitForTest(fraction: Float) = onScrubCommitted(fraction)
    internal fun abandonScrubForTest() = abandonScrub()
    internal fun backJumpForTest() = onBackJump()
    internal val previewDecodeJobForTest: Job? get() = previewDecodeJob

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
        // The mark control moved off the toolbar and into the marks surface, where it is a
        // sentence naming the page it would act on rather than a pictogram that could not.
        bookmarkButton = overlay.findViewById(R.id.comic_bookmark_toggle)
        bookmarkSubject = overlay.findViewById(R.id.comic_bookmark_subject)
        overlay.findViewById<TextView>(R.id.comic_bookmarks_book).text = title
        overlay.findViewById<SideheadView>(R.id.comic_marks_sidehead).apply {
            label = getString(R.string.marks_sidehead)
            form = SideheadView.Form.RULED
        }

        chapterScrubber = overlay.findViewById(R.id.comic_scrubber)
        chapterScrubber.setGenerationStateVisible(false)
        scrubberBackView = overlay.findViewById(R.id.comic_scrubber_back)
        scrubberBackView.setOnClickListener { onBackJump() }
        scrubPreview = overlay.findViewById(R.id.comic_preview)
        // Mirrors ReaderActivity's scrubPreview tap: the explicit "go there" for a scrub the
        // trusted-lift grammar left ARMED (a light drag's lift the panel fabricated, not obeyed).
        scrubPreview.setOnClickListener { chapterScrubber.commitArmed() }
        chapterScrubber.onScrubStart = { onScrubStart() }
        chapterScrubber.onScrubMove = { fraction, _ -> onScrubMoved(fraction) }
        chapterScrubber.onScrubCommit = { fraction, _ -> onScrubCommitted(fraction) }
        chapterScrubber.onScrubCancel = { abandonScrub() }

        overlay.findViewById<View>(R.id.comic_back).setOnClickListener { finish() }
        directionButton.setOnClickListener { toggleDirection() }
        bookmarkButton.setOnClickListener { toggleBookmark() }
        overlay.findViewById<View>(R.id.comic_bookmarks_button).setOnClickListener {
            bookmarksPanel.show(bookPath, bookmarks); bookmarksPanelView.visibility = View.VISIBLE
        }

        bookmarksPanelView = overlay.findViewById(R.id.comic_bookmarks_panel)
        bookmarksPanel = ComicBookmarksPanel(
            overlay, lifecycleScope, bookmarkDao,
            onJump = { page ->
                bookmarksPanelView.visibility = View.GONE
                if (page in 0 until pageCount) {
                    // Only a jump that actually moves the reader pushes — mirrors jumpToAnchor and
                    // a scrub commit: tapping the bookmark already on the current page is a no-op,
                    // not a jump, and should arm no ↩.
                    if (page != currentPage) {
                        jumpStack.push(currentPage)
                        updateBackControl()
                    }
                    showPage(page)
                }
            },
            onDeleted = { marks ->
                bookmarks = marks
                updateBookmarkLabel()
                refreshScrubberBookmarks()
            },
            onDeleteFailed = { e ->
                Log.w(TAG, getString(R.string.error_save_bookmark), e)
                showMessage(getString(R.string.error_save_bookmark))
            },
        )
        // The device has no hardware Back, so the panel carries its own top-right ✕ that peels it
        // back to the reading chrome — the same first step system Back takes, and the fix for the
        // panel's known defect (an empty panel with no in-panel dismiss forced exiting the reader).
        overlay.findViewById<View>(R.id.comic_bookmarks_close).setOnClickListener {
            bookmarksPanelView.visibility = View.GONE
        }
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
            refreshScrubberBookmarks()
        }
    }

    /** Pushes the current [bookmarks] onto [chapterScrubber] as glyph fractions — mirrors
     *  ReaderActivity's `refreshScrubberBookmarks`. Called whenever [bookmarks] changes. */
    private fun refreshScrubberBookmarks() {
        chapterScrubber.setBookmarks(bookmarks.map { it.progressFraction })
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
        val fraction = comicProgressForPage(currentPage, pageCount)
        lifecycleScope.launch {
            val inLibrary = withContext(Dispatchers.IO) { dao.getByPath(bookPath) != null }
            if (!inLibrary) {
                bookmarks = withContext(Dispatchers.IO) { bookmarkDao.bookmarksFor(bookPath) }
                updateBookmarkLabel()
                refreshScrubberBookmarks()
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
                refreshScrubberBookmarks()
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
        // A cell that says what it will do, and a line under it naming the page it will do it to.
        // The old glyph could carry neither, so the add/remove state lived in a content
        // description no sighted reader ever saw.
        val bookmarked = bookmarks.any { it.spineIndex == currentPage }
        bookmarkButton.setText(if (bookmarked) R.string.bookmark_remove else R.string.bookmark_add)
        bookmarkSubject.text = getString(R.string.comic_page_readout, currentPage + 1, pageCount)
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

    /**
     * Draws page [index] and returns the launched [Job] — the caller's only way to know when the
     * decode has actually landed on screen. [onScrubCommitted] awaits it before taking the floating
     * preview down, so the preview bridges the ~decode-latency gap instead of leaving the OLD page
     * on screen with no feedback the instant the finger lifts.
     */
    private fun showPage(index: Int): Job? {
        val doc = document ?: return null
        return lifecycleScope.launch {
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
            // Ticks (none, for comics) and thumb follow the page, so opening the chrome always shows
            // the true position. scrubOrigin is always null by the time showPage runs — a commit
            // clears it before calling showPage, and no other caller ever has a drag in flight — so
            // there is no "mid-drag" case here to guard against, unlike ReaderActivity's own
            // showPage, which can run its own re-entrant scrub-commit path.
            chapterScrubber.setBook(emptyList(), comicProgressForPage(index, pageCount))
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
        val fraction = comicProgressForPage(index, pageCount)
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

    /**
     * Toggles the chrome. Mirrors ReaderActivity's showOverlay/hideOverlay pair: fast e-ink mode
     * runs only while the chrome is up, and closing it resolves any scrub the trusted-lift grammar
     * left open before the one clean refresh that clears whatever ghosting accumulated in fast mode.
     */
    private fun toggleChrome() {
        if (overlay.visibility == View.VISIBLE) {
            // A lift still inside its commit grace window when the chrome closes is resolved
            // synchronously first, exactly as ReaderActivity.hideOverlay does — a trusted lift
            // commits, an untrusted one arms, and abandonScrub below discards it quietly.
            chapterScrubber.flushPendingCommit()
            abandonScrub()
            overlay.visibility = View.GONE
            pageView.epd.exitFastMode()
            // One clean refresh on the way out, so the page the reader returns to is crisp rather
            // than carrying whatever ghosting fast mode accumulated while the chrome was up.
            pageView.fullRefresh()
        } else {
            pageView.epd.enterFastMode()
            overlay.visibility = View.VISIBLE
            updateBackControl()
        }
    }

    /** ACTION_DOWN: cancel any preview decode left over from a prior hover before this drag starts,
     *  and remember the page being left — the ↩ push target on commit, and abandonScrub's return
     *  point on cancel. */
    private fun onScrubStart() {
        previewDecodeJob?.cancel()
        previewTargetPage = null
        scrubOrigin = currentPage
    }

    /**
     * A drag position: update the readout and the floating preview only. Deliberately NO decode of
     * the PAGE ITSELF and no call to [showPage] — the page does not repaint until the finger lifts
     * (see [onScrubCommitted]), the e-ink invariant a drag must never violate.
     *
     * [chapterScrubber] is wired with an empty chapter list, so its `onScrubMove`/`onScrubCommit`
     * callbacks' snapped-chapter parameter is always null for comics — accepted by the lambdas in
     * `onCreate` and dropped there, rather than narrowing this function's own signature.
     */
    private fun onScrubMoved(fraction: Float) {
        if (pageCount <= 0) return
        // Bail before touching any state: a null document leaves previewTargetPage/previewDecodeJob
        // exactly as they were, rather than recording a target for a decode that never launches
        // (which would dedup-away a later legitimate hover on that same page) or leaving a
        // just-cancelled job sitting in previewDecodeJob.
        val doc = document ?: return
        val page = comicPageForFraction(fraction, pageCount)
        val percent = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
        readout.text = getString(R.string.comic_page_readout_drag, page + 1, pageCount, percent)

        // The finger dithered within the same page's fraction span: the decode already dispatched
        // (or already shown) for it is still correct — nothing to cancel or relaunch.
        if (page == previewTargetPage) return
        previewTargetPage = page

        // Cancel the previous hover's decode BEFORE dispatching this one. This is the one thing
        // that keeps ComicPreviewLoader safe to call at all (see its KDoc): withContext's prompt
        // cancellation guarantee means a job cancelled here discards its decode result at its next
        // resumption point, before it can ever reach ComicPreviewLoader.preview's own cache.put —
        // so a superseded hover's bitmap is never cached, and two preview() calls for the same page
        // can never overlap in the cache, as long as every hover past the first cancels its
        // predecessor unconditionally, before decoding, exactly as this does.
        previewDecodeJob?.cancel()
        previewDecodeJob = lifecycleScope.launch {
            val bmp = try {
                previewLoader.preview(page) { doc.openPage(page) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "comic preview decode failed", e)
                null
            }
            // A newer hover may have superseded this decode while it was in flight; only paint if
            // this is still the page the finger is on.
            if (previewTargetPage != page) return@launch
            if (bmp != null) {
                scrubPreview.setImageBitmap(bmp)
                scrubPreview.visibility = View.VISIBLE
            } else {
                hidePreview()
            }
        }
    }

    /**
     * Lift-off: the only path that turns the page from a scrub. Simpler than ReaderActivity's own
     * commit — a comic page IS the unit shown, so there is no chapter to resolve or paginate first,
     * just [comicPageForFraction] and a [showPage] call.
     */
    private fun onScrubCommitted(fraction: Float) {
        previewDecodeJob?.cancel()
        previewDecodeJob = null
        previewTargetPage = null
        val origin = scrubOrigin
        scrubOrigin = null
        if (origin == null || pageCount <= 0) { hidePreview(); return }
        val target = comicPageForFraction(fraction, pageCount)
        if (target != origin) {
            jumpStack.push(origin)
            updateBackControl()
            // The preview window deliberately stays up through the commit — showPage's decode is
            // not instant, and hiding the preview at lift-off would leave the OLD page on screen
            // with no feedback for that gap. It comes down once the new page has actually landed.
            val turned = showPage(target)
            lifecycleScope.launch { turned?.join(); hidePreview() }
        } else {
            // A no-op commit: the drag ended on the page already showing. showPage never runs, so
            // the thumb (moved all over during the drag) is snapped back to the truth instead of
            // lying about where the reader is.
            chapterScrubber.setProgress(comicProgressForPage(currentPage, pageCount))
            updateReadout()
            hidePreview()
        }
    }

    /**
     * Returns to where the scrub began and writes nothing — called when the chrome is dismissed or
     * a gesture is cancelled with a scrub in flight. The origin page never left the screen during
     * the drag, so there is nothing to repaint; only the readout and the thumb need restoring.
     */
    private fun abandonScrub() {
        if (scrubOrigin == null) return
        previewDecodeJob?.cancel()
        previewDecodeJob = null
        previewTargetPage = null
        scrubOrigin = null
        hidePreview()
        updateReadout()
        // The view may still be holding an ARMED session (or an open grace window) — reset it
        // BEFORE moving the thumb, or the next touch would "resume" a session whose origin this
        // method just cleared.
        chapterScrubber.resetSession()
        chapterScrubber.setProgress(comicProgressForPage(currentPage, pageCount))
    }

    private fun hidePreview() {
        scrubPreview.setImageDrawable(null)
        scrubPreview.visibility = View.GONE
    }

    /** Pops one jump and turns to it — like a scrub commit, not a fresh navigation. Does NOT push:
     *  ↩ is one-way. */
    private fun onBackJump() {
        val target = jumpStack.pop() ?: return
        if (target in 0 until pageCount) showPage(target)
        updateBackControl()
    }

    private fun updateBackControl() {
        scrubberBackView.visibility = if (jumpStack.isEmpty) View.GONE else View.VISIBLE
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

    /**
     * Mirrors [ReaderActivity.onResume]: onPause gives the panel's screen mode back
     * unconditionally (it is device-wide state that must never leak), so a resume with the
     * overlay still open would otherwise run every subsequent chrome interaction on the slow,
     * full-quality waveform until the overlay was closed and reopened. Idempotent: enterFastMode
     * no-ops when already held. Guarded the same way [onPause] is: onResume can in principle race
     * initialization before pageView/overlay are assigned.
     */
    override fun onResume() {
        super.onResume()
        if (::pageView.isInitialized && ::overlay.isInitialized && overlay.visibility == View.VISIBLE) {
            pageView.epd.enterFastMode()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::pageView.isInitialized) pageView.epd.exitFastMode()
        // Mirrors ReaderActivity.onPause: a lift still inside its commit grace window when the
        // app is backgrounded (Home, app switcher, an incoming call) is a committed navigation —
        // flush it now rather than let the process pause with it unresolved. Otherwise the
        // grace-window timer never fires and the page turn the user already lifted their finger
        // on is silently discarded, the reverse of the "lift-off is a commitment" contract.
        if (::chapterScrubber.isInitialized) chapterScrubber.flushPendingCommit()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewDecodeJob?.cancel()
        // Clear the ImageView's reference to whatever bitmap it holds BEFORE previewLoader.clear()
        // recycles it — clear() recycles every cached bitmap unconditionally, including the one
        // last handed to comic_preview, and an ImageView still holding a recycled Bitmap crashes
        // ("trying to use a recycled bitmap") the next time it is asked to draw.
        if (::scrubPreview.isInitialized) scrubPreview.setImageDrawable(null)
        previewLoader.clear()
        currentBitmap?.recycle()
        prefetch?.second?.recycle()
        document?.close()
    }

    companion object {
        private const val TAG = "ComicActivity"
    }
}
