package dev.reader.ui

import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R
import dev.reader.ReaderApplication
import dev.reader.data.BookmarkEntity
import dev.reader.data.HighlightEntity
import dev.reader.engine.ExistingHighlight
import dev.reader.engine.Locator
import dev.reader.engine.PageNavigator
import dev.reader.engine.ReadingState
import dev.reader.engine.RenderConfig
import dev.reader.engine.TocEntry
import dev.reader.engine.advance
import dev.reader.engine.bookProgress
import dev.reader.engine.highlightContaining
import dev.reader.engine.mergeHighlights
import dev.reader.engine.pageIndexFor
import dev.reader.engine.reflowedPageIndex
import dev.reader.engine.retreat
import dev.reader.engine.snapToWords
import dev.reader.formats.epub.EpubDocument
import dev.reader.formats.epub.EpubException
import dev.reader.formats.epub.PaginatedChapter
import dev.reader.formats.render.AndroidMeasuredChapter
import dev.reader.formats.render.AndroidTextMeasurer
import dev.reader.formats.render.SpannedChapterBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The overlay's read-only page readout, chapter-relative: `page X of Y · N left in chapter`, where
 * X is 1-based ([pageIndex] + 1), Y is [pageCount], and N is the pages remaining after this one.
 * A pure function of its two ints — the testable seam behind the scrubber, keeping the string out
 * of the View. [pageIndex] is 0-based and expected in `0 until pageCount`.
 */
internal fun scrubberText(pageIndex: Int, pageCount: Int): String {
    val page = pageIndex + 1
    val left = pageCount - page
    return "page $page of $pageCount · $left left in chapter"
}

/**
 * One row of the Contents panel — the display projection of a [TocEntry] for [TocAdapter]. [depth]
 * drives the indent; [isCurrent] (whether this entry's chapter is the one being read) drives the
 * bold marker; [spineIndex]/[charOffset] are what a tap jumps to. Kept as a flat data class, out of
 * the View, so the list-building rules below are unit-testable without a RecyclerView.
 */
internal data class TocRow(
    val title: String,
    val spineIndex: Int,
    val charOffset: Int,
    val depth: Int,
    val isCurrent: Boolean,
)

/**
 * Projects the parsed [toc] into [TocRow]s in list (spine) order, marking every entry whose chapter
 * is [currentSpineIndex] as the current one. A pure function of its two arguments — the testable
 * seam behind the Contents list — so order, depth passthrough and current-chapter marking are
 * verifiable without an Activity. An empty [toc] yields an empty list (the "No contents" case).
 */
internal fun tocRows(toc: List<TocEntry>, currentSpineIndex: Int): List<TocRow> =
    toc.map { entry ->
        TocRow(
            title = entry.title,
            spineIndex = entry.spineIndex,
            charOffset = entry.charOffset,
            depth = entry.depth,
            isCurrent = entry.spineIndex == currentSpineIndex,
        )
    }

/**
 * Resolves the [ReadingState] a Contents tap lands on — the same shape as the open path's
 * `ReadingSession.resolveStart`, factored out pure so the anchored-offset and degrade-on-empty
 * rules are testable without a real document.
 *
 * A well-formed entry ([pageCountFor] > 0) lands on the page whose range contains [charOffset] via
 * [offsetToPageIndex] (which the caller backs with [pageIndexFor]), so an anchored entry lands on
 * its offset, NOT blindly on page 0. An entry pointing at a missing/empty chapter (zero pages)
 * degrades: it skips forward to the nearest readable chapter via [firstNonEmptyFrom] (the open
 * path's `advance` empty-skip), returning `null` only if nothing readable remains.
 */
internal fun tocTarget(
    spineIndex: Int,
    charOffset: Int,
    pageCountFor: (Int) -> Int,
    offsetToPageIndex: (Int, Int) -> Int,
    firstNonEmptyFrom: (Int) -> ReadingState?,
): ReadingState? =
    if (pageCountFor(spineIndex) == 0) {
        firstNonEmptyFrom(spineIndex)
    } else {
        ReadingState(spineIndex, offsetToPageIndex(spineIndex, charOffset))
    }

/**
 * Opens one EPUB and turns its pages.
 *
 * Normally launched from [LibraryActivity] with [EXTRA_BOOK_PATH] set to the tapped book's path.
 * Still works standalone (e.g. `adb shell am start -n dev.reader/.ui.ReaderActivity`, as Plan 2
 * Task 1's device measurement used) — with no extra, it falls back to [findFirstEpub], exactly
 * its pre-library behavior.
 *
 * All calls into [EpubDocument.chapter] happen from this Activity's UI thread (either directly
 * from a lifecycleScope coroutine resumed on Dispatchers.Main, or from [PageView]'s tap
 * callback, which View always delivers on the main thread) because `chapter()`'s cache is
 * documented as not thread-safe. Only opening the document (pure I/O, no cache involved yet)
 * runs on Dispatchers.IO.
 *
 * `open`, not `final`: [ReaderActivityTest]'s Robolectric coverage substitutes
 * [isAllFilesAccessGranted], [openDocument], and [findFirstEpub] via a test subclass — the three
 * points where this class reaches out to real device permissions, real multi-second EPUB opens,
 * and a real /Document tree, none of which a JVM test can exercise meaningfully. The same seam
 * pattern as [LibraryActivity]; no other member is `open`.
 */
open class ReaderActivity : AppCompatActivity() {

    private lateinit var pageView: PageView

    /**
     * The reading chrome, drawn above [pageView] in the content [FrameLayout] and toggled by the
     * center tap. It holds no timer, observer or animation: showing and hiding is a single
     * `visibility` flip (one e-ink redraw), so an open OR closed overlay costs nothing at rest.
     */
    private lateinit var overlay: View
    private lateinit var titleView: TextView
    private lateinit var scrubberView: TextView

    /**
     * The Aa typography sheet — a visibility-toggled panel inside [overlay], opened by the Aa button.
     * Holds no timer or animation: showing/hiding is one `visibility` flip. Each of its controls
     * writes a [ReaderPrefs] field then live-re-paginates the current chapter via
     * [applySettingsChange], keeping the reader on the same text across the reflow.
     */
    private lateinit var settingsSheet: View

    /**
     * The Contents panel — a visibility-toggled, full-height list inside [overlay], opened by the
     * Contents button. Like [settingsSheet] it holds no timer or animation: showing/hiding is one
     * `visibility` flip. Its list is [tocList] (backed by [tocAdapter]); [tocEmpty] takes its place
     * when the book has no usable TOC. Tapping an entry jumps via [jumpToToc].
     */
    private lateinit var tocPanel: View
    private lateinit var tocList: RecyclerView
    private lateinit var tocEmpty: View
    private lateinit var tocAdapter: TocAdapter

    /**
     * The Bookmarks panel — a visibility-toggled, full-height layer inside [overlay], opened by the
     * Bookmarks ("Marks") button. Same shape as [tocPanel]: one `visibility` flip, no timer or
     * animation. [bookmarkToggle] reads "Bookmark this page" / "Remove bookmark from this page"
     * from a fresh, one-shot [refreshBookmarks] read — never a standing observer, so an idle reader
     * with the panel closed (or even open) costs nothing. [bookmarksList] is backed by
     * [bookmarksAdapter]; [bookmarksEmpty] takes its place when the book has no bookmarks yet.
     */
    private lateinit var bookmarksPanel: View
    private lateinit var bookmarksList: RecyclerView
    private lateinit var bookmarksAdapter: BookmarkAdapter
    private lateinit var bookmarksEmpty: TextView
    private lateinit var bookmarkToggle: TextView

    /**
     * The Highlights panel — a visibility-toggled, full-height layer inside [overlay], opened by the
     * Highlights button. Same shape as [bookmarksPanel]: one `visibility` flip, no timer or animation.
     * Unlike the bookmarks panel it has NO add/remove toggle — highlighting happens on the page with
     * the pen (see [onStylusTap]/[onStylusDrag]/[commitHighlight]). [highlightsList] is backed by
     * [highlightsAdapter]; [highlightsEmpty] takes its place when the book has no highlights yet. Its
     * list is loaded once per open by [refreshHighlights] — never a standing observer.
     */
    private lateinit var highlightsPanel: View
    private lateinit var highlightsList: RecyclerView
    private lateinit var highlightsAdapter: HighlightAdapter
    private lateinit var highlightsEmpty: TextView

    private var document: EpubDocument? = null
    private var navigator: PageNavigator? = null
    private var state = ReadingState(0, 0)
    private var config: RenderConfig? = null

    /** Byte weight per spine chapter (see [EpubDocument.chapterWeights]), captured once at open so
     *  [bookProgress] can be recomputed cheaply on every page turn without touching the ZIP again. */
    private var chapterWeights: List<Long> = emptyList()

    /** Mirrors [ReaderPrefs.showProgressBar], read once at open and kept current by
     *  [toggleProgressBar] — so the hot [showPage] path never constructs [ReaderPrefs] itself. */
    private var showProgressBar: Boolean = true

