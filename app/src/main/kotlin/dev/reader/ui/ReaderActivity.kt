package dev.reader.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R
import dev.reader.ReaderApplication
import dev.reader.data.HighlightEntity
import dev.reader.engine.Locator
import dev.reader.engine.Page
import dev.reader.engine.PageNavigator
import dev.reader.engine.ReadingState
import dev.reader.engine.RenderConfig
import dev.reader.engine.TocEntry
import dev.reader.engine.advance
import dev.reader.engine.advanceSpread
import dev.reader.engine.bookProgress
import dev.reader.engine.BookLocation
import dev.reader.engine.chapterEndFraction
import dev.reader.engine.chapterTitleFor
import dev.reader.engine.locateByFraction
import dev.reader.engine.pageIndexFor
import dev.reader.engine.reflowedPageIndex
import dev.reader.engine.retreat
import dev.reader.engine.retreatSpread
import dev.reader.engine.spreadStart
import dev.reader.formats.epub.EpubDocument
import dev.reader.formats.epub.EpubException
import dev.reader.formats.epub.PaginatedChapter
import dev.reader.formats.render.AndroidMeasuredChapter
import dev.reader.formats.render.AndroidTextMeasurer
import dev.reader.formats.render.SpannedChapterBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

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
 * bold marker; [spineIndex]/[charOffset] are what a tap jumps to; [progressPercent] is the
 * whole-book position the leader dots run to. Kept as a flat data class, out of the View, so the
 * list-building rules below are unit-testable without a RecyclerView.
 */
internal data class TocRow(
    val title: String,
    val spineIndex: Int,
    val charOffset: Int,
    val depth: Int,
    val isCurrent: Boolean,
    val progressPercent: Int,
)

/**
 * Projects the parsed [toc] into [TocRow]s in list (spine) order, marking every entry whose chapter
 * is [currentSpineIndex] as the current one, and resolving each entry's whole-book percentage
 * through [progressForRow]. A pure function of its arguments — the testable seam behind the
 * Contents list — so order, depth passthrough, current-chapter marking and percentages are all
 * verifiable without an Activity. An empty [toc] yields an empty list (the "No contents" case).
 *
 * [progressForRow] is called once per TOC entry, so it MUST be byte-weighted and paginate nothing —
 * [ReaderSurface.chapterStartProgress], never [ReaderSurface.progressFor]. `progressFor` resolves
 * through the current pagination (a cache-miss chapter read: parse + `StaticLayout` measure), which
 * is fine for the one anchor a bookmark or highlight asks about, but calling it once per entry here
 * would paginate every chapter in the book just to open the panel — the eager work this reader
 * exists to avoid, and worse, would evict the chapter actually being read from the bounded LRU
 * chapter cache. The real caller ([TocPanel.refresh]) wires this to `chapterStartProgress`.
 */
internal fun tocRows(
    toc: List<TocEntry>,
    currentSpineIndex: Int,
    progressForRow: (Int, Int) -> Float,
): List<TocRow> =
    toc.map { entry ->
        TocRow(
            title = entry.title,
            spineIndex = entry.spineIndex,
            charOffset = entry.charOffset,
            depth = entry.depth,
            isCurrent = entry.spineIndex == currentSpineIndex,
            progressPercent = (progressForRow(entry.spineIndex, entry.charOffset).coerceIn(0f, 1f) * 100)
                .roundToInt(),
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
     * The chapter scrubber beneath [scrubberView] in the bottom bar. Reports whole-book fractions;
     * see [onScrubMoved]/[onScrubCommitted]/[abandonScrub] for the no-live-preview contract this
     * Activity enforces on top of it.
     */
    private lateinit var chapterScrubber: ChapterScrubberView

    /** The ↩ control beside [chapterScrubber]: pops [jumpStack]. GONE whenever the stack is empty —
     *  see [updateBackControl]. */
    private lateinit var scrubberBackView: TextView

    /**
     * The floating page-preview window over [chapterScrubber]: the sampled thumbnail nearest the
     * finger, blitted from [previewStrip] during a drag. GONE at rest and whenever no strip is
     * loaded — see [onScrubMoved]. Never the book page itself; that view never repaints mid-drag.
     */
    private lateinit var scrubPreview: ImageView

    /** The open book's thumbnail strip, or null when none is generated yet (first open, generation
     *  still running, or generation failed). Loaded once per open, off the main thread. */
    private var previewStrip: StripIndex? = null

    /** The strip store; also the generation trigger's collaborator (Task 6). */
    private val stripStore by lazy { PreviewStripStore(this) }

    /**
     * The one in-flight strip generation, if any — held so a config change mid-generate can cancel
     * and relaunch rather than race a second generator over the same directory. See
     * [scheduleStripGeneration].
     */
    private var stripGenerationJob: Job? = null

    /** The entry currently blitted, to skip redundant decodes as the finger dithers in place. */
    private var shownPreviewEntry: StripEntry? = null

    /**
     * The Aa typography sheet — a visibility-toggled panel inside [overlay], opened by the Aa button.
     * Holds no timer or animation: showing/hiding is one `visibility` flip. Each of its controls
     * writes a [ReaderPrefs] field then live-re-paginates the current chapter via
     * [applySettingsChange], keeping the reader on the same text across the reflow.
     */
    private lateinit var settingsSheet: View
    private lateinit var settings: SettingsSheet

    /**
     * The Contents panel — a visibility-toggled, full-height list inside [overlay], opened by the
     * Contents button. Like [settingsSheet] it holds no timer or animation: showing/hiding is one
     * `visibility` flip. Its list is [tocList] (backed by [tocAdapter]); [tocEmpty] takes its place
     * when the book has no usable TOC. Tapping an entry jumps via [jumpToToc].
     */
    private lateinit var tocPanel: View
    private lateinit var toc: TocPanel

    /**
     * The Bookmarks panel — a visibility-toggled, full-height layer inside [overlay], opened by the
     * Bookmarks ("Marks") button. This Activity owns only [bookmarksPanel]'s visibility; everything
     * inside it (the list, the empty state, the add/remove toggle, and every database call) belongs
     * to [BookmarksPanel]. Like [tocPanel] it is one `visibility` flip, no timer or animation.
     */
    private lateinit var bookmarksPanel: View
    private lateinit var bookmarks: BookmarksPanel

    /**
     * The Highlights panel — a visibility-toggled, full-height layer inside [overlay], opened by the
     * Highlights button. Same shape as [bookmarksPanel]: one `visibility` flip, no timer or animation.
     * Unlike the bookmarks panel it has NO add/remove toggle — highlighting happens on the page with
     * the pen (see [onStylusTap]/[onStylusDrag]/[commitHighlight]). [highlightsList] is backed by
     * [highlightsAdapter]; [highlightsEmpty] takes its place when the book has no highlights yet. Its
     * list is loaded once per open by [refreshHighlights] — never a standing observer.
     */
    private lateinit var highlightsPanel: View
    private lateinit var highlights: HighlightsController

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

    /** Mirrors [ReaderPrefs.fasterPageTurns] / [ReaderPrefs.fullRefreshEveryN], read once at open and
     *  kept current by the Aa toggle — so the hot page-turn path never constructs [ReaderPrefs]. */
    private var fasterPageTurns: Boolean = false
    private var fullRefreshEveryN: Int = 6

    /** Whole-book progress `[0,1]` of the page [showPage] last drew — captured there (independently
     *  of [showProgressBar]) so [persistPosition] can store it for the library's percentage. */
    private var currentBookProgress: Float = 0f

    /**
     * The one in-flight adjacent-chapter prefetch, if any (see [schedulePrefetch]). Held only so a
     * newer settle can cancel a now-superseded prefetch before launching the next — there is never
     * more than one, and it is one-shot: it paginates a single neighbour off the main thread and
     * completes. It costs nothing at rest (the idle promise): no timer, no polling, no re-arm — the
     * next prefetch is launched only by the user's next page turn. Lives on [lifecycleScope], so
     * leaving the book (onDestroy) cancels a prefetch still in flight.
     */
    private var prefetchJob: Job? = null

    /**
     * The one in-flight commit render, if any — set only on lift-off, never during a drag. Held so a
     * newer commit can cancel a still-running one, the same cancel-and-relaunch shape [prefetchJob]
     * uses. One-shot: it paginates the selected chapter off the main thread, shows the page, and
     * completes. Costs nothing at rest.
     */
    private var scrubJob: Job? = null

    /**
     * Where the current scrub started, or null when no scrub is in flight. A scrub that is abandoned
     * — the overlay dismissed, Back pressed, the gesture cancelled — returns here and persists
     * nothing, so an exploratory drag can never lose the reader's place.
     */
    private var scrubOrigin: ReadingState? = null

    /** Page turns since the last full-panel refresh; drives the [shouldFullRefresh] ghost-clear. */
    private var turnsSinceRefresh = 0

    /**
     * The jump back-stack: every JUMP (a scrub commit, a Contents/bookmark/highlight jump via
     * [ReaderSurface.pushJump]) pushes the position being left; [onBackJump] pops. Page turns never
     * push. In-memory, per book-open — cleared in [openFirstBook] alongside [previewStrip]/bookmarks,
     * since a new book is a new session. Costs nothing at rest: no timer, no observer.
     */
    private val jumpStack = JumpStack()

    /** Whether the Aa font options have been given their preview typefaces yet (loaded once, on
     * the first sheet-open — see [loadFontPreviewsOnce]). */

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

    /** Incremented at the top of every [showPage] call — a test's only way to prove a drag renders
     *  no page: it reads this before and after a run of [onScrubMoved] calls and asserts it is
     *  unchanged, since only [onScrubCommitted] (lift-off) is allowed to call [showPage]. */
    internal var pagesShownForTest: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyRotationLock(ReaderPrefs(this).rotationLocked)
        pageView = PageView(this)
        pageView.epd = EinkController.forContext(this)

        // Wrap the page in a container so the overlay can draw ABOVE it. The overlay is added after
        // pageView, so it sits on top; it is not clickable itself, so page-area taps fall through to
        // pageView (which dismisses the overlay) while its Back control consumes its own tap.
        val container = FrameLayout(this)
        container.addView(pageView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        overlay = layoutInflater.inflate(R.layout.overlay_reader, container, false)
        container.addView(overlay)
        // The on-page delete chip is added to this container too, by HighlightsController below —
        // after the overlay, so it draws above both the page and the chrome.
        titleView = overlay.findViewById(R.id.book_title)
        scrubberView = overlay.findViewById(R.id.scrubber)
        chapterScrubber = overlay.findViewById(R.id.chapter_scrubber)
        scrubberBackView = overlay.findViewById(R.id.scrubber_back)
        scrubberBackView.setOnClickListener { onBackJump() }
        scrubPreview = overlay.findViewById(R.id.scrub_preview)
        chapterScrubber.onScrubStart = {
            // Cancel any still-running prior commit before starting a new drag. Without this, an
            // old commit's showPage() can land mid-drag (a repaint during a drag, forbidden) and then
            // null out scrubOrigin out from under this new drag, breaking a later abandon.
            scrubJob?.cancel()
            scrubOrigin = state
        }
        chapterScrubber.onScrubMove = { fraction, snap -> onScrubMoved(fraction, snap) }
        chapterScrubber.onScrubCommit = { fraction, snap -> onScrubCommitted(fraction, snap) }
        chapterScrubber.onScrubCancel = { abandonScrub() }
        settingsSheet = overlay.findViewById(R.id.settings_sheet)
        settings = SettingsSheet(overlay, settingsHost) { ReaderPrefs(this) }
        tocPanel = overlay.findViewById(R.id.toc_panel)
        toc = TocPanel(overlay, readerSurface)
        bookmarksPanel = overlay.findViewById(R.id.bookmarks_panel)
        bookmarks = BookmarksPanel(
            overlay, readerSurface, lifecycleScope,
            database.bookmarkDao(), database.bookDao(),
            onBookmarksChanged = ::refreshScrubberBookmarks,
        )
        highlightsPanel = overlay.findViewById(R.id.highlights_panel)
        highlights = HighlightsController(
            overlay, container, pageView, readerSurface, lifecycleScope,
            database.highlightDao(), database.bookDao(),
        )
        overlay.findViewById<View>(R.id.back).setOnClickListener { exitToLibrary() }
        overlay.findViewById<View>(R.id.highlights_button).setOnClickListener { toggleHighlights() }
        overlay.findViewById<View>(R.id.bookmarks_button).setOnClickListener { toggleBookmarks() }
        overlay.findViewById<View>(R.id.contents_button).setOnClickListener { toggleToc() }
        overlay.findViewById<View>(R.id.settings_button).setOnClickListener { toggleSettings() }
        // The device has no hardware Back, so each panel/sheet carries a top-right ✕ that peels it
        // back to the reading toolbar — the same first step system Back takes. Closing a panel only
        // hides that layer; the bare overlay stays up (tap the page to return to reading).
        overlay.findViewById<View>(R.id.toc_close).setOnClickListener { tocPanel.visibility = View.GONE }
        overlay.findViewById<View>(R.id.bookmarks_close).setOnClickListener { bookmarksPanel.visibility = View.GONE }
        overlay.findViewById<View>(R.id.highlights_close).setOnClickListener { highlightsPanel.visibility = View.GONE }
        overlay.findViewById<View>(R.id.settings_close).setOnClickListener { settingsSheet.visibility = View.GONE }
        settings.wire()
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

    /**
     * The panels' view of this reader (see [ReaderSurface]). An anonymous implementation rather than
     * `ReaderActivity : ReaderSurface`, deliberately: the interface exists to NARROW what a panel can
     * reach, and making the Activity itself the surface would hand every panel the whole Activity
     * again through an up-cast, which is the coupling being removed.
     *
     * The paginating members below can throw [EpubException] — chapter bytes are read lazily, so a
     * corrupt chapter surfaces the first time a panel reaches it. That is the documented contract;
     * each panel catches where it can report.
     */
    private val readerSurface = object : ReaderSurface {

        override val isBookOpen: Boolean
            get() = document != null && config != null && navigator != null

        override val toc: List<TocEntry> get() = document?.toc.orEmpty()

        override val currentState: ReadingState get() = state

        override val currentPage: Page?
            get() {
                val doc = document ?: return null
                val cfg = config ?: return null
                return doc.chapter(state.spineIndex, cfg).pages.getOrNull(state.pageIndex)
            }

        override val currentProgress: Float get() = currentBookProgress

        override val bookPath: String? get() = this@ReaderActivity.bookPath

        override fun pageCountFor(spineIndex: Int): Int {
            val doc = document ?: return 0
            val cfg = config ?: return 0
            return doc.chapter(spineIndex, cfg).pages.size
        }

        override fun pageIndexForOffset(spineIndex: Int, charOffset: Int): Int {
            val doc = document ?: return 0
            val cfg = config ?: return 0
            return pageIndexFor(doc.chapter(spineIndex, cfg).pages, charOffset)
        }

        override fun firstNonEmptyFrom(spineIndex: Int): ReadingState? {
            val nav = navigator ?: return null
            return advance(nav, ReadingState(spineIndex, 0), ::pageCountFor)
        }

        override fun currentChapterText(): String? = this@ReaderActivity.currentChapterText()

        override fun progressFor(spineIndex: Int, charOffset: Int): Float {
            val doc = document ?: return 0f
            val cfg = config ?: return 0f
            val pages = doc.chapter(spineIndex, cfg).pages
            return bookProgress(chapterWeights, spineIndex, pageIndexFor(pages, charOffset), pages.size)
        }

        override fun chapterStartProgress(spineIndex: Int): Float =
            bookProgress(chapterWeights, spineIndex, 0, 1)

        override fun goTo(target: ReadingState) {
            showPage(target)
            flushPosition()
            // goTo is only ever reached via jumpToAnchor (Contents/Bookmarks/Highlights), whose
            // preceding closeOverlay() deliberately skipped its own clean refresh (see hideOverlay) —
            // so this is the one clean refresh of the DESTINATION page for the whole jump. showPage
            // only invalidate()s, so without this the landed-on page would carry whatever ghosting
            // accumulated while the chrome (fast mode) was up, exactly the "crisp on return" promise
            // a plain overlay close gets from hideOverlay's own refresh.
            pageView.fullRefresh()
            turnsSinceRefresh = 0
        }

        // The jump path: skips hideOverlay's own clean refresh (which would flash the page being
        // LEFT) — goTo above supplies the one clean refresh of the DESTINATION page instead.
        override fun closeOverlay() = hideOverlay(cleanRefresh = false)

        override fun pushJump() {
            jumpStack.push(state)
            updateBackControl()
        }

        override fun message(messageId: Int) = showMessage(messageId)

        override fun error(messageId: Int, cause: Throwable) = showError(messageId, cause)
    }

    /** The current chapter's source text (the StaticLayout's text), for word-snapping and excerpting. */
    private fun currentChapterText(): String? {
        val doc = document ?: return null
        val cfg = config ?: return null
        return (doc.chapter(state.spineIndex, cfg).measured as? AndroidMeasuredChapter)?.layout?.text?.toString()
    }

    /**
     * What the Aa sheet is allowed to do (see [SettingsHost]). Typography changes go through
     * [applySettingsChange], which re-paginates and keeps the reader's place; the display-only
     * switches deliberately do NOT, since none of them changes how a page is laid out.
     */
    private val settingsHost = object : SettingsHost {

        override fun applyTypography(mutate: (ReaderPrefs) -> Unit) = applySettingsChange(mutate)

        override fun stepTextSize(deltaPx: Float) = this@ReaderActivity.stepTextSize(deltaPx)

        override fun applyMarginPreset(presetPx: Int) = applyMargin(presetPx)

        override fun toggleProgressBar() = this@ReaderActivity.toggleProgressBar()

        override fun toggleRotationLock() = this@ReaderActivity.toggleRotationLock()

        override fun toggleFasterTurns() = this@ReaderActivity.toggleFasterTurns()

        override fun applyRefreshFrequency(pages: Int) = this@ReaderActivity.applyRefreshFrequency(pages)
    }

    /** The app's single Room database — the panels take the DAOs they need from it. */
    private val database get() = (application as ReaderApplication).database

    /** Whether the reading chrome is currently on screen. */
    private fun isOverlayVisible(): Boolean = overlay.visibility == View.VISIBLE

    /** Reveals the reading chrome — one redraw, no animation. */
    private fun showOverlay() {
        highlights.hideDeleteChip() // the chip is a reading-mode affordance; it never coexists with the chrome
        // Chrome is redrawn far too often for a clean update per frame. Fast mode is device-wide
        // runtime state — see onPause, which is what guarantees it is given back.
        pageView.epd.enterFastMode()
        overlay.visibility = View.VISIBLE
        // Reflects the jump stack as it stands now — a jump made with the chrome hidden (there is
        // none today, but this keeps the control honest regardless) or a stack a prior showOverlay
        // already reflected both resolve to the same visibility here.
        updateBackControl()
    }

    /**
     * Dismisses the reading chrome — one redraw, no animation. Also closes the Aa sheet, the
     * Contents panel, and the Bookmarks panel, so the overlay always reopens to its bare bar rather
     * than a stale open panel.
     *
     * [cleanRefresh] is false only on the jump path ([readerSurface]'s `closeOverlay`, called by
     * [jumpToAnchor] before [goTo] draws a DIFFERENT page): a clean refresh here would flash the page
     * being LEFT, and still leave the destination un-clean-refreshed once [goTo] draws over it —
     * [goTo] does its own single refresh after the new page is on screen instead. The plain "return
     * to reading, same page" close (the toggle tap and system Back) always wants true.
     *
     * [pageView.epd.exitFastMode] is unconditional either way — the screen-mode restore is
     * device-wide runtime state (see [onPause]) and must happen on every close, jump or not.
     */
    private fun hideOverlay(cleanRefresh: Boolean = true) {
        // A scrub still in flight when the overlay closes (Back, the toggle tap, a jump) is
        // abandoned first: the page never moved during the drag, so this just clears scrubOrigin
        // and re-syncs the readout/thumb. A no-op when no scrub is in flight.
        abandonScrub()
        settingsSheet.visibility = View.GONE
        tocPanel.visibility = View.GONE
        bookmarksPanel.visibility = View.GONE
        highlightsPanel.visibility = View.GONE
        // Returning to the page ends any pen selection that was in progress when the chrome went up:
        // a bracket armed before then is stale, and its marker would otherwise still be sitting on
        // the page waiting for a second tap the reader has long since moved on from.
        highlights.cancelPendingSelection()
        overlay.visibility = View.GONE
        pageView.epd.exitFastMode()
        if (cleanRefresh) {
            // One clean refresh on the way out, so the page the reader returns to is crisp rather
            // than carrying whatever ghosting fast mode accumulated while the chrome was up.
            pageView.fullRefresh()
            turnsSinceRefresh = 0
        }
    }

    /** Opens or closes the Aa sheet — a visibility flip (one redraw). Opening first syncs its
     * controls to the current [ReaderPrefs] so it always shows the live values. */
    private fun toggleSettings() {
        highlights.hideDeleteChip() // a panel covers the page; the on-page chip must not float over it
        if (settingsSheet.visibility == View.VISIBLE) {
            settingsSheet.visibility = View.GONE
        } else {
            tocPanel.visibility = View.GONE // one panel open at a time
            bookmarksPanel.visibility = View.GONE
            highlightsPanel.visibility = View.GONE
            settings.loadFontPreviewsOnce() // before refresh(): sets each font option's preview face
            settings.refresh()
            settingsSheet.visibility = View.VISIBLE
        }
    }

    /** Opens or closes the Contents panel — a visibility flip (one redraw). Opening first rebuilds
     * the list from the current [EpubDocument.toc] and current chapter, and closes the Aa sheet so
     * only one panel is ever open. */
    private fun toggleToc() {
        highlights.hideDeleteChip() // a panel covers the page; the on-page chip must not float over it
        if (tocPanel.visibility == View.VISIBLE) {
            tocPanel.visibility = View.GONE
        } else {
            settingsSheet.visibility = View.GONE // one panel open at a time
            bookmarksPanel.visibility = View.GONE
            highlightsPanel.visibility = View.GONE
            toc.refresh()
            tocPanel.visibility = View.VISIBLE
        }
    }

    /** Opens/closes the Highlights panel — one panel open at a time (closes the Aa sheet, TOC, Marks). */
    private fun toggleHighlights() {
        highlights.hideDeleteChip() // a panel covers the page; the on-page chip must not float over it
        if (highlightsPanel.visibility == View.VISIBLE) {
            highlightsPanel.visibility = View.GONE
        } else {
            settingsSheet.visibility = View.GONE
            tocPanel.visibility = View.GONE
            bookmarksPanel.visibility = View.GONE
            highlightsPanel.visibility = View.VISIBLE
            highlights.refresh()
        }
    }

    /** Opens/closes the Bookmarks panel — one panel open at a time (closes the Aa sheet and TOC). */
    private fun toggleBookmarks() {
        highlights.hideDeleteChip() // a panel covers the page; the on-page chip must not float over it
        if (bookmarksPanel.visibility == View.VISIBLE) {
            bookmarksPanel.visibility = View.GONE
        } else {
            settingsSheet.visibility = View.GONE // one panel open at a time
            tocPanel.visibility = View.GONE
            highlightsPanel.visibility = View.GONE
            bookmarksPanel.visibility = View.VISIBLE
            bookmarks.refresh()
        }
    }

    // -- Highlight test seams --------------------------------------------------------------------
    // The on-page gesture machine has no observable production surface of its own, so these read-only
    // hooks let ReaderActivityTest assert against the cache and the armed bracket without widening the
    // production API. None is called in production.

    /** The armed bracket-start offset, or null — a test's "did the chapter change drop it?" probe. */
    internal val bracketAnchorForTest: Int? get() = highlights.bracketAnchorForTest

    /** The current chapter's cached highlights — a test waits on this before tapping into a wash. */
    internal val chapterHighlightsForTest: List<HighlightEntity> get() = highlights.chapterHighlightsForTest

    /** The current chapter's source text — a test computes the expected word-snap against it. */
    internal fun currentChapterTextForTest(): String? = currentChapterText()

    /** The char offset at the top of the page on screen — the anchor a re-pagination preserves. */
    internal fun currentTopOffsetForTest(): Locator? {
        val doc = document ?: return null
        val cfg = config ?: return null
        val page = doc.chapter(state.spineIndex, cfg).pages.getOrNull(state.pageIndex) ?: return null
        return Locator(state.spineIndex, page.startOffset)
    }

    /** The on-page delete chip — a test asserts a highlight-tap reveals it and its tap deletes. */
    internal val deleteChipForTest: TextView get() = highlights.deleteChipForTest

    /** Delegates to the private overlay show/hide so a test can drive the fast-e-ink-mode wiring
     *  directly, without going through a tap dispatch. */
    internal fun showOverlayForTest() = showOverlay()
    internal fun hideOverlayForTest() = hideOverlay()

    /** Pen entry points, forwarded so the tests can drive the gesture machine with exact offsets
     *  rather than depending on Robolectric's coarse text measurement. */
    internal fun onStylusTap(offset: Int) = highlights.onStylusTap(offset)
    internal fun onStylusDrag(startOffset: Int, endOffset: Int) =
        highlights.onStylusDrag(startOffset, endOffset)
    internal fun commitHighlight(rawStart: Int, rawEnd: Int) = highlights.commit(rawStart, rawEnd)

    /** Bumps the persisted text size by [deltaPx], clamped to the sane range, then re-paginates. A
     * tap already at the bound only refreshes the readout (no reflow to do). */
    private fun stepTextSize(deltaPx: Float) {
        val current = ReaderPrefs(this).textSizePx
        val next = (current + deltaPx).coerceIn(TEXT_SIZE_MIN_PX, TEXT_SIZE_MAX_PX)
        if (next == current) {
            settings.refresh()
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
            val newConfig = ReaderPrefs(this).renderConfig(width, height, pageView.bottomChromeHeightPx)

            // chapter() takes newConfig as a parameter, so the re-paginate does not need the field
            // set yet. Reassign config/state only AFTER this (throwing) call succeeds, so a failure
            // leaves the field agreeing with the page still on screen — the invariant the KDoc states.
            val newPages = doc.chapter(state.spineIndex, newConfig).pages
            config = newConfig
            // The strip is keyed to the typography; a visual change makes it stale. Drop it so the
            // preview degrades to the readout until a strip for the new config is loaded.
            // shownPreviewEntry is cleared so a later drag re-blits fresh. The old strip directory
            // is deleted by the generator on completion; until then stripFor simply misses.
            previewStrip = null
            shownPreviewEntry = null
            scheduleStripGeneration()
            val newPageIndex = reflowedPageIndex(oldPages, state.pageIndex, newPages)
            state = ReadingState(state.spineIndex, newPageIndex)
            showPage(state)
            flushPosition()
            settings.refresh()
        } catch (e: EpubException) {
            showError(R.string.error_apply_setting, e)
        } catch (e: Exception) {
            showError(R.string.error_apply_setting, e)
        }
    }

    /**
     * The device was rotated (or the window otherwise resized). The manifest declares
     * `configChanges="orientation|screenSize"`, so this arrives INSTEAD of the activity being
     * destroyed and recreated — which would reopen the ZIP, re-parse and re-measure the chapter from
     * scratch, and flash the panel through a teardown. On e-ink that is a multi-second ugly
     * transition for something the reader already knows how to do in one redraw.
     *
     * The work is exactly a settings change with no setting changed: [applySettingsChange] rebuilds
     * the config from the newly measured viewport (which is what picks up the new column count — see
     * [ReaderPrefs.renderConfig]), re-paginates, and resolves the char offset at the top of the
     * current page onto the new pagination. The reader lands on the same words, not the same page
     * number.
     *
     * Deferred to the next layout pass: when this callback runs, the view has been told the
     * configuration changed but has NOT been re-measured, so `pageView.width/height` are still the
     * old orientation's. Building a config from them would paginate portrait pages and then draw
     * them into landscape columns.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Nothing is open yet. The open path measures the viewport for itself — and when it is
        // already in flight when this arrives, it has ALREADY measured, so it finishes against a
        // viewport that no longer exists. That case is caught after the open instead, by
        // [reconcileViewport]; there is nothing useful to do from here.
        if (document == null) return
        // An anchored chip and a half-drawn overlay both point at coordinates that are about to stop
        // existing. The overlay's own layout follows the new viewport; the chip is positioned per-tap
        // and has no way to re-resolve itself, so it goes.
        highlights.hideDeleteChip()
        pageView.doOnNextLayout { applySettingsChange { /* re-measure only; no pref changes */ } }
    }

    /**
     * Re-paginates if the config the book was opened against no longer matches the viewport on
     * screen, and does nothing (the overwhelmingly common case) if it does.
     *
     * This closes the window that [onConfigurationChanged] structurally cannot: [openFirstBook]
     * measures the viewport and installs the config SYNCHRONOUSLY, but `document` is only assigned
     * after a multi-second archive open on [Dispatchers.IO]. A configuration change arriving in
     * between finds `document == null` and has nothing to act on, and nothing re-arms it afterwards.
     *
     * That window is not exotic — it is the ordinary path for someone who reads in landscape. The
     * library is pinned portrait, so a book tapped while the device is held sideways opens into a
     * portrait window, measures a portrait viewport, and only then is rotated by the system. Without
     * this, the book would paginate as one narrow portrait column stranded on a landscape screen,
     * turning one page at a time, until the reader rotated twice or touched an Aa control.
     *
     * Comparing the whole [RenderConfig] rather than just the orientation makes it total: any drift
     * between the config in force and the one this viewport would produce is reconciled, whatever
     * caused it. Rebuilding through the same [ReaderPrefs.renderConfig] the open path used means an
     * unchanged viewport compares equal and this costs one allocation and no pagination.
     */
    private fun reconcileViewport() {
        val cfg = config ?: return
        val width = pageView.width
        val height = pageView.height
        if (width <= 0 || height <= 0) return
        val current = ReaderPrefs(this).renderConfig(width, height, pageView.bottomChromeHeightPx)
        if (current != cfg) applySettingsChange { /* re-measure only; no pref changes */ }
    }

    /**
     * Pins the reader to its current orientation, or releases it back to the sensor.
     *
     * Locking uses the CURRENT orientation rather than a stored one, so the lock means "keep it like
     * this" — which is what a reader settling down on their side actually wants. Unlocking returns
     * to `UNSPECIFIED`, which defers to the system auto-rotate setting rather than forcing rotation
     * on: if the reader has auto-rotate off system-wide, this app does not override that.
     */
    private fun applyRotationLock(locked: Boolean) {
        requestedOrientation = if (!locked) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /** Flips [ReaderPrefs.rotationLocked] and applies it immediately. A pure display-side change:
     *  it never re-paginates — if it changes anything about the viewport, that arrives as a
     *  configuration change and [onConfigurationChanged] handles it. */
    private fun toggleRotationLock() {
        val prefs = ReaderPrefs(this)
        prefs.rotationLocked = !prefs.rotationLocked
        applyRotationLock(prefs.rotationLocked)
        settings.refresh()
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
        settings.refresh()
    }

    /** Flips [ReaderPrefs.fasterPageTurns] and resets the turn counter so the new cadence (every
     *  turn, or every [ReaderPrefs.fullRefreshEveryN]th) starts fresh rather than firing on a count
     *  accumulated under the old mode. */
    private fun toggleFasterTurns() {
        val prefs = ReaderPrefs(this)
        prefs.fasterPageTurns = !prefs.fasterPageTurns
        fasterPageTurns = prefs.fasterPageTurns
        turnsSinceRefresh = 0 // start the new cadence fresh so the next full refresh lands correctly
        settings.refresh()
    }

    /** Persists a new [ReaderPrefs.fullRefreshEveryN] and resets the turn counter for the same
     *  reason [toggleFasterTurns] does. */
    private fun applyRefreshFrequency(pages: Int) {
        val prefs = ReaderPrefs(this)
        prefs.fullRefreshEveryN = pages
        fullRefreshEveryN = prefs.fullRefreshEveryN
        turnsSinceRefresh = 0
        settings.refresh()
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
            showMessage(R.string.library_permission_prompt)
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
     * Gives the panel's screen mode back — the load-bearing restore, not the one in the overlay-hide
     * path. The mode is device-wide runtime state and is not persisted: if the process dies or the
     * app is swiped away with the overlay open, a leaked fast mode degrades the entire device UI
     * until something resets it. `onPause` is the last callback Android guarantees, so the restore
     * rides here. Idempotent — a no-op when fast mode is not held.
     */
    override fun onPause() {
        super.onPause()
        pageView.epd.exitFastMode()
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
            bottomChromePx = pageView.bottomChromeHeightPx,
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
                        showMessage(R.string.error_book_missing)
                        finish()
                    } else {
                        // No extra at all: the standalone adb launch path. Not a permanent
                        // failure — the user may drop a book in and come back, and onResume
                        // re-arms only while `opening` is false.
                        showMessage(R.string.error_no_epub_found)
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
                fasterPageTurns = ReaderPrefs(this@ReaderActivity).fasterPageTurns
                fullRefreshEveryN = ReaderPrefs(this@ReaderActivity).fullRefreshEveryN
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
                    showMessage(R.string.error_book_no_text)
                    // showPage never ran, so the scrubber was never set; give the overlay (if the
                    // reader opens it on this broken book) a coherent readout instead of a blank.
                    scrubberView.text = getString(R.string.error_no_text_short)
                } else {
                    showPage(start)
                    // Write the resolved start back immediately: stamps lastOpenedAtMs (so the
                    // RECENTLY_OPENED sort works, and it survives a process kill that skips onStop)
                    // AND heals a stale or clamped stored position on disk. showPage recorded the
                    // resolved start, so flushPosition persists exactly the page that was shown.
                    flushPosition()
                    // The device may have been rotated while this open was in flight — most likely
                    // by the system itself, on a book tapped from the portrait-pinned library while
                    // the reader held the device sideways. See reconcileViewport.
                    reconcileViewport()
                    // A new book is a new session: the jump back-stack from whatever was open
                    // before (if anything) means nothing here, and hanging onto it would let ↩
                    // "return" to a position in a book that is no longer open.
                    jumpStack.clear()
                    updateBackControl()
                    // Bookmark glyphs for the scrubber: loaded once per open (and again on
                    // add/remove via BookmarksPanel's onBookmarksChanged callback above).
                    refreshScrubberBookmarks()
                    // The scrubbing preview's thumbnail strip, if one has been generated for this
                    // exact (book, config) already.
                    previewStrip = withContext(Dispatchers.IO) { stripStore.stripFor(file, renderConfig) }
                    // No valid strip for this (book, config): schedule the one-shot background
                    // generation. Absent until it completes, the preview window simply never shows —
                    // the correct, non-crashing fallback.
                    if (previewStrip == null) scheduleStripGeneration()
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
                showError(R.string.error_open_book, e)
            } catch (e: Exception) {
                // open() is documented to throw only EpubException, but that promise is only as
                // good as every path inside EpubPackageParser/EpubTocParser honouring it (e.g. a
                // raw XmlPullParserException or IOException from a corrupt-but-zip-valid file).
                // A malformed book must never crash the app, so nothing escapes this boundary.
                opening = false
                showError(R.string.error_open_book, e)
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
     * Schedules THE one-shot strip generation: cancel-and-relaunch (a typography change mid-generate
     * must not leave two generators racing over the same directory), on lifecycleScope so leaving the
     * book cancels it — generation resumes on the next open instead. The relaunch JOINS the previous
     * job before touching the (possibly same) strip directory — a bare cancel() doesn't wait for the
     * prior coroutine to actually stop, so without the join a mid-open rotation (which schedules once
     * for the corrected config and again from the open path, same config, same dir) could still run
     * two generators over one directory concurrently. The project's single authorized exception to
     * 0%-idle: bounded (5–15 s measured), one-shot, only on first open or config change. `protected
     * open` purely as the test seam.
     */
    protected open fun scheduleStripGeneration() {
        val file = bookPath?.let(::File) ?: return
        val cfg = config ?: return
        val previous = stripGenerationJob
        stripGenerationJob = lifecycleScope.launch {
            // Wait for any prior generator to fully stop before regenerating — see KDoc above.
            previous?.cancelAndJoin()
            try {
                stripStore.generate(file, cfg)
                stripStore.evictOverBudget(keep = file)
                previewStrip = withContext(Dispatchers.IO) { stripStore.stripFor(file, cfg) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed generation degrades to no preview window — never to a crashed reader.
                Log.w("Reader", "preview strip generation failed", e)
            }
        }
    }

    /**
     * Bookmark glyphs for the scrubber: loaded once per open, and again whenever
     * [BookmarksPanel] re-reads its list (open/add/remove), via the callback wired at
     * construction — no standing observer, so a reader sitting on a page costs nothing.
     *
     * Rows come off [Dispatchers.IO]; [ReaderSurface.progressFor] runs after that `withContext`
     * returns, on the main thread, because it can paginate an uncached chapter and the reader's
     * document cache is main-thread-only. This is a handful of bookmarks through already-cached
     * chapters at most — not the whole-TOC pagination trap [ReaderSurface.chapterStartProgress]
     * exists for — and a bookmark's glyph should sit at its true page. If a book with bookmarks
     * scattered across many unvisited chapters makes this open-path pagination cost show up,
     * chapterStartProgress is the coarser, free fallback.
     */
    private fun refreshScrubberBookmarks() {
        val path = bookPath ?: return
        lifecycleScope.launch {
            val dao = (application as ReaderApplication).database.bookmarkDao()
            val marks = withContext(Dispatchers.IO) { dao.bookmarksFor(path) }
            val fractions = marks.map { readerSurface.progressFor(it.spineIndex, it.charOffset) }
            chapterScrubber.setBookmarks(mergedBookmarkFractions(fractions))
        }
    }

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
        highlights.hideDeleteChip() // a finger tap (page turn or overlay toggle) dismisses any on-page delete chip
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
            // A landscape spread shows two pages, so a turn moves two — the spread functions defer
            // to these same advance/retreat rules for everything that is not simple arithmetic
            // (crossing a chapter boundary, skipping a chapter that paginates to nothing).
            val spread = cfg.columnCount > 1
            val next = when (zone) {
                TapZone.NEXT ->
                    if (spread) advanceSpread(nav, state, pageCountFor) else advance(nav, state, pageCountFor)
                TapZone.PREVIOUS ->
                    if (spread) retreatSpread(nav, state, pageCountFor) else retreat(nav, state, pageCountFor)
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
                // Refresh cadence: a full-panel redraw to clear accumulated e-ink ghosting, paced by
                // the prefs-driven cadence (see shouldFullRefresh) — by default every turn is a full
                // refresh; with Faster page turns on, only every fullRefreshEveryN'th turn is.
                // Counter-driven, not time-driven, so it holds no steady state. Only genuine turns
                // count — an overlay toggle (which yields null above and never reaches here), a
                // settings re-paginate, or a TOC jump do not, matching "every N turns" rather than
                // "every N redraws".
                turnsSinceRefresh++
                if (shouldFullRefresh(fasterPageTurns, fullRefreshEveryN, turnsSinceRefresh)) {
                    pageView.fullRefresh()
                    turnsSinceRefresh = 0
                }
            }
        } catch (e: EpubException) {
            showError(R.string.error_turn_page, e)
        } catch (e: Exception) {
            // Mirrors openFirstBook's defense-in-depth catch: chapter() is documented to throw
            // only EpubException, but that promise is only as good as every path inside the
            // format parsers honouring it. A malformed book must never crash the app here either.
            showError(R.string.error_turn_page, e)
        }
    }

    private fun showPage(next: ReadingState) {
        pagesShownForTest++
        val doc = document ?: return
        val cfg = config ?: return
        val chapter: PaginatedChapter = doc.chapter(next.spineIndex, cfg)
        if (chapter.pages.isEmpty()) return

        // Align to the spread that OWNS the requested page whenever two columns are showing. Every
        // path that lands on a page runs through here — a TOC jump, a bookmark, a highlight, the
        // locator reflowed after a rotation — so none of them can split a spread and leave every
        // later turn pairing pages that a forward read never paired.
        val requested = next.pageIndex.coerceIn(0, chapter.pages.lastIndex)
        val pageIndex = if (cfg.columnCount > 1) spreadStart(requested) else requested
        state = next.copy(pageIndex = pageIndex)

        highlights.hideDeleteChip() // the page is changing; an anchored delete chip no longer points at anything

        // Reloads the chapter's washes when the chapter changed, and no-ops otherwise, so a page
        // turn within a chapter costs no database read.
        highlights.onChapterShown(state.spineIndex)

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
        // The right column of a landscape spread: the next page of THIS chapter, or nothing when
        // there isn't one (a spread never crosses a chapter boundary, so an odd-length chapter ends
        // with a blank right column, as a printed book does).
        val secondPage = if (cfg.columnCount > 1) chapter.pages.getOrNull(pageIndex + 1) else null
        pageView.show(
            layout,
            chapter.pages[pageIndex],
            cfg.marginPx,
            secondPage = secondPage,
            columnGapPx = cfg.columnGapPx,
        )
        // Computed once and used two ways: it drives the in-book bar (only when the toggle is on)
        // AND is captured for persistence below so the library card can show the same percentage.
        // Persistence is independent of the display toggle — hiding the bar must not blank the
        // library's progress.
        currentBookProgress = bookProgress(chapterWeights, state.spineIndex, pageIndex, chapter.pages.size)
        // Ticks and thumb follow the page, so opening the overlay always shows the true position.
        // Skipped mid-scrub: the finger owns the thumb until it lifts. Placed after
        // currentBookProgress is (re)computed above — the scrubber must show the fraction of the
        // page just drawn, not the one before it.
        if (scrubOrigin == null) {
            chapterScrubber.setBook(
                chapterStartFractions = chapterWeights.indices.map { i ->
                    if (i == 0) 0f else chapterEndFraction(chapterWeights, i - 1)
                },
                progress = currentBookProgress,
            )
        }
        // The tick is computed from chapterWeights alone — no pagination, no new state — and is
        // suppressed with the bar itself so hiding the bar hides all of it.
        pageView.setProgress(
            if (showProgressBar) currentBookProgress else null,
            if (showProgressBar) chapterEndFraction(chapterWeights, state.spineIndex) else null,
        )
        // Same once-per-turn readout as the progress bar and scrubber above — chapterTitleFor is the
        // same pure TOC lookup the bookmarks/highlights rows already use.
        pageView.setRunningFoot(
            chapterTitleFor(doc.toc, next.spineIndex),
            pageIndex + 1,
            chapter.pages.size,
            // "pages 3–4 of 12" for a full spread; the singular form when the right column is blank,
            // so the foot never names a page that is not on screen.
            lastPageInSpread = if (secondPage != null) pageIndex + 2 else pageIndex + 1,
        )

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
     * A drag position: update the readout text only. Deliberately NO pagination and NO page render —
     * the book page does not repaint until the finger lifts. The chapter title comes from the same
     * pure TOC lookup the running foot uses; mapping the fraction to a chapter touches only
     * chapterWeights, never the document.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun onScrubMoved(fraction: Float, snappedChapter: Int?) {
        val doc = document ?: return
        val located = locateByFraction(chapterWeights, fraction)
        scrubberView.text = getString(
            R.string.scrubber_position,
            chapterTitleFor(doc.toc, located.spineIndex).orEmpty(),
            (fraction.coerceIn(0f, 1f) * 100).roundToInt(),
        )

        // The preview blit: nearest sampled thumbnail, decoded off disk. No strip -> no window; the
        // readout above already carries chapter + percent. Never paginates, never touches the page.
        val strip = previewStrip
        val bookFile = bookPath?.let(::File)
        val cfg = config
        if (strip != null && bookFile != null && cfg != null) {
            val entry = nearestEntry(strip.entries, fraction)
            if (entry != null && entry != shownPreviewEntry) {
                val bmp = android.graphics.BitmapFactory.decodeFile(
                    stripStore.thumbnailFile(bookFile, cfg, entry).path,
                )
                shownPreviewEntry = entry            // mark attempted either way — no re-decode churn
                if (bmp != null) {
                    scrubPreview.setImageBitmap(bmp)
                    scrubPreview.visibility = View.VISIBLE
                } else {
                    // A missing/corrupt thumbnail: hide rather than leave a wrong page showing.
                    scrubPreview.setImageDrawable(null)
                    scrubPreview.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Lift-off: this is the only path that renders a page from a scrub. Paginate the selected chapter
     * off the main thread, show the page, persist it, and clear the scrub. One clean refresh, because
     * the page has not been drawn since the drag began.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun onScrubCommitted(fraction: Float, snappedChapter: Int?) {
        scrubJob?.cancel()
        // Capture the position being LEFT now — `state` still holds it here, since showPage (inside
        // the coroutine below) hasn't moved it yet. The jump back-stack push itself is deferred until
        // the target resolves below (see the comment there); this is unrelated to scrubOrigin below:
        // that field is the ABANDON path's memory, this is the ↩ control's.
        val origin = state
        // Lift-off is a commitment, not a draft: clear scrubOrigin synchronously, before launching
        // the commit coroutine, so this navigation is no longer abandonable. Without this, dismissing
        // the overlay during the ~230-360ms off-main-thread pagination below sees scrubOrigin still
        // set and reverts the jump the user just committed (abandonScrub is now a no-op instead, since
        // it early-returns on a null origin).
        scrubOrigin = null
        // The preview window belongs to the drag, not to the page it lands on: hide it the moment
        // the finger commits, synchronously, same as scrubOrigin above — the render below is a
        // separate, later thing.
        scrubPreview.visibility = View.GONE
        scrubPreview.setImageDrawable(null)
        shownPreviewEntry = null
        scrubJob = lifecycleScope.launch {
            val located = locateByFraction(chapterWeights, fraction)
            val target = withContext(Dispatchers.IO) { resolveScrubTarget(located) }
            // Only a commit that actually moves the reader is a jump: a null target (a chapter that
            // paginates to zero pages, e.g. image-only/cover content) or a target equal to where we
            // already are pushes nothing onto the back-stack and arms no ↩ — mirroring jumpToAnchor
            // (6d822a3), which pushes only after its target resolves, for the same reason.
            if (target != null && target != origin) {
                jumpStack.push(origin)
                updateBackControl()
                showPage(target)
                session.drainPending()?.let { persistPosition(it) }
            }
        }
    }

    /** Pops one jump and navigates there, UNDER the still-open chrome — like a scrub commit, not a
     *  Contents jump. No fullRefresh (the overlay is open; the clean refresh lands when it closes)
     *  and no closeOverlay (so repeated taps walk back). Does NOT push — back is one-way. */
    private fun onBackJump() {
        val target = jumpStack.pop() ?: return
        showPage(target)
        session.drainPending()?.let { persistPosition(it) }
        updateBackControl()
    }

    private fun updateBackControl() {
        scrubberBackView.visibility = if (jumpStack.isEmpty) View.GONE else View.VISIBLE
    }

    /**
     * Returns to where the scrub began and writes nothing. Called when the overlay is dismissed or
     * Back is pressed with a scrub in flight.
     */
    private fun abandonScrub() {
        val origin = scrubOrigin ?: return
        scrubJob?.cancel()
        scrubOrigin = null
        scrubPreview.visibility = View.GONE
        scrubPreview.setImageDrawable(null)
        shownPreviewEntry = null
        // Restore the readout to the page actually on screen; the page itself never moved.
        showPage(origin)
    }

    /**
     * Turns a [BookLocation] into a real [ReadingState], paginating the chapter (off the main thread)
     * only now, on commit — never during the drag. `EpubDocument.chapter()` memoises per
     * `(config, spineIndex)`, so a chapter already visited resolves from cache.
     */
    private fun resolveScrubTarget(located: BookLocation): ReadingState? {
        val doc = document ?: return null
        val cfg = config ?: return null
        val pageCount = doc.chapter(located.spineIndex, cfg).pages.size
        if (pageCount == 0) return null
        val pageIndex = ((pageCount - 1) * located.fractionWithinChapter).roundToInt()
            .coerceIn(0, pageCount - 1)
        return ReadingState(located.spineIndex, pageIndex)
    }

    // -- Scrub test seams -------------------------------------------------------------------------
    // The scrub commit render is a background coroutine with no other observable production surface,
    // so these read-only hooks let ReaderActivityTest wait for it and assert its no-preview contract
    // without widening the production API. None is called in production.

    /** True when no commit render is in flight — a test waits on this after a commit or an abandon. */
    internal val scrubIdleForTest: Boolean get() = scrubJob?.isActive != true

    /** The reader's current position — a test's "did the page actually move" probe. */
    internal val currentStateForTest: ReadingState get() = state

    /** Drives the overlay-hide/Back abandon path directly, without a real touch dispatch. */
    internal fun abandonScrubForTest() = abandonScrub()

    // -- Preview-strip test seams -------------------------------------------------------------------
    // Strip GENERATION is Task 6; until then a test that needs the preview window to actually show
    // must generate a strip itself, against the Activity's own RenderConfig, then re-run the load
    // that normally only happens once, at open. These three seams are exactly that path.

    /** The Activity's own resolved RenderConfig for this open — what a test must generate a strip
     *  against for [previewStrip] to recognize it as a match. */
    internal val configForTest: RenderConfig? get() = config

    /** Re-runs the strip load [openFirstBook] does once, e.g. after a test has generated a strip for
     *  this exact (book, config) on disk after the fact. Not called in production. */
    internal fun loadPreviewStripForTest() {
        val file = bookPath?.let(::File) ?: return
        val cfg = config ?: return
        lifecycleScope.launch {
            previewStrip = withContext(Dispatchers.IO) { stripStore.stripFor(file, cfg) }
        }
    }

    /** True once [loadPreviewStripForTest] (or the real open-time load) has found a strip. */
    internal val previewStripLoadedForTest: Boolean get() = previewStrip != null

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
        val target = chapterToPrefetch(state, chapterPageCount, doc.spineSize, cfg.columnCount) ?: return
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

    private fun showMessage(@StringRes message: Int) = showMessage(getString(message))

    /**
     * Reports a failure to the reader and sends [cause] to the log.
     *
     * These used to interpolate `e.message ?: e.javaClass.simpleName` straight into the toast, so a
     * damaged book produced "Couldn't open this book: Not a readable EPUB archive: error in opening
     * zip file", and anything without a message produced a bare "NullPointerException". The reader
     * can act on neither. The throwable is genuinely useful to whoever is debugging, so it goes
     * where debugging happens instead.
     */
    private fun showError(@StringRes message: Int, cause: Throwable) {
        Log.w(TAG, getString(message), cause)
        showMessage(getString(message))
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
        private const val TAG = "ReaderActivity"

        /** String extra: an absolute book path, set by [LibraryActivity] when opening a tap. */
        const val EXTRA_BOOK_PATH = "dev.reader.ui.EXTRA_BOOK_PATH"
    }
}