    /** Whole-book progress `[0,1]` of the page [showPage] last drew — captured there (independently
     *  of [showProgressBar]) so [persistPosition] can store it for the library's percentage. */
    private var currentBookProgress: Float = 0f

    /** The current chapter's highlights, cached so page turns within a chapter never re-query. Keyed by
     *  [chapterHighlightsSpine]; reloaded only on a chapter change or after an edit. */
    private var chapterHighlights: List<HighlightEntity> = emptyList()
    private var chapterHighlightsSpine: Int = -1

    /** The armed bracket-start offset (transient UI state, not persisted); null when no bracket is armed. */
    private var bracketAnchorOffset: Int? = null

    /**
     * The one in-flight adjacent-chapter prefetch, if any (see [schedulePrefetch]). Held only so a
     * newer settle can cancel a now-superseded prefetch before launching the next — there is never
     * more than one, and it is one-shot: it paginates a single neighbour off the main thread and
     * completes. It costs nothing at rest (the idle promise): no timer, no polling, no re-arm — the
     * next prefetch is launched only by the user's next page turn. Lives on [lifecycleScope], so
     * leaving the book (onDestroy) cancels a prefetch still in flight.
     */
    private var prefetchJob: Job? = null

    /** Page turns since the last full-panel refresh; drives the [REFRESH_CADENCE] ghost-clear. */
    private var turnsSinceRefresh = 0

    /** Whether the Aa font options have been given their preview typefaces yet (loaded once, on
     * the first sheet-open — see [loadFontPreviewsOnce]). */
    private var fontPreviewsLoaded = false

    /** The pure position-memory logic: the restore rules and the in-memory page-turn debounce. */
    private val session = ReadingSession()

    /**
     * The absolute path of the open book, i.e. the `books` row key that position writes target. Set
     * once the document opens; null before that and for the (impossible-in-practice) case where no
     * book opened. [onStop] and [persistPosition] both need it after the opening coroutine is gone.
     */
    private var bookPath: String? = null

    /**
     * Guards against opening the book twice — set synchronously, before any coroutine starts.
     *
     * [openFirstBook] is armed from both [onCreate] and [onResume]. On a cold start with the
     * permission already granted, onCreate → onStart → onResume all run *before* the first layout
     * pass, so both `doOnLayout` calls take the deferred path, register two separate
     * OnLayoutChangeListeners, and both fire on that one layout. A `document != null` check cannot
     * stop that: `document` is assigned asynchronously, and the first invocation suspends at
     * `withContext(Dispatchers.IO)` long before assigning anything — so the second invocation sees
     * `null` and opens the book again. That means two EpubDocument.open calls, two ZipFile handles
     * and chapter 0 paginated twice, with the loser silently overwritten and never closed.
     *
     * Being a plain field written only on the main thread, this flag is set before the `launch`
     * even has a chance to suspend, which is exactly what makes it airtight here.
     */
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageView = PageView(this)

        // Wrap the page in a container so the overlay can draw ABOVE it. The overlay is added after
        // pageView, so it sits on top; it is not clickable itself, so page-area taps fall through to
        // pageView (which dismisses the overlay) while its Back control consumes its own tap.
        val container = FrameLayout(this)
        container.addView(pageView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        overlay = layoutInflater.inflate(R.layout.overlay_reader, container, false)
        container.addView(overlay)
        titleView = overlay.findViewById(R.id.book_title)
        scrubberView = overlay.findViewById(R.id.scrubber)
        settingsSheet = overlay.findViewById(R.id.settings_sheet)
        tocPanel = overlay.findViewById(R.id.toc_panel)
        tocList = overlay.findViewById(R.id.toc_list)
        tocEmpty = overlay.findViewById(R.id.toc_empty)
        tocAdapter = TocAdapter(::jumpToToc)
        tocList.layoutManager = LinearLayoutManager(this)
        tocList.itemAnimator = null // e-ink: a rebind is one redraw, never an animated shuffle.
        tocList.adapter = tocAdapter
        bookmarksPanel = overlay.findViewById(R.id.bookmarks_panel)
        bookmarksList = overlay.findViewById(R.id.bookmarks_list)
        bookmarksEmpty = overlay.findViewById(R.id.bookmarks_empty)
        bookmarkToggle = overlay.findViewById(R.id.bookmark_toggle)
        bookmarksAdapter = BookmarkAdapter(onJump = ::jumpToBookmark, onDelete = ::deleteBookmark)
        bookmarksList.layoutManager = LinearLayoutManager(this)
        bookmarksList.itemAnimator = null // e-ink: a rebind is one redraw, never an animated shuffle.
        bookmarksList.adapter = bookmarksAdapter
        highlightsPanel = overlay.findViewById(R.id.highlights_panel)
        highlightsList = overlay.findViewById(R.id.highlights_list)
        highlightsEmpty = overlay.findViewById(R.id.highlights_empty)
        highlightsAdapter = HighlightAdapter(onJump = ::jumpToHighlight, onDelete = ::deleteHighlightRow)
        highlightsList.layoutManager = LinearLayoutManager(this)
        highlightsList.itemAnimator = null // e-ink: a rebind is one redraw, never an animated shuffle.
        highlightsList.adapter = highlightsAdapter
        overlay.findViewById<View>(R.id.back).setOnClickListener { exitToLibrary() }
        overlay.findViewById<View>(R.id.highlights_button).setOnClickListener { toggleHighlights() }
        overlay.findViewById<View>(R.id.bookmarks_button).setOnClickListener { toggleBookmarks() }
        overlay.findViewById<View>(R.id.contents_button).setOnClickListener { toggleToc() }
        overlay.findViewById<View>(R.id.settings_button).setOnClickListener { toggleSettings() }
        bookmarkToggle.setOnClickListener { toggleCurrentPageBookmark() }
        pageView.onStylusTap = ::onStylusTap
        pageView.onStylusDrag = ::onStylusDrag
        wireSettingsControls()
        setContentView(container)

        // System Back is the reader's other way out (the Nomad's hardware/gesture Back does not
        // finish this Activity on its own). Additive — there is no onBackPressed override. Overlay
        // shown: Back only closes it. Overlay hidden: Back leaves the book, flushing position first.
        onBackPressedDispatcher.addCallback(this) {
            when {
                // The Bookmarks/TOC panels and the Aa sheet are layers inside the overlay: Back peels
                // whichever is open off first, then the overlay, then the book — one thing per press.
                // Only one panel is ever open at a time (opening any one closes the others), so the
                // order among them is a formality; Highlights is checked first as the topmost-drawn
                // layer (added last in the XML), then Bookmarks, then the TOC/Aa layers.
                highlightsPanel.visibility == View.VISIBLE -> highlightsPanel.visibility = View.GONE
                bookmarksPanel.visibility == View.VISIBLE -> bookmarksPanel.visibility = View.GONE
                tocPanel.visibility == View.VISIBLE -> tocPanel.visibility = View.GONE
                settingsSheet.visibility == View.VISIBLE -> settingsSheet.visibility = View.GONE
                isOverlayVisible() -> hideOverlay()
                else -> exitToLibrary()
            }
        }

        if (!isAllFilesAccessGranted()) {
            requestAllFilesAccess()
            return
        }
        pageView.doOnLayout { openFirstBook() }
    }

    /** Whether the reading chrome is currently on screen. */
    private fun isOverlayVisible(): Boolean = overlay.visibility == View.VISIBLE

    /** Reveals the reading chrome — one redraw, no animation. */
    private fun showOverlay() {
        overlay.visibility = View.VISIBLE
    }

    /** Dismisses the reading chrome — one redraw, no animation. Also closes the Aa sheet, the
     * Contents panel, and the Bookmarks panel, so the overlay always reopens to its bare bar rather
     * than a stale open panel. */
    private fun hideOverlay() {
        settingsSheet.visibility = View.GONE
        tocPanel.visibility = View.GONE
        bookmarksPanel.visibility = View.GONE
        highlightsPanel.visibility = View.GONE
        // Opening the chrome ends any on-page pen selection in progress: a pending bracket-start is
        // dropped (the marker would otherwise linger under the overlay).
        clearBracketAnchor()
        overlay.visibility = View.GONE
    }

    /** Opens or closes the Aa sheet — a visibility flip (one redraw). Opening first syncs its
     * controls to the current [ReaderPrefs] so it always shows the live values. */
    private fun toggleSettings() {
        if (settingsSheet.visibility == View.VISIBLE) {
            settingsSheet.visibility = View.GONE
        } else {
            tocPanel.visibility = View.GONE // one panel open at a time
            bookmarksPanel.visibility = View.GONE
            highlightsPanel.visibility = View.GONE
            loadFontPreviewsOnce() // before refreshSheet: sets each font option's preview face
            refreshSheet()
            settingsSheet.visibility = View.VISIBLE
        }
    }

    /** Shows each font option in its own face, so the picker previews the fonts before selection.
     * Deferred to first sheet-open and done once — loading three font families is real work that a
     * reader who never opens the Aa sheet should not pay at every book open. */
    private fun loadFontPreviewsOnce() {
        if (fontPreviewsLoaded) return
        fontPreviewsLoaded = true
        overlay.findViewById<TextView>(R.id.font_literata).typeface = ResourcesCompat.getFont(this, R.font.literata)
        overlay.findViewById<TextView>(R.id.font_bitter).typeface = ResourcesCompat.getFont(this, R.font.bitter)
        overlay.findViewById<TextView>(R.id.font_atkinson).typeface = ResourcesCompat.getFont(this, R.font.atkinson)
    }

    /** Opens or closes the Contents panel — a visibility flip (one redraw). Opening first rebuilds
     * the list from the current [EpubDocument.toc] and current chapter, and closes the Aa sheet so
     * only one panel is ever open. */
    private fun toggleToc() {
        if (tocPanel.visibility == View.VISIBLE) {
            tocPanel.visibility = View.GONE
        } else {
            settingsSheet.visibility = View.GONE // one panel open at a time
            bookmarksPanel.visibility = View.GONE
            highlightsPanel.visibility = View.GONE
            refreshToc()
            tocPanel.visibility = View.VISIBLE
        }
    }

    /** Opens/closes the Bookmarks panel — one panel open at a time (closes the Aa sheet and TOC). */
    private fun toggleBookmarks() {
        if (bookmarksPanel.visibility == View.VISIBLE) {
            bookmarksPanel.visibility = View.GONE
        } else {
            settingsSheet.visibility = View.GONE // one panel open at a time
            tocPanel.visibility = View.GONE
            highlightsPanel.visibility = View.GONE
            bookmarksPanel.visibility = View.VISIBLE
            refreshBookmarks()
        }
    }

    /** Rebuilds the Contents list from the already-parsed [EpubDocument.toc] and the current chapter
     * (for the bold marker). An empty/malformed TOC shows the "No contents" state with the list
     * hidden — never an empty clickable void. Pure View work; no re-parse (reads `doc.toc`). */
    private fun refreshToc() {
        val rows = tocRows(document?.toc.orEmpty(), state.spineIndex)
        tocAdapter.submit(rows)
        val empty = rows.isEmpty()
        tocEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        tocList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /**
     * Jumps the reader to a tapped Contents [row], through the SAME restore machinery the open path
     * and the Aa sheet use: resolve the target page via [tocTarget] (an anchored entry lands on the
     * page containing its char offset, NOT blindly page 0; an entry at a missing/empty chapter skips
     * forward to the nearest readable one), then [showPage] + [flushPosition] as a normal navigation,
     * and close the chrome down to the page.
     *
     * The lazy [EpubDocument.chapter] read can throw [EpubException] here for the first time, so this
     * is wrapped exactly as [onTap]/[applySettingsChange] are: a failure shows a message and leaves
     * the reader on the page it was already showing ([state] is only reassigned inside [showPage],
     * after its own `chapter()` call has succeeded).
     */
    private fun jumpToToc(row: TocRow) {
        val doc = document ?: return
        val cfg = config ?: return
        val nav = navigator ?: return
        val pageCountFor: (Int) -> Int = { doc.chapter(it, cfg).pages.size }
        try {
            val target = tocTarget(
                spineIndex = row.spineIndex,
                charOffset = row.charOffset,
                pageCountFor = pageCountFor,
                offsetToPageIndex = { spineIndex, charOffset ->
                    pageIndexFor(doc.chapter(spineIndex, cfg).pages, charOffset)
                },
                firstNonEmptyFrom = { from -> advance(nav, ReadingState(from, 0), pageCountFor) },
            )
            if (target == null) {
                // The tapped chapter and everything after it paginate to zero pages: nothing to show.
                showMessage("This book has no readable text.")
                return
            }
            hideOverlay()
            showPage(target)
            flushPosition()
        } catch (e: EpubException) {
            showMessage("Couldn't open that section: ${e.message}")
        } catch (e: Exception) {
            showMessage("Couldn't open that section: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun bookmarkDao() = (application as ReaderApplication).database.bookmarkDao()

    private fun bookDao() = (application as ReaderApplication).database.bookDao()

    /**
     * Loads this book's bookmarks (one-shot, off-main) and rebinds the panel: the list (chapter · %,
     * in reading order), the empty state, and the "Add/remove this page" toggle's label — set from
     * range-based [currentPageBookmark] detection so it stays correct after a re-paginate. No
     * standing observer: the panel re-reads each time it opens, and add/remove/delete call this again.
     */
    private fun refreshBookmarks() {
        val path = bookPath ?: return
        lifecycleScope.launch {
            val marks = withContext(Dispatchers.IO) { bookmarkDao().bookmarksFor(path) }
            val rows = bookmarkRows(marks, document?.toc.orEmpty())
            bookmarksAdapter.submit(rows)
            val empty = rows.isEmpty()
            bookmarksEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            bookmarksList.visibility = if (empty) View.GONE else View.VISIBLE

            val onThisPage = currentPageBookmarkForState(marks)
            bookmarkToggle.text = if (onThisPage != null) "Remove bookmark from this page" else "Bookmark this page"
        }
    }

    /** The bookmark on the page [showPage] last drew, if any (range-based). Null if the reader has no
     *  current chapter/page yet. */
    private fun currentPageBookmarkForState(marks: List<BookmarkEntity>): BookmarkEntity? {
        val doc = document ?: return null
        val cfg = config ?: return null
        val pages = doc.chapter(state.spineIndex, cfg).pages
        val page = pages.getOrNull(state.pageIndex) ?: return null
        return currentPageBookmark(marks, state.spineIndex, page)
    }

    /**
     * Adds a bookmark for the current page, or removes the one already on it — a pure data action
     * that never re-paginates or moves the reading position. Captures the page's top [Locator] and
     * the current [currentBookProgress] (independent of the display toggle). Reloads the panel after
     * the write so the list + toggle reflect it.
     *
     * Guarded by a library-membership check first: [BookmarkEntity.bookPath] is a foreign key to
     * `books.path` with FK enforcement ON, but the reader also opens books that have no `books` row
     * — a sideloaded EPUB launched directly, or one the library indexer hasn't reached yet. For such
     * a book, inserting a bookmark would violate the FK and throw `SQLiteConstraintException`.
     * Unlike [persistPosition] (an `UPDATE ... WHERE path` that harmlessly no-ops on 0 rows), an
     * INSERT has no such graceful fallback, so we check membership via [bookDao] before attempting
     * any write and bail out with a message instead — a book with no `books` row also can't have any
     * existing bookmarks, so there is nothing to remove either. The try/catch around the write itself
     * is a backstop for the race where a concurrent library sync deletes the `books` row between this
     * check and the write: surface the failure instead of letting it crash the coroutine.
     */
    private fun toggleCurrentPageBookmark() {
        val doc = document ?: return
        val cfg = config ?: return
        val path = bookPath ?: return
        val pages = doc.chapter(state.spineIndex, cfg).pages
        val page = pages.getOrNull(state.pageIndex) ?: return
        lifecycleScope.launch {
            val inLibrary = withContext(Dispatchers.IO) { bookDao().getByPath(path) != null }
            if (!inLibrary) {
                showMessage("This book isn't in your library yet.")
                return@launch
            }
            val existing = withContext(Dispatchers.IO) {
                currentPageBookmark(bookmarkDao().bookmarksFor(path), state.spineIndex, page)
            }
            try {
                withContext(Dispatchers.IO) {
                    if (existing != null) {
                        bookmarkDao().deleteById(existing.id)
                    } else {
                        bookmarkDao().insert(
                            BookmarkEntity(
                                bookPath = path,
                                spineIndex = state.spineIndex,
                                charOffset = page.startOffset,
                                progressFraction = currentBookProgress,
                                createdAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
                refreshBookmarks()
            } catch (e: CancellationException) {
                // The Activity was destroyed mid-write: let structured-concurrency cancellation
                // propagate rather than swallowing it into a "couldn't save" toast on a dying
                // screen — the same rule openFirstBook and the prefetch coroutine hold in this file.
                throw e
            } catch (e: Exception) {
                showMessage("Couldn't save that bookmark: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Deletes a bookmark from the list's ✕ and reloads the panel. */
    private fun deleteBookmark(row: BookmarkRow) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { bookmarkDao().deleteById(row.id) }
            refreshBookmarks()
        }
    }

    /**
     * Jumps to a tapped bookmark through the SAME restore machinery as [jumpToToc]: resolve the
     * target page via [tocTarget] (lands on the page containing the bookmark's char offset, re-
     * pagination-safe; skips forward if the chapter is empty), then [showPage] + [flushPosition] and
     * close the chrome. Wrapped like [jumpToToc] since the lazy [EpubDocument.chapter] can throw.
     */
    private fun jumpToBookmark(row: BookmarkRow) {
        val doc = document ?: return
        val cfg = config ?: return
        val nav = navigator ?: return
        val pageCountFor: (Int) -> Int = { doc.chapter(it, cfg).pages.size }
        try {
            val target = tocTarget(
                spineIndex = row.spineIndex,
                charOffset = row.charOffset,
                pageCountFor = pageCountFor,
                offsetToPageIndex = { spineIndex, charOffset ->
                    pageIndexFor(doc.chapter(spineIndex, cfg).pages, charOffset)
                },
                firstNonEmptyFrom = { from -> advance(nav, ReadingState(from, 0), pageCountFor) },
            )
            if (target == null) {
                showMessage("This book has no readable text.")
                return
            }
            // Hide the chrome BEFORE drawing the page (like jumpToToc), so the jump is one clean
            // e-ink redraw rather than a page draw followed by an overlay-dismiss redraw.
            hideOverlay()
            showPage(target)
            flushPosition()
        } catch (e: EpubException) {
            showMessage("Couldn't open that bookmark: ${e.message}")
        } catch (e: Exception) {
            showMessage("Couldn't open that bookmark: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // -- Highlights ------------------------------------------------------------------------------

    private fun highlightDao() = (application as ReaderApplication).database.highlightDao()

    /** Opens/closes the Highlights panel — one panel open at a time (closes the Aa sheet, TOC, Marks). */
    private fun toggleHighlights() {
        if (highlightsPanel.visibility == View.VISIBLE) {
            highlightsPanel.visibility = View.GONE
        } else {
            settingsSheet.visibility = View.GONE
            tocPanel.visibility = View.GONE
            bookmarksPanel.visibility = View.GONE
            highlightsPanel.visibility = View.VISIBLE
            refreshHighlights()
        }
    }

    /**
     * Loads this book's highlights (one-shot, off-main) and rebinds the panel list + empty state. No
     * standing observer: the panel re-reads each time it opens, and delete calls this again.
     */
    private fun refreshHighlights() {
        val path = bookPath ?: return
        lifecycleScope.launch {
            val hl = withContext(Dispatchers.IO) { highlightDao().highlightsForBook(path) }
            val rows = highlightRows(hl, document?.toc.orEmpty())
            highlightsAdapter.submit(rows)
            val empty = rows.isEmpty()
            highlightsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            highlightsList.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    /**
     * Jumps to a tapped highlight via the SAME restore machinery as [jumpToToc]/[jumpToBookmark]:
     * resolve the target page via [tocTarget] (lands on the page containing the highlight's start
     * offset, re-pagination-safe), then [showPage] + [flushPosition], and close the chrome.
     */
    private fun jumpToHighlight(row: HighlightRow) {
        val doc = document ?: return
        val cfg = config ?: return
        val nav = navigator ?: return
        val pageCountFor: (Int) -> Int = { doc.chapter(it, cfg).pages.size }
        try {
            val target = tocTarget(
                spineIndex = row.spineIndex,
                charOffset = row.startOffset,
                pageCountFor = pageCountFor,
                offsetToPageIndex = { spineIndex, charOffset ->
                    pageIndexFor(doc.chapter(spineIndex, cfg).pages, charOffset)
                },
                firstNonEmptyFrom = { from -> advance(nav, ReadingState(from, 0), pageCountFor) },
            ) ?: run { showMessage("This book has no readable text."); return }
            hideOverlay()
            showPage(target)
            flushPosition()
        } catch (e: EpubException) {
            showMessage("Couldn't open that highlight: ${e.message}")
        } catch (e: Exception) {
            showMessage("Couldn't open that highlight: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun deleteHighlightRow(row: HighlightRow) = deleteHighlight(row.id) { refreshHighlights() }

    /** Clears a pending bracket-start (both the field and the on-page marker). */
    private fun clearBracketAnchor() {
        bracketAnchorOffset = null
        pageView.setBracketAnchor(null)
    }

    /** The current chapter's source text (the StaticLayout's text), for word-snapping and excerpting. */
    private fun currentChapterText(): String? {
        val doc = document ?: return null
        val cfg = config ?: return null
        return (doc.chapter(state.spineIndex, cfg).measured as? AndroidMeasuredChapter)?.layout?.text?.toString()
    }

    /**
     * A pen tap: if it lands inside an existing highlight, offer to remove it; otherwise it is a
     * bracket endpoint — the first tap arms a start marker, a second tap in a different word commits
     * the span, and a second tap in the same word cancels.
     */
    internal fun onStylusTap(offset: Int) {
        val existing = highlightContaining(chapterHighlights.toExisting(), offset)
        if (existing != null) {
            clearBracketAnchor() // a remove-tap also abandons any pending bracket
            promptRemoveHighlight(existing.id)
            return
        }

        val text = currentChapterText() ?: return
        val anchor = bracketAnchorOffset
        if (anchor == null) {
            bracketAnchorOffset = offset
            pageView.setBracketAnchor(offset)
            return
        }
        // Tapping the same word as the anchor cancels; otherwise commit the span between them.
        if (snapToWords(text, anchor, anchor) == snapToWords(text, offset, offset)) {
            clearBracketAnchor()
        } else {
            clearBracketAnchor()
            commitHighlight(minOf(anchor, offset), maxOf(anchor, offset))
        }
    }

    /** A pen drag: clear any armed bracket and commit the swiped span. */
    internal fun onStylusDrag(startOffset: Int, endOffset: Int) {
        clearBracketAnchor()
        commitHighlight(minOf(startOffset, endOffset), maxOf(startOffset, endOffset))
    }

    /**
     * Word-snaps [rawStart,rawEnd], merges with the chapter's existing highlights, and writes one row
     * — FK-guarded (a not-yet-indexed book has no `books` row) and cancellation-safe. Reloads the
     * chapter's washes on success. All within the current chapter ([state.spineIndex]); brackets never
     * cross chapters because the anchor is dropped on a chapter change (see [showPage]).
     *
     * `internal`, not `private`, purely as a test seam: [ReaderActivityTest] drives commits with
     * explicit offsets so its assertions do not depend on Robolectric's coarse text measurement.
     */
    internal fun commitHighlight(rawStart: Int, rawEnd: Int) {
        val doc = document ?: return
        val cfg = config ?: return
        val path = bookPath ?: return
        val spineIndex = state.spineIndex
        val text = currentChapterText() ?: return
        val snapped = snapToWords(text, rawStart, rawEnd) ?: return // landed between words → no-op
        lifecycleScope.launch {
            val inLibrary = withContext(Dispatchers.IO) { bookDao().getByPath(path) != null }
            if (!inLibrary) { showMessage("This book isn't in your library yet."); return@launch }
            try {
                val existing = withContext(Dispatchers.IO) { highlightDao().highlightsForChapter(path, spineIndex) }
                val merge = mergeHighlights(existing.map { ExistingHighlight(it.id, it.startOffset, it.endOffset) }, snapped)
                val pages = doc.chapter(spineIndex, cfg).pages
                val frac = bookProgress(chapterWeights, spineIndex, pageIndexFor(pages, merge.merged.start), pages.size)
                val excerpt = text.substring(merge.merged.start, merge.merged.end)
                withContext(Dispatchers.IO) {
                    highlightDao().replaceWithMerged(
                        merge.removedIds,
                        HighlightEntity(
                            bookPath = path, spineIndex = spineIndex,
                            startOffset = merge.merged.start, endOffset = merge.merged.end,
                            text = excerpt, progressFraction = frac, createdAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
                reloadChapterHighlightsIfCurrent(spineIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Couldn't save that highlight: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Confirms and deletes a highlight tapped on the page. */
    private fun promptRemoveHighlight(id: Long) {
        android.app.AlertDialog.Builder(this)
            .setMessage("Remove this highlight?")
            .setPositiveButton("Remove") { _, _ -> deleteHighlight(id) { } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Deletes a highlight by id (off-main), reloads the current chapter's washes, then runs [also]. */
    private fun deleteHighlight(id: Long, also: () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { highlightDao().deleteById(id) }
            reloadChapterHighlightsIfCurrent(state.spineIndex)
            also()
        }
    }

    /** Maps the cached entities to the engine's [ExistingHighlight] shape. */
    private fun List<HighlightEntity>.toExisting(): List<ExistingHighlight> =
        map { ExistingHighlight(it.id, it.startOffset, it.endOffset) }

    /** One-shot off-main load of a chapter's highlights into the cache + PageView washes. */
    private fun loadChapterHighlights(spineIndex: Int) {
        val path = bookPath ?: return
        lifecycleScope.launch {
            val hl = try {
                withContext(Dispatchers.IO) { highlightDao().highlightsForChapter(path, spineIndex) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (spineIndex != chapterHighlightsSpine) return@launch // a later chapter change won the race
            chapterHighlights = hl
            pageView.setHighlights(hl.map { it.startOffset to it.endOffset })
        }
    }

    /** Reloads the chapter cache after an edit, but only if that chapter is still the one on screen. */
    private fun reloadChapterHighlightsIfCurrent(spineIndex: Int) {
        if (spineIndex == chapterHighlightsSpine) loadChapterHighlights(spineIndex)
    }

    // -- Highlight test seams --------------------------------------------------------------------
    // The on-page gesture machine has no observable production surface of its own, so these read-only
    // hooks let ReaderActivityTest assert against the cache and the armed bracket without widening the
    // production API. None is called in production.

    /** The armed bracket-start offset, or null — a test's "did the chapter change drop it?" probe. */
    internal val bracketAnchorForTest: Int? get() = bracketAnchorOffset

    /** The current chapter's cached highlights — a test waits on this before tapping into a wash. */
    internal val chapterHighlightsForTest: List<HighlightEntity> get() = chapterHighlights

    /** The current chapter's source text — a test computes the expected word-snap against it. */
    internal fun currentChapterTextForTest(): String? = currentChapterText()

    /** Wires every Aa-sheet control to its pref write + live re-paginate. Called once from onCreate;
     * the listeners hold no state and fire only on a deliberate tap, so they cost nothing at rest. */
    private fun wireSettingsControls() {
        overlay.findViewById<View>(R.id.font_literata).setOnClickListener { applySettingsChange { p -> p.fontFamily = "literata" } }
        overlay.findViewById<View>(R.id.font_bitter).setOnClickListener { applySettingsChange { p -> p.fontFamily = "bitter" } }
        overlay.findViewById<View>(R.id.font_atkinson).setOnClickListener { applySettingsChange { p -> p.fontFamily = "atkinson" } }
        // The per-option preview typefaces are loaded lazily on first sheet-open, not here — see
        // loadFontPreviewsOnce. Loading three font families synchronously at every book open (even
        // for a reader who never touches the Aa sheet) would be cold-open work for nothing.

        overlay.findViewById<View>(R.id.size_minus).setOnClickListener { stepTextSize(-TEXT_SIZE_STEP_PX) }
        overlay.findViewById<View>(R.id.size_plus).setOnClickListener { stepTextSize(TEXT_SIZE_STEP_PX) }

        overlay.findViewById<View>(R.id.spacing_12).setOnClickListener { applySettingsChange { p -> p.lineSpacingMultiplier = 1.2f } }
        overlay.findViewById<View>(R.id.spacing_14).setOnClickListener { applySettingsChange { p -> p.lineSpacingMultiplier = 1.4f } }
        overlay.findViewById<View>(R.id.spacing_16).setOnClickListener { applySettingsChange { p -> p.lineSpacingMultiplier = 1.6f } }

        overlay.findViewById<View>(R.id.margin_narrow).setOnClickListener { applyMargin(MARGIN_NARROW_PX) }
        overlay.findViewById<View>(R.id.margin_medium).setOnClickListener { applyMargin(MARGIN_MEDIUM_PX) }
        overlay.findViewById<View>(R.id.margin_wide).setOnClickListener { applyMargin(MARGIN_WIDE_PX) }

        overlay.findViewById<View>(R.id.toggle_justify).setOnClickListener { applySettingsChange { p -> p.justified = !p.justified } }
        overlay.findViewById<View>(R.id.toggle_hyphen).setOnClickListener { applySettingsChange { p -> p.hyphenated = !p.hyphenated } }
        overlay.findViewById<View>(R.id.toggle_publisher).setOnClickListener { applySettingsChange { p -> p.publisherStyling = !p.publisherStyling } }
        overlay.findViewById<View>(R.id.toggle_headings).setOnClickListener { applySettingsChange { p -> p.inferHeadings = !p.inferHeadings } }
        overlay.findViewById<View>(R.id.toggle_progress).setOnClickListener { toggleProgressBar() }
    }

    /** Bumps the persisted text size by [deltaPx], clamped to the sane range, then re-paginates. A
     * tap already at the bound only refreshes the readout (no reflow to do). */
    private fun stepTextSize(deltaPx: Float) {
        val current = ReaderPrefs(this).textSizePx
        val next = (current + deltaPx).coerceIn(TEXT_SIZE_MIN_PX, TEXT_SIZE_MAX_PX)
        if (next == current) {
            refreshSheet()
        } else {
            applySettingsChange { p -> p.textSizePx = next }
        }
    }

    /** Applies a margin preset, clamped so the chosen margin can never leave a non-positive content
     * width or height on the current viewport — the value [RenderConfig] would throw on. */
    private fun applyMargin(presetPx: Int) {
        val clamped = presetPx.coerceIn(0, maxMarginForViewport(pageView.width, pageView.height))
        applySettingsChange { p -> p.marginPx = clamped }
    }

    /**
     * The live re-paginate — the Aa sheet's correctness core, run on every control change.
     *
     * Captures the char offset at the top of the CURRENT page under the OLD config, writes the pref,
     * rebuilds the config from the SAME measured viewport the open path used and installs it as the
     * source of truth for later page turns, re-paginates the current chapter, and resolves the
     * captured offset to the page in the NEW pagination whose range contains it (via
     * [reflowedPageIndex]). The reader lands on the same text, not the same page index — a larger
     * font that pushes that text from page 3 to page 5 lands on page 5.
     *
     * A lazily-read chapter can throw [EpubException] on re-pagination, so this is wrapped exactly as
     * [onTap] is: a failure shows a message and leaves the reader on the page it was already showing
     * ([config]/[state] are only reassigned after the throwing `chapter()` calls have succeeded).
     */
    private fun applySettingsChange(mutate: (ReaderPrefs) -> Unit) {
        val doc = document ?: return
        val cfg = config ?: return
        val width = pageView.width
        val height = pageView.height
        if (width <= 0 || height <= 0) return
        try {
            // Capture the CURRENT chapter's pagination under the OLD config before changing anything;
            // reflowedPageIndex reads the top-of-page char offset off it as the anchor to preserve.
            val oldPages = doc.chapter(state.spineIndex, cfg).pages

            mutate(ReaderPrefs(this))
            val newConfig = ReaderPrefs(this).renderConfig(width, height)

            // chapter() takes newConfig as a parameter, so the re-paginate does not need the field
            // set yet. Reassign config/state only AFTER this (throwing) call succeeds, so a failure
            // leaves the field agreeing with the page still on screen — the invariant the KDoc states.
            val newPages = doc.chapter(state.spineIndex, newConfig).pages
            config = newConfig
            val newPageIndex = reflowedPageIndex(oldPages, state.pageIndex, newPages)
            state = ReadingState(state.spineIndex, newPageIndex)
            showPage(state)
            flushPosition()
            refreshSheet()
        } catch (e: EpubException) {
            showMessage("Couldn't apply that setting: ${e.message}")
        } catch (e: Exception) {
            showMessage("Couldn't apply that setting: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Flips the progress-bar display toggle and redraws the bar in place. A pure display change:
     * unlike [applySettingsChange] it does NOT re-paginate, turn the page, or record a position.
     *
     * It is tempting to assume the current chapter is always already paginated (it is on screen)
     * and just call [EpubDocument.chapter], but that is NOT a true invariant, for two reasons:
     *  - Open-path race: [config]/[document]/[chapterWeights]/[showProgressBar] and
     *    `pageView.onTap` are all assigned before the open coroutine suspends on the
     *    `Dispatchers.IO` DAO read. A tap landing in that window calls this with `state` still the
     *    default `ReadingState(0, 0)` and NOTHING paginated yet — a genuine cache miss.
     *  - A failed [applySettingsChange]: `EpubDocument.chapter()` clears the cache and moves its
     *    internal `cacheConfig` to the NEW config before paginating; if the re-paginate throws,
     *    this Activity's [config] correctly stays on the old value, but the document's cache is
     *    left keyed on the new one — so a later call here is also a miss under the old config.
     *
     * Rather than paginating to fill either gap (which would mean this tap handler synchronously
     * paginates on the main thread — exactly what [EpubDocument.chapter]'s cache-confinement
     * contract is trying to prevent outside a real page turn), this refuses to paginate BY
     * CONSTRUCTION: [EpubDocument.isPaginated] is a read-only `containsKey` peek (it does not
     * disturb the access-ordered LRU), so the fraction is only computed when the chapter is
     * already resident. Otherwise `null` goes to [PageView.setProgress] — no bar until the next
     * [showPage], which recomputes the real fraction. That is a rare window and harmless: the bar
     * simply reappears one page turn later, not a wrong or stale value.
     */
    private fun toggleProgressBar() {
        val prefs = ReaderPrefs(this)
        prefs.showProgressBar = !prefs.showProgressBar
        showProgressBar = prefs.showProgressBar

        val doc = document
        val cfg = config
        val fraction = if (showProgressBar && doc != null && cfg != null && doc.isPaginated(state.spineIndex, cfg)) {
            val pageCount = doc.chapter(state.spineIndex, cfg).pages.size
            bookProgress(chapterWeights, state.spineIndex, state.pageIndex, pageCount)
        } else {
            null
        }
        pageView.setProgress(fraction)
        refreshSheet()
    }

    /** Syncs the sheet's controls to the current [ReaderPrefs]: the selected option in each group is
     * marked with a boxed outline (see [setOptionSelected]), the size readout shows the current px,
     * and each [ToggleSwitchView] reflects its boolean. Pure View work. */
    private fun refreshSheet() {
        val prefs = ReaderPrefs(this)

        setOptionSelected(R.id.font_literata, prefs.fontFamily == "literata")
        setOptionSelected(R.id.font_bitter, prefs.fontFamily == "bitter")
        setOptionSelected(R.id.font_atkinson, prefs.fontFamily == "atkinson")

        overlay.findViewById<TextView>(R.id.size_value).text = "${prefs.textSizePx.toInt()}px"

        setOptionSelected(R.id.spacing_12, prefs.lineSpacingMultiplier == 1.2f)
        setOptionSelected(R.id.spacing_14, prefs.lineSpacingMultiplier == 1.4f)
        setOptionSelected(R.id.spacing_16, prefs.lineSpacingMultiplier == 1.6f)

        setOptionSelected(R.id.margin_narrow, prefs.marginPx == MARGIN_NARROW_PX)
        setOptionSelected(R.id.margin_medium, prefs.marginPx == MARGIN_MEDIUM_PX)
        setOptionSelected(R.id.margin_wide, prefs.marginPx == MARGIN_WIDE_PX)

        setToggle(R.id.toggle_justify_switch, prefs.justified)
        setToggle(R.id.toggle_hyphen_switch, prefs.hyphenated)
        setToggle(R.id.toggle_publisher_switch, prefs.publisherStyling)
        setToggle(R.id.toggle_headings_switch, prefs.inferHeadings)
        setToggle(R.id.toggle_progress_switch, prefs.showProgressBar)
    }

    private fun setOptionSelected(id: Int, selected: Boolean) {
        // A boxed outline (not bold weight) marks the selection. It is typeface-independent, so it
        // works with the font options that preview their own face without disturbing that face, and
        // — crucially — it clears cleanly. The old `setTypeface(view.typeface, NORMAL)` could not
        // strip bold back off a bundled font's already-bold instance, so de-selecting silently
        // failed and every font eventually rendered as selected. `0` clears the background.
        overlay.findViewById<TextView>(id)
            .setBackgroundResource(if (selected) R.drawable.aa_option_selected else 0)
    }

    private fun setToggle(switchId: Int, on: Boolean) {
        overlay.findViewById<ToggleSwitchView>(switchId).checked = on
    }

    /**
     * Leaves the book for the library. Flushes the current position first (every page turn already
     * persists its own, so this normally drains nothing — it is the backstop for the open-time
     * write), then finishes. Wired to both the overlay Back control and system Back with the
     * overlay hidden.
     */
    private fun exitToLibrary() {
        flushPosition()
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (document != null || opening) return
        if (!isAllFilesAccessGranted()) {
            // Without the permission there is nothing this screen can ever show, and silently
            // doing nothing here (as this branch used to) leaves a blank white page
            // indistinguishable from a broken app. Say why and bow out; reopening after
            // granting starts clean. (In the normal flow this is unreachable: LibraryActivity
            // already gated on the same permission before launching us.)
            showMessage("Reader needs all-files access to open books.")
            finish()
            return
        }
        pageView.doOnLayout { openFirstBook() }
    }

    /**
     * Whether all-files access is currently granted — the same thin `protected open` wrapper
     * around [hasAllFilesAccess] as [LibraryActivity]'s, and for the same reason: Robolectric
     * cannot fake `Environment.isExternalStorageManager()`, so [ReaderActivityTest] stubs this
     * one point via a test subclass.
     */
    protected open fun isAllFilesAccessGranted(): Boolean = hasAllFilesAccess()

    /**
     * A final [flushPosition] on the way out. Every page turn already persists its own position (see
     * [flushPosition] and [showPage]), so by the time onStop runs there is usually nothing pending and
     * this drains to null. It stays as a backstop for the one position change that a turn does not
     * cover: the open-time write. The write is launched into the application scope, not lifecycleScope,
     * because onStop is immediately followed by onDestroy cancelling lifecycleScope, which could cancel
     * the UPDATE before it commits — see [persistPosition] and [ReaderApplication.positionWriteScope].
     */
    override fun onStop() {
        flushPosition()
        super.onStop()
    }

    /**
     * Persists the latest recorded position, if any, to this book's row. Called after every page turn
     * and once more from [onStop]. [ReadingSession.drainPending] returns the position last recorded by
     * [showPage] and clears it, so a flush with nothing new to write (a second flush after the same
     * turn) writes nothing rather than re-committing a stale row.
     *
     * Writing on every turn — rather than coalescing until exit — is what keeps the on-disk progress
     * current: close the app, pull the battery, or have the process killed, and it reopens on the page
     * last turned to, not the page the book was opened at. It does not cost the idle promise: the write
     * is triggered by the user's own page turn (never at rest), runs off the main thread so it adds no
     * latency to the e-ink refresh, and is a single sub-millisecond keyed UPDATE — negligible beside
     * the full-screen EPD redraw the same turn already paid for.
     */
    private fun flushPosition() {
        session.drainPending()?.let { persistPosition(it) }
    }

    /**
     * Writes [locator] to this book's row via [dev.reader.data.BookDao.updatePosition], stamping
     * `lastOpenedAtMs` to now. Launched into [ReaderApplication.positionWriteScope] — an
     * application-scoped, cancel-independent scope — rather than `lifecycleScope`, because [onStop]
     * calls this and onDestroy (cancelling lifecycleScope) follows onStop immediately: a write on
     * lifecycleScope could be cancelled before it commits, the "work cancelled before it ran" bug.
     * The scope is dormant until this launch, so it never wakes the process on its own.
     *
     * A book that is not in the library ([bookPath] never matched a row, e.g. a standalone adb
     * launch) makes updatePosition match 0 rows and silently no-op; that book simply doesn't persist.
     */
    private fun persistPosition(locator: Locator) {
        val path = bookPath ?: return
        val app = application as ReaderApplication
        val dao = app.database.bookDao()
        val now = System.currentTimeMillis()
        // Capture the fraction on the main thread, at the same moment the locator is drained, so the
        // stored percentage matches the stored page — a later showPage must not mutate what this
        // write commits.
        val fraction = currentBookProgress
        app.positionWriteScope.launch {
            dao.updatePosition(path, locator.spineIndex, locator.charOffset, fraction, now)
        }
    }

    override fun onDestroy() {
        document?.close()
        document = null
        super.onDestroy()
    }

    private fun openFirstBook() {
        if (document != null || opening) return

        // pageView.width/height can still be 0 here in principle (doOnLayout fires on any
        // layout pass, not only one that gave the view real bounds). RenderConfig's init
        // throws on a non-positive content width/height, which would otherwise crash this
        // coroutine and the app. Guard it and simply wait for a layout pass that has bounds.
        //
        // doOnNextLayout, not doOnLayout: doOnLayout runs its action SYNCHRONOUSLY when
        // `isLaidOut && !isLayoutRequested`, and isLaidOut() is true after *any* completed layout
        // pass — including a 0x0 one. Re-arming with doOnLayout here would therefore recurse
        // openFirstBook -> doOnLayout -> openFirstBook without bound and blow the stack in exactly
        // the case this guard exists to survive. doOnNextLayout always defers to a future pass.
        val width = pageView.width
        val height = pageView.height
        if (width <= 0 || height <= 0) {
            pageView.doOnNextLayout { openFirstBook() }
            return
        }

        // The typography now comes from persisted settings (ReaderPrefs) rather than literals;
        // only the viewport is per-open, measured from the view just above. Defaults equal the
        // old literals, so an untouched install renders identically.
        val renderConfig = ReaderPrefs(this).renderConfig(
            viewportWidthPx = width,
            viewportHeightPx = height,
        )
        config = renderConfig

        // Set synchronously, before launch: see the `opening` field's KDoc. Nothing between here
        // and the coroutine's first suspension point can run on this thread, so a second
        // openFirstBook() on this same layout pass is guaranteed to see this and bail.
        opening = true

        lifecycleScope.launch {
            var opened: EpubDocument? = null
            try {
                val explicitPath = intent.getStringExtra(EXTRA_BOOK_PATH)
                val file = withContext(Dispatchers.IO) {
                    if (explicitPath != null) File(explicitPath).takeIf { it.isFile } else findFirstEpub()
                }
                if (file == null) {
                    opening = false
                    if (explicitPath != null) {
                        // The tapped book vanished between the grid painting and the tap landing
                        // (the index can be behind the filesystem). Falling back to findFirstEpub
                        // here — as this path used to — silently opens a DIFFERENT book than the
                        // one tapped, and once Task 6 wires position memory it would write that
                        // book's position onto the wrong row. Name the problem and bow out; the
                        // library re-syncs on re-entry and drops the stale cell.
                        showMessage("That book is no longer there.")
                        finish()
                    } else {
                        // No extra at all: the standalone adb launch path. Not a permanent
                        // failure — the user may drop a book in and come back, and onResume
                        // re-arms only while `opening` is false.
                        showMessage("No EPUB found in /Document.")
                    }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    opened = openDocument(file)
                }
                val doc = opened!!
                document = doc
                // chapterWeights is a lazy, one-time ZIP central-directory read (never a pagination),
                // so capturing it here costs nothing extra on the open path it already runs on. The
                // toggle mirrors ReaderPrefs so showPage — the hot path — never constructs it itself.
                chapterWeights = doc.chapterWeights
                showProgressBar = ReaderPrefs(this@ReaderActivity).showProgressBar
                bookPath = file.path
                navigator = PageNavigator(doc.spineSize)
                pageView.onTap = ::onTap

                // The overlay title: the book's own metadata title. The parser already substitutes
                // "Untitled" for a missing <dc:title>, and the library grid shows that same value —
                // so we deliberately do NOT override it with the filename here (that would make the
                // reader disagree with the library). The filename is only a defensive fallback for
                // the currently-unreachable case of a blank title slipping through.
                titleView.text = doc.metadata.title.takeIf { it.isNotBlank() }
                    ?: File(file.path).nameWithoutExtension

                // The stored position for this book, if it is in the library. getByPath is the only
                // read on IO; resolveStart and every chapter() below stay on the main thread, as
                // chapter()'s unsynchronized cache requires. A book not in the library (the
                // standalone adb launch, or one indexing hasn't reached) has no row -> null -> a
                // fresh read from the start, exactly the pre-Task-6 behavior.
                val dao = (application as ReaderApplication).database.bookDao()
                val storedEntity = withContext(Dispatchers.IO) { dao.getByPath(file.path) }
                val stored = storedEntity?.let { session.storedLocator(it.spineIndex, it.charOffset) }

                // resolveStart owns the clamp / empty-chapter fallback / offset->page rules (unit-
                // tested in ReadingSessionTest). The lambdas are the only impure parts:
                //  - firstNonEmptyFrom generalizes the old cover-skip: advance() from the stored (or
                //    0th) chapter, skipping empty chapters exactly as a page turn would. It only
                //    fires for a chapter that paginates to ZERO pages — a cover the parser renders
                //    no block for (an SVG/<image> cover, common), or content that lost its text.
                //    Since inline images render, an <img>-based cover chapter now paginates to one
                //    page (the image itself) instead of the blank page it used to show, so it is
                //    NOT skipped: a fresh read deliberately lands on the cover image, and the reader
                //    turns to the text. A stored position still restores exactly (its offset
                //    resolves regardless); only the never-opened landing shows the cover first.
                //  - offsetToPageIndex maps the stored char offset back to a page; pageIndexFor
                //    already survives a re-pagination after a font-size/margin change (:engine test).
                val pageCountFor: (Int) -> Int = { doc.chapter(it, renderConfig).pages.size }
                val start = session.resolveStart(
                    stored = stored,
                    spineSize = doc.spineSize,
                    pageCountFor = pageCountFor,
                    offsetToPageIndex = { spineIndex, charOffset ->
                        pageIndexFor(doc.chapter(spineIndex, renderConfig).pages, charOffset)
                    },
                    firstNonEmptyFrom = { from -> advance(navigator!!, ReadingState(from, 0), pageCountFor) },
                )

                val firstChapter = doc.chapter(start.spineIndex, renderConfig)
                if (firstChapter.pages.isEmpty()) {
                    // A missing or empty chapter file paginates to zero pages. showPage() would
                    // return silently and leave a blank white screen — indistinguishable from a
                    // broken app — so name the problem, and say that the book may still be
                    // readable from the next chapter on (advance() skips empty chapters).
                    showMessage("This book has no readable text.")
                    // showPage never ran, so the scrubber was never set; give the overlay (if the
                    // reader opens it on this broken book) a coherent readout instead of a blank.
                    scrubberView.text = "No readable text"
                } else {
                    showPage(start)
                    // Write the resolved start back immediately: stamps lastOpenedAtMs (so the
                    // RECENTLY_OPENED sort works, and it survives a process kill that skips onStop)
                    // AND heals a stale or clamped stored position on disk. showPage recorded the
                    // resolved start, so flushPosition persists exactly the page that was shown.
                    flushPosition()
                }
            } catch (e: CancellationException) {
                // The activity was destroyed while open() was in flight. lifecycleScope cancelled
                // us, so `document = doc` never ran and onDestroy saw null — this is the only
                // thing standing between a back-press during load and a leaked ZipFile. `opened`
                // is assigned inside the IO block, so it is set here even though withContext threw
                // on resumption. Rethrown: cancellation must never be swallowed (it would also be
                // caught by the `Exception` branch below, which is why this branch comes first).
                opened?.close()
                throw e
            } catch (e: EpubException) {
                opening = false
                showMessage("Couldn't open this book: ${e.message}")
            } catch (e: Exception) {
                // open() is documented to throw only EpubException, but that promise is only as
                // good as every path inside EpubPackageParser/EpubTocParser honouring it (e.g. a
                // raw XmlPullParserException or IOException from a corrupt-but-zip-valid file).
                // A malformed book must never crash the app, so nothing escapes this boundary.
                opening = false
                showMessage("Couldn't open this book: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Opens [file] as an EPUB — always called on [Dispatchers.IO]. `protected open` purely as a
     * test seam: [ReaderActivityTest] substitutes implementations that count invocations, block
     * until released, or throw, to exercise the [opening]-flag race, the cancel-mid-open close
     * path, and failed-open recovery without racing real multi-second book opens. Production
     * always opens the real file with the real Android measurer.
     */
    protected open fun openDocument(file: File): EpubDocument = EpubDocument.open(
        file,
        AndroidTextMeasurer(SpannedChapterBuilder(), BundledTypefaceProvider(this)),
    )

    /**
     * The standalone-launch fallback (no [EXTRA_BOOK_PATH] on the intent): the first EPUB under
     * /Document. `protected open` purely as a test seam — [ReaderActivityTest] substitutes it to
     * exercise the fallback path without a real /Document tree.
     */
    protected open fun findFirstEpub(): File? = try {
        val documents = File(Environment.getExternalStorageDirectory(), "Document")
        documents.walkTopDown()
            .maxDepth(10) // Closes an unbounded symlink-loop walk; matches LibraryIndexer.walk().
            .filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }
            .firstOrNull()
    } catch (e: SecurityException) {
        // A denied or half-revoked all-files-access grant can surface here as walkTopDown
        // touches directories; treat it as "nothing found" rather than crashing.
        null
    }

    /**
     * Resolves a page turn with [dev.reader.engine.advance] / [dev.reader.engine.retreat], the
     * pure functions in `:engine` that own the empty-chapter looping and are unit-tested there
     * (`PageTurnsTest`). Both take everything they need from the document as a single
     * `spineIndex -> page count` lookup, which is all `PageNavigator`'s two documented traps
     * require: `next()` cannot know whether the chapter it lands on has pages, and `previous()`
     * answers `LastPageOf` blindly, which is `-1` on an empty chapter.
     *
     * View delivers taps on the main thread, so every `chapter()` call made through the lookup
     * below is main-thread-confined, as `EpubDocument.chapter()`'s unsynchronized cache requires.
     */
    private fun onTap(zone: TapZone) {
        // While the overlay is up, any tap that reaches pageView is on the page area (the overlay's
        // Back control sits above pageView and consumes its own tap), so it dismisses the overlay
        // and turns NO page — not even a PREVIOUS/NEXT zone tap. Paired with the TOGGLE_OVERLAY show
        // below, the center tap is a true toggle: hidden -> show here, shown -> hide here.
        if (isOverlayVisible()) {
            hideOverlay()
            return
        }

        val nav = navigator ?: return
        val doc = document ?: return
        val cfg = config ?: return
        val pageCountFor: (Int) -> Int = { doc.chapter(it, cfg).pages.size }

        // Chapter bytes are read lazily (EpubDocument.chapter -> readBlocks -> readTextChecked),
        // so any chapter past the one openFirstBook already paginated can throw EpubException here
        // for the first time — a corrupt deflate stream, a truncated archive, the zip-bomb guard.
        // This runs synchronously inside View.onTouchEvent with nothing else on the stack to catch
        // it, so it must be handled right here or the app dies on a page turn. `state` is only
        // ever reassigned below in showPage, after its own chapter() call has already succeeded,
        // so a failure anywhere in advance()/retreat()/showPage() leaves `state` untouched — the
        // reader simply stays on the page it was already showing.
        //
        // Not a coroutine, so no CancellationException can arise here; none is caught.
        try {
            val next = when (zone) {
                TapZone.NEXT -> advance(nav, state, pageCountFor)
                TapZone.PREVIOUS -> retreat(nav, state, pageCountFor)
                // Overlay hidden (the visible case returned above): reveal it. No page turn, so
                // this arm yields null and the showPage/flush below is skipped.
                TapZone.TOGGLE_OVERLAY -> {
                    showOverlay()
                    null
                }
            }
            // null = nowhere to go (start/end of book, or everything beyond is empty): stay put and
            // draw nothing, so a tap at the end of the book costs no invalidate — and nothing to
            // persist, since the position did not change.
            if (next != null) {
                showPage(next)
                // Persist the new position now, not at onStop: reopening lands on the page last
                // turned to even across a battery pull. showPage recorded it; this writes it, off
                // the main thread and serialized (see flushPosition / positionWriteScope).
                flushPosition()
                // Refresh cadence: every REFRESH_CADENCE actual page turns, force a full-panel
                // redraw to clear accumulated e-ink ghosting. Counter-driven, not time-driven, so
                // it holds no steady state. Only genuine turns count — an overlay toggle (which
                // yields null above and never reaches here), a settings re-paginate, or a TOC jump
                // do not, matching "every N turns" rather than "every N redraws".
                if (++turnsSinceRefresh >= REFRESH_CADENCE) {
                    pageView.fullRefresh()
                    turnsSinceRefresh = 0
                }
            }
        } catch (e: EpubException) {
            showMessage("Couldn't turn the page: ${e.message}")
        } catch (e: Exception) {
            // Mirrors openFirstBook's defense-in-depth catch: chapter() is documented to throw
            // only EpubException, but that promise is only as good as every path inside the
            // format parsers honouring it. A malformed book must never crash the app here either.
            showMessage("Couldn't turn the page: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun showPage(next: ReadingState) {
        val doc = document ?: return
        val cfg = config ?: return
        val chapter: PaginatedChapter = doc.chapter(next.spineIndex, cfg)
        if (chapter.pages.isEmpty()) return

        val pageIndex = next.pageIndex.coerceIn(0, chapter.pages.lastIndex)
        state = next.copy(pageIndex = pageIndex)

        if (state.spineIndex != chapterHighlightsSpine) {
            // Moved to a new chapter: a pending bracket cannot cross chapters, so drop it with a note.
            if (bracketAnchorOffset != null) {
                showMessage("Highlight cancelled — you moved to another chapter.")
                clearBracketAnchor()
            }
            chapterHighlightsSpine = state.spineIndex
            chapterHighlights = emptyList()
            pageView.setHighlights(emptyList())
            loadChapterHighlights(state.spineIndex)
        }

        // Keep the overlay's read-only readout current with the page just shown, so it is right the
        // next time the overlay opens. Reuses the chapter already fetched above — no extra work, and
        // page turns only happen while the overlay is hidden anyway.
        scrubberView.text = scrubberText(pageIndex, chapter.pages.size)

        // Unchecked downcast through the TextMeasurer seam: MeasuredChapter itself stays
        // Android-free, but PageView needs the real StaticLayout to draw. Safe today because
        // this Activity is the only caller of EpubDocument.open, always with
        // AndroidTextMeasurer — this cast is the seam's one leak, and it stays that way rather
        // than widening MeasuredChapter's contract for a single caller.
        val layout = (chapter.measured as AndroidMeasuredChapter).layout
        pageView.show(layout, chapter.pages[pageIndex], cfg.marginPx)
        // Computed once and used two ways: it drives the in-book bar (only when the toggle is on)
        // AND is captured for persistence below so the library card can show the same percentage.
        // Persistence is independent of the display toggle — hiding the bar must not blank the
        // library's progress.
        currentBookProgress = bookProgress(chapterWeights, state.spineIndex, pageIndex, chapter.pages.size)
        pageView.setProgress(if (showProgressBar) currentBookProgress else null)

        // Record the new position: the page's startOffset is the stable char offset a later restore
        // maps back to a page. This only sets an in-memory field; the caller (onTap, or the open
        // path) follows with flushPosition to write it. Keeping the write out of showPage means the
        // main-thread draw path never touches the DB — the UPDATE happens on the write scope.
        session.recordPageTurn(Locator(state.spineIndex, chapter.pages[pageIndex].startOffset))

        // Now that the page has settled, prefetch the adjacent chapter the next boundary turn would
        // land on (if any), so that turn does not pay the 230–360ms pagination on the main thread.
        // One shot; see schedulePrefetch. Reuses chapter.pages.size — no extra work.
        schedulePrefetch(chapter.pages.size)
    }

    /**
     * Paginates the neighbouring chapter a boundary turn is about to need — [PrefetchPolicy]'s
     * [chapterToPrefetch] decides which, or none — on ONE background coroutine, then publishes the
     * result on the main thread. A pure performance nicety: it must never destabilize the reader, so
     * every load-bearing rule below is defensive.
     *
     *  - Correctness: only the now-race-free [EpubDocument.paginate] runs off the main thread (Task
     *    6b, Part A made it thread-safe by construction); the cache is touched only by [publish], and
     *    only back on the main thread. [chapter] is never called off-main.
     *  - Staleness: if the reader changes typography while this is in flight, [publish] discards the
     *    result (its config no longer matches the cache's) — the boundary turn simply re-paginates.
     *  - Waste: a neighbour already cached (the usual case for the previous chapter after a forward
     *    read) is skipped rather than needlessly recomputed.
     *  - The idle promise: exactly one coroutine per settle, it runs once and completes. It does not
     *    loop or re-arm; nothing schedules the next prefetch but the user's next turn. A superseded
     *    in-flight prefetch is cancelled when the next one is scheduled.
     *  - Teardown: [paginate] is non-suspending CPU/IO work with no cancellation points, so cancelling
     *    the job (on supersede or onDestroy) does NOT interrupt a paginate already running — and
     *    onDestroy closes the archive's [ZipFile] on the main thread. A background read can therefore
     *    find the archive closed under it mid-paginate and throw a raw exception. That is caught and
     *    dropped below (the result is worthless once the book is closing); it never reaches [publish],
     *    never corrupts the cache, and never crashes teardown. The nicety simply evaporates.
     *  - Cost: the pagination runs at the lowest JVM thread priority for the span of the call, then
     *    restores the pooled thread's priority, so it yields to anything the user is actively doing.
     */
    private fun schedulePrefetch(chapterPageCount: Int) {
        val doc = document ?: return
        val cfg = config ?: return
        val target = chapterToPrefetch(state, chapterPageCount, doc.spineSize) ?: return
        // Already resident (e.g. the chapter just turned away from): the prefetch would recompute it
        // only for publish to no-op. Skip the wasted pagination.
        if (doc.isPaginated(target, cfg)) return

        // Supersede any earlier prefetch still running: only the newest neighbour matters, and a
        // cancelled paginate is simply discarded (it never reached publish).
        prefetchJob?.cancel()
        prefetchJob = lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    val thread = Thread.currentThread()
                    val priorPriority = thread.priority
                    thread.priority = Thread.MIN_PRIORITY // background work yields to the foreground
                    try {
                        doc.paginate(target, cfg) // off the main thread — race-free by construction
                    } finally {
                        thread.priority = priorPriority // restore the pooled thread for its next use
                    }
                }
            } catch (e: CancellationException) {
                throw e // a genuine cancel must propagate so the coroutine unwinds
            } catch (e: Throwable) {
                // Almost always the archive being closed under us in onDestroy (a raw ZipFile
                // "closed" ISE that readTextChecked does not translate). A prefetch is a nicety;
                // drop it rather than let a teardown-time read crash the app.
                return@launch
            }
            // Back on the main thread (lifecycleScope is Main): publish drops the result if a
            // typography change since the launch moved the cache's config on.
            doc.publish(target, cfg, result)
        }
    }

    // -- Test seams -----------------------------------------------------------------------------
    // The prefetch is a background nicety with no user-visible surface of its own, so these two
    // read-only hooks let ReaderActivityTest observe it (did the neighbour get cached? did the
    // coroutine terminate without re-arming?) without widening the production API. Neither is
    // called in production.

    /** The current prefetch coroutine, if any — a test reads its liveness to prove it terminates. */
    internal val prefetchJobForTest: Job? get() = prefetchJob

    /** Whether chapter [spineIndex] is cached under the live config — a test's prefetch-landed probe. */
    internal fun isChapterCachedForTest(spineIndex: Int): Boolean {
        val doc = document ?: return false
        val cfg = config ?: return false
        return doc.isPaginated(spineIndex, cfg)
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * The largest margin the current viewport can take while still leaving positive content width AND
     * height. `RenderConfig.init` throws when `viewport - margin*2 <= 0` on either axis, so a margin
     * preset is clamped to this before it is written. `margin*2 < min(w, h)` ⇒ `margin <= (min - 1) /
     * 2`. On the ~1404×1872 panel this is ~701, so every real preset (≤80) passes untouched; the
     * clamp only bites on a pathologically small viewport that no device presents.
     */
    private fun maxMarginForViewport(width: Int, height: Int): Int =
        ((minOf(width, height) - 1) / 2).coerceAtLeast(0)

    companion object {
        /** String extra: an absolute book path, set by [LibraryActivity] when opening a tap. */
        const val EXTRA_BOOK_PATH = "dev.reader.ui.EXTRA_BOOK_PATH"

        /** Full-panel refresh cadence (spec default): a ghost-clearing redraw every N page turns. */
        private const val REFRESH_CADENCE = 8

        // The Aa sheet's bounded value sets. Text size is a stepper over [MIN, MAX] by STEP; the
        // others are presets. All chosen so the resulting RenderConfig stays valid on the device
        // viewport (margins additionally clamped per-viewport by maxMarginForViewport).
        private const val TEXT_SIZE_MIN_PX = 24f
        private const val TEXT_SIZE_MAX_PX = 56f
        private const val TEXT_SIZE_STEP_PX = 2f
        private const val MARGIN_NARROW_PX = 24
        private const val MARGIN_MEDIUM_PX = 48
        private const val MARGIN_WIDE_PX = 80
    }
}
