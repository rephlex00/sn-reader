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
import androidx.core.content.res.ResourcesCompat
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
import dev.reader.formats.BookException
import dev.reader.formats.ReflowableDocument
import dev.reader.formats.ReflowableDocuments
import dev.reader.formats.PaginatedChapter
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
 * The overlay's read-only page readout: `page X of Y · P%`, where X is 1-based ([pageIndex] + 1)
 * and chapter-relative, Y is [pageCount], and P is the whole-book percentage. The old tail — "N
 * left in chapter" — restated X of Y in different words; the book percentage says something the
 * rest of the line does not (and matches the drag readout's `chapter · P%` shape). A pure function
 * of its three ints — the testable seam behind the scrubber, keeping the string out of the View.
 * [pageIndex] is 0-based and expected in `0 until pageCount`.
 */
internal fun scrubberText(pageIndex: Int, pageCount: Int, bookPercent: Int): String =
    "page ${pageIndex + 1} of $pageCount · $bookPercent%"

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

    /** The right-hand side of the running head: the chapter you are in, in tracked caps. Kept in
     *  step with the page's own foot — see [showPage]. */
    private lateinit var runningChapterView: TextView

    /**
     * The chrome's mark ribbon: outlined when the page on screen carries no mark, flooded when it
     * does. It is the one control on that bar whose appearance is a FACT about the page rather than
     * a door, which is why it is a glyph where CONTENTS is a word — a glyph can hold a state.
     *
     * Its state comes from [BookmarksPanel] (see [showMarkState]), so the ribbon and the panel's own
     * "Mark this page" cell can never disagree about the same page.
     */
    private lateinit var bookmarkButton: ImageView
    private lateinit var scrubberView: TextView

    /** The last text [setRestingReadout] wrote to [scrubberView] — the "page X of Y · N left in
     *  chapter" line, or the no-text-fallback. Restored onto [scrubberView] once strip generation's
     *  transient "preparing previews · N%" readout (scheduleStripGeneration's onChapterDone) is
     *  done overwriting it. Drag readouts ([onScrubMoved]) deliberately bypass this — they own the
     *  readout only for the gesture's duration and never touch what it should read at rest. */
    private var restingReadout: CharSequence = ""

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

    /** The window around [scrubPreview] — the framed preview plus its reversed caption bar naming
     *  the chapter and page it shows. Visibility lives here, not on the ImageView: the caption is
     *  part of the window, and previously the reader had to read the separate readout to know what
     *  the bare thumbnail was of. */
    private lateinit var scrubPreviewFrame: View
    private lateinit var scrubPreviewChapter: TextView
    private lateinit var scrubPreviewPage: TextView

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

    /** Increments on every scheduled generation; a callback carrying a stale token is a superseded
     *  run's queued main-thread post and must not repopulate [generatedChapters]. */
    private var stripGenerationToken: Int = 0

    /** The entry currently blitted, to skip redundant decodes as the finger dithers in place. */
    private var shownPreviewEntry: StripEntry? = null

    /**
     * Spine chapters whose thumbnail(s) already exist — pushed to [chapterScrubber] so the track
     * lights up chapter-by-chapter as generation runs, rather than only once the whole strip lands.
     * Seeded from [previewStrip] when one is already on disk at open ([openFirstBook]); rebuilt from
     * empty at the start of each [scheduleStripGeneration] run (a fresh generation starts all-dashed)
     * and grown live by its `onChapterDone` callback.
     */
    private val generatedChapters = mutableSetOf<Int>()

    /**
     * The Aa typography sheet — a visibility-toggled panel inside [overlay], opened by the Aa button.
     * Holds no timer or animation: showing/hiding is one `visibility` flip. Each of its controls
     * writes a [ReaderPrefs] field then live-re-paginates the current chapter via
     * [applySettingsChange], keeping the reader on the same text across the reflow.
     */
    private lateinit var settingsSheet: View
    private lateinit var settings: SettingsSheet

    /**
     * **Back matter** — the reader's other surface, holding chapters, marks and notes behind one
     * segmented header. These were three separate panels with three toolbar entries, three headers
     * and a dismiss that moved between them; they are all answers to "where am I in this book", so
     * they are now one surface the reader learns once. See [BackMatterPanel].
     *
     * Like [settingsSheet] it holds no timer or animation: showing and hiding is one `visibility`
     * flip. The three bodies below still own their own lists, adapters, jumps and database work —
     * only the shell moved.
     */
    private lateinit var backMatter: BackMatterPanel

    /** Chapters. Tapping an entry jumps via the same restore machinery the open path uses. */
    private lateinit var toc: TocPanel

    /** Marks. Owns its list, its "Mark this page" cell and every database call it makes. */
    private lateinit var bookmarks: BookmarksPanel

    /**
     * Notes. Unlike marks it has no add control — highlighting happens on the page with the pen
     * (see [onStylusTap]/[onStylusDrag]/[commitHighlight]). Its list is loaded once per open,
     * never by a standing observer.
     */
    private lateinit var highlights: HighlightsController

    private var document: ReflowableDocument? = null
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

    /** Mirrors [ReaderPrefs.previewsEnabled], read once at open and kept current by the Aa toggle —
     *  so the drag hot path never constructs [ReaderPrefs]. */
    private var previewsEnabled: Boolean = true

    /** The one in-flight preview decode, cancelled by the next move so a fast sweep never queues
     *  a backlog of decodes for entries the finger has already passed. */
    private var previewDecodeJob: Job? = null

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
    private val jumpStack = JumpStack<ReadingState>()

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
        runningChapterView = overlay.findViewById(R.id.running_chapter)
        // Assigned before BookmarksPanel is constructed below: that panel's onMarkedChanged writes
        // straight to this view.
        bookmarkButton = overlay.findViewById(R.id.bookmark_button)
        applyChromeOrientation()
        scrubberView = overlay.findViewById(R.id.scrubber)
        // Literata + tabular numerals: the readout's digits (page counts, percentages) must not
        // shift width as they change, and the XML's default sans doesn't carry tabular figures.
        // 16sp/black stay as set in overlay_reader.xml — only the face and figure style change here.
        scrubberView.typeface = ResourcesCompat.getFont(this, R.font.literata)
        scrubberView.fontFeatureSettings = "tnum"
        chapterScrubber = overlay.findViewById(R.id.chapter_scrubber)
        scrubberBackView = overlay.findViewById(R.id.scrubber_back)
        scrubberBackView.setOnClickListener { onBackJump() }
        scrubPreview = overlay.findViewById(R.id.scrub_preview)
        scrubPreviewFrame = overlay.findViewById(R.id.scrub_preview_frame)
        scrubPreviewChapter = overlay.findViewById(R.id.scrub_preview_chapter)
        scrubPreviewPage = overlay.findViewById(R.id.scrub_preview_page)
        // The trusted-lift grammar can leave a scrub ARMED (a light drag's lift is never obeyed —
        // the panel fabricates lifts for light contacts). Tapping the floating preview is the
        // explicit "go there": a no-op in every other state.
        scrubPreviewFrame.setOnClickListener { chapterScrubber.commitArmed() }
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
        // The obscured-inset sync (see syncObscuredInsets) needs post-layout heights, and a view
        // flipped VISIBLE is only measured in the traversal that follows — so the three surfaces
        // whose heights matter each re-sync on layout. The GONE direction never lays out, which is
        // why the hide paths below all call syncObscuredInsets() explicitly instead.
        val syncOnLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncObscuredInsets() }
        overlay.findViewById<View>(R.id.reader_chrome_top).addOnLayoutChangeListener(syncOnLayout)
        overlay.findViewById<View>(R.id.reader_chrome_bottom).addOnLayoutChangeListener(syncOnLayout)
        settingsSheet.addOnLayoutChangeListener(syncOnLayout)
        settings = SettingsSheet(overlay, settingsHost) { ReaderPrefs(this) }
        // The three back-matter bodies report emptiness up to the shared empty state rather than
        // each carrying one — see BackMatterPanel.onBodyEmpty.
        toc = TocPanel(overlay, readerSurface) { empty ->
            backMatter.onBodyEmpty(BackMatterPanel.Segment.CHAPTERS, empty)
        }
        bookmarks = BookmarksPanel(
            overlay, readerSurface, lifecycleScope,
            database.bookmarkDao(), database.bookDao(),
            onBookmarksChanged = ::refreshScrubberBookmarks,
            onEmpty = { empty -> backMatter.onBodyEmpty(BackMatterPanel.Segment.MARKS, empty) },
            onMarkedChanged = ::showMarkState,
        )
        highlights = HighlightsController(
            overlay, container, pageView, readerSurface, lifecycleScope,
            database.highlightDao(), database.bookDao(),
            onEmpty = { empty -> backMatter.onBodyEmpty(BackMatterPanel.Segment.NOTES, empty) },
        )
        backMatter = BackMatterPanel(
            overlay, toc, bookmarks, highlights,
            prefs = { ReaderPrefs(this) },
            bookKey = { intent.getStringExtra(EXTRA_BOOK_PATH).orEmpty() },
            onDismiss = {
                backMatter.hide()
                syncObscuredInsets()
            },
        )
        overlay.findViewById<View>(R.id.back).setOnClickListener { exitToLibrary() }
        overlay.findViewById<View>(R.id.contents_button).setOnClickListener { toggleBackMatter() }
        overlay.findViewById<View>(R.id.settings_button).setOnClickListener { toggleSettings() }
        bookmarkButton.setOnClickListener { bookmarks.toggleCurrentPageMark() }
        // The device has no hardware Back, so each surface carries its own ‹ at the screen margin,
        // in the same place on both — the same first step system Back takes. Closing a surface only
        // hides that layer; the bare overlay stays up (tap the page to return to reading).
        overlay.findViewById<View>(R.id.settings_close).setOnClickListener {
            settingsSheet.visibility = View.GONE
            syncObscuredInsets()
        }
        settings.wire()
        setContentView(container)

        // System Back is the reader's other way out (the Nomad's hardware/gesture Back does not
        // finish this Activity on its own). Additive — there is no onBackPressed override. Overlay
        // shown: Back only closes it. Overlay hidden: Back leaves the book, flushing position first.
        onBackPressedDispatcher.addCallback(this) {
            when {
                // Back matter and Type are layers inside the overlay: Back peels whichever is open
                // off first, then the overlay, then the book — one thing per press. Only one is ever
                // open at a time (opening either closes the other), so the order is a formality.
                backMatter.isVisible -> {
                    backMatter.hide()
                    syncObscuredInsets()
                }
                settingsSheet.visibility == View.VISIBLE -> {
                    settingsSheet.visibility = View.GONE
                    syncObscuredInsets()
                }
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
            // [spineIndex] itself has pages: it IS the first readable position — the interface's
            // documented contract ("the first readable position AT [spineIndex]..."). advance()
            // instead resolves a forward PAGE TURN from (spineIndex, 0), which for a chapter that
            // already has pages returns its SECOND page, not its first — every caller before the
            // scrub-commit snap path only ever invoked this once pageCountFor(spineIndex) was
            // already known to be 0 (see tocTarget/resolveStart), so that off-by-one landmine was
            // never tripped until a snapped commit called this directly on a non-empty chapter.
            if (pageCountFor(spineIndex) > 0) return ReadingState(spineIndex, 0)
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

        override fun applyTextSize(px: Float) = this@ReaderActivity.applyTextSize(px)

        override fun applyMarginPreset(presetPx: Int) = applyMargin(presetPx)

        override fun toggleProgressBar() = this@ReaderActivity.toggleProgressBar()

        override fun toggleRotationLock() = this@ReaderActivity.toggleRotationLock()

        override fun toggleFasterTurns() = this@ReaderActivity.toggleFasterTurns()

        override fun applyRefreshFrequency(pages: Int) = this@ReaderActivity.applyRefreshFrequency(pages)

        /**
         * Flips [ReaderPrefs.previewsEnabled]. Turning previews ON schedules generation if this book
         * has no strip yet (mirrors [openFirstBook]'s own gate); it never deletes a strip that
         * already exists, so a reader toggling off-then-on again gets the strip back instantly rather
         * than paying to regenerate it. Turning previews OFF hides the floating preview window
         * immediately — the dashing/solid track and the window are what "off" means to visit again,
         * and holding a stale blitted bitmap or entry across the toggle would be wrong the next time
         * a drag turns previews back on before a new one is chosen.
         */
        override fun togglePreviews() {
            val prefs = ReaderPrefs(this@ReaderActivity)
            val enabled = !prefs.previewsEnabled
            prefs.previewsEnabled = enabled
            previewsEnabled = enabled
            chapterScrubber.setGenerationStateVisible(enabled)
            if (enabled) {
                if (previewStrip == null) scheduleStripGeneration()
            } else {
                // Previews off: stop paying for them immediately. A generation in flight is 5-15s
                // of CPU producing thumbnails for a window that will not open — the pref was only
                // ever checked when generation was SCHEDULED, so without this the reader watched
                // the battery drain for a feature they had just switched off.
                //
                // Keep the handle (do NOT null it): cancel() is cooperative, so the coroutine can
                // still be running when previews are switched back on. scheduleStripGeneration's
                // own `previous?.cancelAndJoin()` is what actually waits for it to stop before a
                // new generator touches the same directory — nulling the field here would throw
                // that join away and let two generators race over one directory, exactly the
                // hazard scheduleStripGeneration's KDoc says the join exists to prevent.
                stripGenerationJob?.cancel()
                previewDecodeJob?.cancel()
                scrubPreviewFrame.visibility = View.GONE
                scrubPreview.setImageDrawable(null)
                shownPreviewEntry = null
            }
        }

        /**
         * Deletes every strip this book owns (see [PreviewStripStore.deleteStripsFor]) and clears the
         * in-memory mirror of it, pushing the empty set to the scrubber so the track goes back to
         * all-dashed. Does NOT flip [ReaderPrefs.previewsEnabled] or re-schedule generation — deleting
         * is a standalone "reclaim the disk space" action; a reader who wants it back opens the book
         * again (or the next config change re-triggers it).
         */
        override fun deletePreviewsForCurrentBook() {
            bookPath?.let { stripStore.deleteStripsFor(File(it)) }
            previewStrip = null
            previewDecodeJob?.cancel()
            shownPreviewEntry = null
            generatedChapters.clear()
            chapterScrubber.setGeneratedChapters(emptySet())
        }

        /** See [SettingsHost.previewGenerationProgress]: only meaningful mid-generation — previews
         *  off, or a strip already loaded (complete OR simply absent with nothing running), report
         *  null rather than a stale or zero count. */
        override fun previewGenerationProgress(): Pair<Int, Int>? {
            val doc = document ?: return null
            val prefs = ReaderPrefs(this@ReaderActivity)
            return if (prefs.previewsEnabled && previewStrip == null && stripGenerationJob?.isActive == true) {
                generatedChapters.size to doc.spineSize
            } else {
                null
            }
        }

        /** See [SettingsHost.hasPreviewsForCurrentBook]. [stripStore.stripFor] is a cheap on-disk
         *  index read; refresh() only runs on Aa-sheet open/control-change, never on the hot path,
         *  so paying for it here is fine. Covers the case where previews were toggled off mid-session
         *  ([previewStrip] nulled) but a strip still sits on disk to reclaim. */
        override fun hasPreviewsForCurrentBook(): Boolean {
            val file = bookPath?.let(::File) ?: return false
            val cfg = config ?: return false
            return previewStrip != null || stripGenerationJob?.isActive == true || stripStore.stripFor(file, cfg) != null
        }
    }

    /** The app's single Room database — the panels take the DAOs they need from it. */
    private val database get() = (application as ReaderApplication).database

    /** Whether the reading chrome is currently on screen. */
    private fun isOverlayVisible(): Boolean = overlay.visibility == View.VISIBLE

    /**
     * Tells [PageView] which bands the chrome covers, so it stops drawing the lines the chrome
     * slices (see [PageView.setObscuredInsets]) — the rule then meets air, not half glyphs.
     *
     * One function, several triggers: layout listeners on the top chrome, the bottom bar and the
     * Type sheet (visibility flips to VISIBLE, tab-height changes, rotation), plus explicit calls
     * on every hide path, because a view going GONE is never laid out.
     *
     * Back matter is deliberately ignored: it is a full-screen opaque surface, so re-clipping the
     * page under it would spend an invisible e-ink redraw. While it is open the last chrome insets
     * sit stale and harmless; they are correct again the moment it closes (its close path re-syncs).
     */
    private fun syncObscuredInsets() {
        if (!::backMatter.isInitialized) return // a layout pass can beat onCreate's panel wiring
        if (backMatter.isVisible) return
        val top = if (isOverlayVisible()) overlay.findViewById<View>(R.id.reader_chrome_top).height else 0
        val bottom = when {
            !isOverlayVisible() -> 0
            settingsSheet.visibility == View.VISIBLE -> settingsSheet.height
            else -> overlay.findViewById<View>(R.id.reader_chrome_bottom).height
        }
        pageView.setObscuredInsets(top, bottom)
    }

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
        // The ribbon states a fact about the page the reader is looking at, and pages turn while the
        // chrome is down — so it is re-read on the way up. One indexed DAO read; nothing paginates.
        bookmarks.refreshCurrentPageMark()
        // The bars' heights are only real after the traversal that makes them visible; the child
        // layout listeners don't reliably fire on a re-show with unchanged bounds, so the show path
        // syncs itself. Same frame as the reveal — one redraw, not two.
        overlay.doOnLayout { syncObscuredInsets() }
    }

    /** Draws the chrome's mark ribbon for the page on screen — flooded when marked, outlined when
     *  not. Called by [BookmarksPanel] whenever it learns the answer; never guessed at here. */
    private fun showMarkState(marked: Boolean) {
        bookmarkButton.setImageResource(
            if (marked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark,
        )
        bookmarkButton.contentDescription =
            getString(if (marked) R.string.bookmark_remove else R.string.bookmark_add)
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
        // A lift still sitting in its commit grace window when the overlay closes is resolved
        // synchronously first: a TRUSTED lift (crisp tap / firm drag) is a navigation the reader
        // already made and lands via onScrubCommit (which clears scrubOrigin); an untrusted one
        // arms, and the abandonScrub below then discards it quietly along with any session that
        // was already ARMED. No-op when no grace is open.
        chapterScrubber.flushPendingCommit()
        // A scrub still in flight when the overlay closes (Back, the toggle tap, a jump) is
        // abandoned first: the page never moved during the drag, so this just clears scrubOrigin
        // and re-syncs the readout/thumb. A no-op when no scrub is in flight.
        abandonScrub()
        settingsSheet.visibility = View.GONE
        backMatter.hide()
        // Returning to the page ends any pen selection that was in progress when the chrome went up:
        // a bracket armed before then is stale, and its marker would otherwise still be sitting on
        // the page waiting for a second tap the reader has long since moved on from.
        highlights.cancelPendingSelection()
        overlay.visibility = View.GONE
        // Before the refresh below, so the un-clipping and the clean flash land as ONE frame — a
        // hardware refresh does not redraw the view (see PageView.fullRefresh), only re-flashes it.
        syncObscuredInsets()
        pageView.epd.exitFastMode()
        if (cleanRefresh) {
            // One clean refresh on the way out, so the page the reader returns to is crisp rather
            // than carrying whatever ghosting fast mode accumulated while the chrome was up.
            pageView.fullRefresh()
            turnsSinceRefresh = 0
        }
    }

    /** Opens or closes Type — a visibility flip (one redraw). Opening first syncs its cells to the
     * current [ReaderPrefs] so it always shows the live values. */
    private fun toggleSettings() {
        highlights.hideDeleteChip() // a surface covers the page; the on-page chip must not float over it
        if (settingsSheet.visibility == View.VISIBLE) {
            settingsSheet.visibility = View.GONE
            syncObscuredInsets() // GONE is never laid out; drop the inset back to the bar's height
        } else {
            backMatter.hide() // one surface open at a time
            settings.refresh()
            settingsSheet.visibility = View.VISIBLE
        }
    }

    /**
     * Opens or closes back matter — a visibility flip (one redraw). Opening lands on whichever
     * segment this book was left on and rebuilds that list; the reader chooses among chapters,
     * marks and notes from inside the surface rather than from three toolbar entries.
     */
    private fun toggleBackMatter() {
        highlights.hideDeleteChip() // a surface covers the page; the on-page chip must not float over it
        if (backMatter.isVisible) {
            backMatter.hide()
            syncObscuredInsets()
        } else {
            settingsSheet.visibility = View.GONE // one surface open at a time
            backMatter.open()
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

    /**
     * The reader's exact position right now: the char offset at the top of the page on screen, which
     * is the anchor a re-pagination preserves. Null before a page has been drawn.
     */
    private fun currentLocator(): Locator? {
        val doc = document ?: return null
        val cfg = config ?: return null
        val page = doc.chapter(state.spineIndex, cfg).pages.getOrNull(state.pageIndex) ?: return null
        return Locator(state.spineIndex, page.startOffset)
    }

    /** The char offset at the top of the page on screen — the anchor a re-pagination preserves. */
    internal fun currentTopOffsetForTest(): Locator? = currentLocator()

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
    /**
     * Sets the text size to one of the five steps.
     *
     * Was a ±2px stepper with a "36px" readout. A tap that lands on the size already in use costs
     * a refresh rather than a re-paginate — the cells are a direct choice now, so re-selecting the
     * chosen one is a no-op the reader can perform freely.
     */
    /**
     * Folds the reader's two chrome rows into one when the panel is wider than it is tall.
     *
     * 1872px fits the ‹, the title, the chapter and every control on a single row, so landscape gets
     * the second row back as page. The chapter has a wide-mode twin in the top row; only one of the
     * pair is ever visible, and [showPage] writes both so neither can go stale. The title needs no
     * twin — it lives in the top row in both orientations, and its weight is what pushes the
     * controls to the right margin either way.
     */
    private fun applyChromeOrientation() {
        val singleRow = resources.getBoolean(R.bool.chrome_single_row)
        val wide = if (singleRow) View.VISIBLE else View.GONE
        // The running-head row itself collapses. Set here rather than left to a values-land dimen:
        // the layout's height was resolved once at inflate, and this Activity is never re-inflated
        // across a rotation, so a qualified dimension alone would only ever apply to whichever
        // orientation the reader happened to open in.
        overlay.findViewById<View>(R.id.chrome_running_head).visibility =
            if (singleRow) View.GONE else View.VISIBLE
        overlay.findViewById<View>(R.id.running_chapter_wide).visibility = wide
        overlay.findViewById<View>(R.id.chrome_wide_divider).visibility = wide
    }

    private fun applyTextSize(px: Float) {
        val current = ReaderPrefs(this).textSizePx
        val next = px.coerceIn(TEXT_SIZE_MIN_PX, TEXT_SIZE_MAX_PX)
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
            // The strip is keyed to the typography; a visual change makes the loaded one stale.
            // Drop the in-memory handle, then reload from disk — a config the reader has used
            // before (stepping text size back down, toggling justify off and on) usually still has
            // its strip sitting there. Only a genuine miss schedules generation, exactly as
            // openFirstBook does.
            //
            // A generation for the ABANDONED config may still be in flight, captured on its own
            // cfg. Its completion runs a sibling sweep (see generate's KDoc) that deletes every
            // OTHER config's strip — including the one about to be reloaded below — and then
            // reassigns previewStrip to ITS config, stomping this reload. A bare cancel() only
            // narrows that race: cancellation is cooperative, and generate()'s tail (writing the
            // index, then the sibling sweep) runs with no suspension point to catch it. So this
            // cancels AND joins before trusting the reload, exactly as scheduleStripGeneration's
            // own supersede path does for the same reason.
            //
            // Keep the handle (do NOT null it): the join above is only awaited once this coroutine
            // resumes, and the main thread is free to accept taps for the whole 0.1-2s it can take
            // a cooperative cancellation to land. A second settings change (or a previews-off/on
            // pair) landing in that window must see the SAME job here that this coroutine is
            // waiting on — nulling it would make that next caller read `previous = null`, skip its
            // own join, and start a second generator while this one's cancelled-but-still-running
            // tail can still delete the directory the new one just created. Exactly the hazard
            // togglePreviews' own KDoc documents for the same field.
            previewStrip = null
            shownPreviewEntry = null
            val previous = stripGenerationJob
            lifecycleScope.launch {
                previous?.cancelAndJoin()
                try {
                    val path = bookPath
                    val reloaded = if (path != null) {
                        withContext(Dispatchers.IO) { stripStore.stripFor(File(path), newConfig) }
                    } else {
                        null
                    }
                    if (reloaded != null) {
                        previewStrip = reloaded
                        generatedChapters.clear()
                        generatedChapters.addAll(generatedChaptersOf(reloaded))
                        chapterScrubber.setGeneratedChapters(generatedChapters.toSet())
                    } else {
                        scheduleStripGeneration()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed reload degrades to no preview window — never to a crashed reader,
                    // exactly as scheduleStripGeneration's own identical guard states. stripFor is
                    // a TOCTOU disk read (isFile then readText); a concurrent sweep or eviction
                    // deleting the index between those two lines throws here, and this used to run
                    // uncaught inside this launch, outside applySettingsChange's own try/catch.
                    Log.w("Reader", "preview strip reload failed", e)
                }
            }
            val newPageIndex = reflowedPageIndex(oldPages, state.pageIndex, newPages)
            state = ReadingState(state.spineIndex, newPageIndex)
            showPage(state)
            flushPosition()
            settings.refresh()
        } catch (e: BookException) {
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
        // The chrome folds to one row in landscape and back to two in portrait. This Activity
        // declares configChanges for orientation (it re-paginates rather than reopening the book),
        // so onCreate does not run again and this is the only place the chrome learns it rotated.
        applyChromeOrientation()
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
        // Both arguments, exactly as showPage passes them: the tick comes from chapterWeights
        // alone — no pagination, no new state — so omitting it only meant the bar appeared
        // incomplete until the next turn.
        pageView.setProgress(
            fraction,
            if (showProgressBar) chapterEndFraction(chapterWeights, state.spineIndex) else null,
        )
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
        // onPause gives the panel's screen mode back unconditionally (it is device-wide state that
        // must never leak), so a resume with the chrome still open would otherwise run every
        // subsequent chrome interaction on the slow, full-quality waveform until the overlay was
        // closed and reopened. Idempotent: enterFastMode no-ops when already held.
        if (isOverlayVisible()) pageView.epd.enterFastMode()
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
        // A lift still inside its commit grace window when the app is backgrounded (Home, app
        // switcher, an incoming call) is a committed navigation — flush it now rather than let the
        // process pause with it unresolved. chapterScrubber may not exist yet if onPause somehow
        // races initialization; guard defensively.
        if (::chapterScrubber.isInitialized) chapterScrubber.flushPendingCommit()
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
            var opened: ReflowableDocument? = null
            try {
                val explicitPath = intent.getStringExtra(EXTRA_BOOK_PATH)
                val file = withContext(Dispatchers.IO) {
                    if (explicitPath != null) File(explicitPath).takeIf { it.isFile } else findFirstBook()
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
                        showMessage(R.string.error_no_book_found)
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
                previewsEnabled = ReaderPrefs(this@ReaderActivity).previewsEnabled
                bookPath = file.path
                navigator = PageNavigator(doc.spineSize)
                pageView.onTap = ::onTap

                // The overlay title: the book's own metadata title. The parser already substitutes
                // "Untitled" for a missing <dc:title>, and the library grid shows that same value —
                // so we deliberately do NOT override it with the filename here (that would make the
                // reader disagree with the library). The filename is only a defensive fallback for
                // the currently-unreachable case of a blank title slipping through.
                titleView.text = displayTitle(
                    doc.metadata.title.takeIf { it.isNotBlank() }
                        ?: File(file.path).nameWithoutExtension,
                )
                // Both surfaces name the book they belong to. On a device with four books half-read
                // that matters more than a panel title repeating its own name back at you.
                backMatter.setBookTitle(titleView.text.toString())

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
                    setRestingReadout(getString(R.string.error_no_text_short))
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
                    // Push the previews-on/off state and the generated-chapters set (derived from the
                    // strip just loaded, if any) to the scrubber, so a book reopened with a complete
                    // strip already on disk shows its track solid from the first frame rather than
                    // dashed until some later event repaints it.
                    chapterScrubber.setGenerationStateVisible(ReaderPrefs(this@ReaderActivity).previewsEnabled)
                    previewStrip?.let {
                        generatedChapters.clear()
                        generatedChapters.addAll(generatedChaptersOf(it))
                        chapterScrubber.setGeneratedChapters(generatedChapters.toSet())
                    }
                    // No valid strip for this (book, config): schedule the one-shot background
                    // generation (itself gated on previewsEnabled). Absent until it completes, the
                    // preview window simply never shows — the correct, non-crashing fallback.
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
            } catch (e: BookException) {
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
    protected open fun openDocument(file: File): ReflowableDocument = ReflowableDocuments.open(
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
        if (!ReaderPrefs(this).previewsEnabled) return // off: no generation to schedule
        val file = bookPath?.let(::File) ?: return
        val cfg = config ?: return
        val previous = stripGenerationJob
        // A fresh generation starts from empty — every chapter draws dashed until onChapterDone
        // says otherwise, even if a stale set survived from a superseded run.
        generatedChapters.clear()
        chapterScrubber.setGeneratedChapters(emptySet())
        // Captured before the launch: a superseded run's per-chapter callback closes over ITS OWN
        // token, so it can tell — even after landing on the main thread well after this run was
        // abandoned — that [stripGenerationToken] has since moved on and it must not touch
        // [generatedChapters].
        val token = ++stripGenerationToken
        stripGenerationJob = lifecycleScope.launch {
            // Wait for any prior generator to fully stop before regenerating — see KDoc above.
            previous?.cancelAndJoin()
            try {
                stripStore.generate(file, cfg) { spineIndex ->
                    // Fires on Dispatchers.Default (see generate's KDoc); marshal to the main thread
                    // before touching the Activity's fields or any View.
                    runOnUiThread {
                        // A superseded run's callback, queued before the join above returned and
                        // landing only now: the token it closed over no longer matches, so this is
                        // stale data for a config nobody wants anymore — discard it rather than
                        // repopulating generatedChapters out from under the run that superseded it.
                        if (token != stripGenerationToken) return@runOnUiThread
                        generatedChapters.add(spineIndex)
                        chapterScrubber.setGeneratedChapters(generatedChapters.toSet())
                        settings.refresh() // live "N / M chapters" readout, a no-op while the sheet is closed
                        // Mirror the same progress into the resting readout — but only at rest: mid-drag
                        // the readout belongs to the finger (chapter + percent, onScrubMoved), and this
                        // per-chapter callback must not steal it out from under an in-flight gesture.
                        if (scrubOrigin == null) {
                            val spineSize = (document?.spineSize ?: 1).coerceAtLeast(1)
                            scrubberView.text = getString(
                                R.string.previews_preparing,
                                generatedChapters.size * 100 / spineSize,
                            )
                        }
                    }
                }
                // IO, not Main: this stats every file under the previews root and can delete whole
                // strip directories. The stripFor call below already knew that; this line didn't.
                withContext(Dispatchers.IO) { stripStore.evictOverBudget(keep = file) }
                previewStrip = withContext(Dispatchers.IO) { stripStore.stripFor(file, cfg) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed generation degrades to no preview window — never to a crashed reader.
                Log.w("Reader", "preview strip generation failed", e)
            } finally {
                // The per-chapter callback wrote a transient "preparing previews · N%" into the
                // readout. On the failure path nothing used to take it back, so it sat there until
                // the next page turn happened to call setRestingReadout. Gated on not-mid-drag, same
                // as every other write to this line, AND on this run still being the current one —
                // a superseded run's finally must not stomp the readout the run that replaced it
                // (or its own failure/success path) already owns.
                if (token == stripGenerationToken && scrubOrigin == null) {
                    scrubberView.text = restingReadout
                }
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
            // progressFor paginates a chapter on its first lazy read, so a bookmark anchored in a
            // chapter that fails to read throws here — and this pass runs at EVERY open, so the
            // throw crashed the book permanently (the row persists). Glyph placement is decoration:
            // a bookmark that cannot be located precisely falls back to the whole-book fraction
            // captured when it was saved, and the reader opens either way.
            val fractions = marks.map { mark ->
                try {
                    readerSurface.progressFor(mark.spineIndex, mark.charOffset)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mark.progressFraction
                }
            }
            chapterScrubber.setBookmarks(fractions)
        }
    }

    /**
     * The standalone-launch fallback (no [EXTRA_BOOK_PATH] on the intent): the first reflowable
     * book under /Document, in either format. `protected open` purely as a test seam —
     * [ReaderActivityTest] substitutes it to exercise the fallback path without a real /Document
     * tree.
     */
    protected open fun findFirstBook(): File? = try {
        val documents = File(Environment.getExternalStorageDirectory(), "Document")
        documents.walkTopDown()
            .maxDepth(10) // Closes an unbounded symlink-loop walk; matches LibraryIndexer.walk().
            .filter { it.isFile && it.extension.lowercase() in READABLE_EXTENSIONS }
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
        } catch (e: BookException) {
            showError(R.string.error_turn_page, e)
        } catch (e: Exception) {
            // Mirrors openFirstBook's defense-in-depth catch: chapter() is documented to throw
            // only EpubException, but that promise is only as good as every path inside the
            // format parsers honouring it. A malformed book must never crash the app here either.
            showError(R.string.error_turn_page, e)
        }
    }

    /**
     * Sets [scrubberView]'s text AND remembers it as [restingReadout] — the resting page readout
     * ("page X of Y · P%", or the no-text fallback), as opposed to a transient
     * readout (strip-generation progress, a drag position) that must eventually give the line back.
     * Every call site that sets the RESTING readout goes through this instead of writing
     * `scrubberView.text` directly, so scheduleStripGeneration knows what to restore once its own
     * "preparing previews · N%" readout is done overwriting it.
     */
    private fun setRestingReadout(text: CharSequence) {
        restingReadout = text
        scrubberView.text = text
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
        // Keep the overlay's read-only readout current with the page just shown, so it is right the
        // next time the overlay opens. Sits AFTER currentBookProgress is computed — the readout's
        // percentage must be the fraction of the page just drawn, not the one before it.
        setRestingReadout(
            scrubberText(
                pageIndex,
                chapter.pages.size,
                (currentBookProgress.coerceIn(0f, 1f) * 100).roundToInt(),
            ),
        )
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
        val chapterTitle = chapterTitleFor(doc.toc, next.spineIndex)
        // The chrome's running head. Set on the same turn as the foot so the two never disagree,
        // and blank rather than stale when the TOC names nothing for this chapter. On a chapter
        // OPENER it stands down entirely — the page carries its own heading directly beneath the
        // chrome, and the running head repeating it 40dp above was saying the same thing twice
        // (print omits running heads on chapter openers for the same reason).
        val headText = if (pageIndex == 0) "" else chapterTitle.orEmpty()
        runningChapterView.text = headText
        overlay.findViewById<TextView>(R.id.running_chapter_wide).text = headText
        if (resources.getBoolean(R.bool.chrome_single_row)) {
            // Landscape: the wide slot and its divider disappear with the text, rather than leave
            // a divider floating beside nothing. applyChromeOrientation re-seats both on rotation.
            val wide = if (headText.isEmpty()) View.GONE else View.VISIBLE
            overlay.findViewById<View>(R.id.running_chapter_wide).visibility = wide
            overlay.findViewById<View>(R.id.chrome_wide_divider).visibility = wide
        }
        pageView.setRunningFoot(
            chapterTitle,
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
     * the book page does not repaint until the finger lifts.
     *
     * [snappedChapter] (from [ChapterScrubberView]'s detent) takes priority when present, for both
     * halves of this function: the readout names the chapter it snapped to (via [chapterTitleFor]),
     * rather than whatever chapter the raw fraction resolves to, and the preview blits that chapter's
     * OPENING thumbnail ([entryForChapterOpening]) rather than the nearest sampled page — showing the
     * finger the chapter it is about to land on, not a boundary page one drag-pixel either side of it.
     * Unsnapped, both fall back to the prior fraction-based lookups ([locateByFraction]/[nearestEntry]).
     */
    private fun onScrubMoved(fraction: Float, snappedChapter: Int?) {
        val doc = document ?: return
        val title = if (snappedChapter != null) {
            chapterTitleFor(doc.toc, snappedChapter)
        } else {
            chapterTitleFor(doc.toc, locateByFraction(chapterWeights, fraction).spineIndex)
        }
        scrubberView.text = getString(
            R.string.scrubber_position,
            title.orEmpty(),
            (fraction.coerceIn(0f, 1f) * 100).roundToInt(),
        )

        // Previews off: the readout above is the whole story; the window stays GONE (wherever it
        // already was — togglePreviews hides it the moment previews go off) and no disk is touched.
        // Reads the mirrored field, never ReaderPrefs: this runs on every ACTION_MOVE.
        if (!previewsEnabled) return

        // The preview blit: the snapped chapter's opening thumbnail, or (unsnapped) the nearest
        // sampled page, decoded off disk. No strip -> no window; the readout above already carries
        // chapter + percent. Never paginates, never touches the page.
        val strip = previewStrip
        val bookFile = bookPath?.let(::File)
        val cfg = config
        if (strip != null && bookFile != null && cfg != null) {
            val entry = if (snappedChapter != null) {
                entryForChapterOpening(strip.entries, snappedChapter)
            } else {
                nearestEntry(strip.entries, fraction)
            }
            if (entry == null) {
                // A snapped chapter with no sampled opening (e.g. a zero-page image chapter never
                // entered the plan): hide rather than leave a mismatched thumbnail from a moment ago.
                scrubPreview.setImageDrawable(null)
                scrubPreviewFrame.visibility = View.GONE
                shownPreviewEntry = null
            } else if (entry != shownPreviewEntry) {
                shownPreviewEntry = entry            // mark attempted either way — no re-decode churn
                // Off the main thread: this is a ~702x936 WEBP decoding to ~2.6MB, and it used to
                // run inline in the touch handler on every entry the finger crossed. cancel() here
                // is cooperative — it cannot interrupt a decodeFile() already running on the IO
                // pool, so during a fast sweep, decodes already dispatched before the cancel still
                // run to completion in parallel (N concurrent ~2.6MB allocations are still
                // possible). What it actually buys: a decode not yet dispatched is skipped
                // entirely, and — via the shownPreviewEntry check below — the paint of any decode
                // that does finish late is suppressed either way.
                previewDecodeJob?.cancel()
                val thumbnail = stripStore.thumbnailFile(bookFile, cfg, entry)
                previewDecodeJob = lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        android.graphics.BitmapFactory.decodeFile(thumbnail.path)
                    }
                    // A newer move may have superseded this decode while it was in flight; only
                    // paint if this is still the entry the finger is on.
                    if (shownPreviewEntry != entry) return@launch
                    if (bmp != null) {
                        scrubPreview.setImageBitmap(bmp)
                        // The caption names what the window is showing. Without it the reader has
                        // to look away to the readout to find out which page the thumbnail is —
                        // two things that belong together, so they now sit together.
                        //
                        // The design asks for "page 3 of 12" here. It says percentage instead,
                        // deliberately: a page count means paginating that chapter
                        // (ReaderSurface.pageCountFor -> doc.chapter(...)), which is real work in
                        // the middle of a gesture, and this reader does no work during a drag. The
                        // fraction is already in hand from the scrub itself and costs nothing.
                        scrubPreviewChapter.text =
                            chapterTitleFor(document?.toc.orEmpty(), entry.spineIndex).orEmpty()
                        scrubPreviewPage.text =
                            getString(R.string.scrub_preview_position, (entry.fraction * 100).toInt())
                        scrubPreviewFrame.visibility = View.VISIBLE
                    } else {
                        // A missing/corrupt thumbnail: hide rather than leave a wrong page showing.
                        scrubPreview.setImageDrawable(null)
                        scrubPreviewFrame.visibility = View.GONE
                    }
                }
            }
        }
    }

    /**
     * Lift-off: this is the only path that renders a page from a scrub. Paginate the selected chapter
     * off the main thread, show the page, persist it, and clear the scrub. One clean refresh, because
     * the page has not been drawn since the drag began.
     *
     * [snappedChapter], when present, resolves through [ReaderSurface.firstNonEmptyFrom] straight to
     * that chapter's FIRST page — the fix for the reported bug: resolving even a snapped commit
     * through the boundary fraction ([resolveScrubTarget]) could land one page into the PREVIOUS
     * chapter, since the fraction the thumb snapped to is the chapter's start, and a `roundToInt`
     * against the previous chapter's page count can round up to its last page. Unsnapped commits are
     * unaffected: they still resolve through the raw fraction, same as before.
     */
    private fun onScrubCommitted(fraction: Float, snappedChapter: Int?) {
        scrubJob?.cancel()
        // The drag is over — any preview decode still in flight for the last-crossed entry would
        // otherwise run to completion only to be thrown away (the bridge below already shows that
        // entry's bitmap; nothing new needs painting). Cancel it now, freeing the IO thread right
        // when the commit's own pagination could use it.
        previewDecodeJob?.cancel()
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
        // Durable down-payment on the commit. The exact landing page is only known after the
        // coroutine's pagination below — but that coroutine rides lifecycleScope, so a lift
        // followed within the grace by a fast teardown (double-Back, swipe-away; onPause flushes
        // the grace straight into this call) cancels it before showPage/persist ever run, silently
        // discarding a navigation the reader committed. When the commit changes CHAPTER — knowable
        // synchronously from byte weights alone, no pagination — write (chapter, offset 0) through
        // the app-scoped writer now, and let the coroutine refine it to the exact page. Death
        // mid-commit then reopens at the committed chapter's start instead of quietly reverting to
        // the origin. Same-chapter commits skip the down-payment: the stored position is already
        // inside the right chapter, and a coarse offset-0 write would WORSEN a rare death there
        // (chapter start vs the exact page already stored). The `finally` below honours this same
        // contract for an Activity teardown, not just a process death: it never repairs a
        // teardown-driven cancellation back to the origin, because lift-off is a commitment.
        val coarseChapter = snappedChapter ?: locateByFraction(chapterWeights, fraction).spineIndex
        val downPaymentWritten = coarseChapter != origin.spineIndex
        // Captured BEFORE the down-payment overwrites the stored row, so a commit that never
        // resolves can put back exactly what was there. currentLocator() reads the already-cached
        // current chapter, so this costs nothing.
        val originLocator = if (downPaymentWritten) currentLocator() else null
        if (downPaymentWritten) {
            persistPosition(Locator(coarseChapter, 0))
        }
        // The preview window deliberately STAYS UP through the commit. Lift-off starts a
        // ~230-360ms off-main-thread pagination before the chosen page can draw; hiding the
        // preview at lift-off left the OLD page on screen with zero feedback for that window,
        // which read as "the slider did nothing" — and a natural second touch in that window
        // cancels the in-flight commit (the race guard in onScrubStart), making the choice
        // silently vanish. The preview is already showing the chosen page, so it bridges the
        // gap honestly; it comes down inside the coroutine, after the real page has rendered
        // beneath it (every terminal path below hides it).
        scrubJob = lifecycleScope.launch {
            // True once this commit has written a position of its own (or established that the
            // stored one is already right). While false, a down-payment is standing unrefined.
            var resolved = false
            try {
                // Resolve the landing page WITHOUT touching the chapter cache off the main thread. The
                // cache is a LinkedHashMap(accessOrder = true) — "even a read mutates link order" — and
                // is main-thread-only by contract; the prefetch honours that by computing with the PURE
                // paginate() off-main and installing via publish() back on main. This block used to call
                // doc.chapter()/firstNonEmptyFrom inside Dispatchers.IO instead, racing the cache
                // against exactly the main-thread traffic a just-committed page guarantees (its
                // showPage's own chapter() and its prefetch's publish). That data race was the
                // intermittent "scrubbing after a page selection misbehaves" seen on the device. Same
                // choreography as the prefetch now: pure paginate off-main, publish + resolve on main.
                val doc = document
                val cfg = config
                var target: ReadingState? = null
                if (doc != null && cfg != null) {
                    val located = locateByFraction(chapterWeights, fraction)
                    val spine = snappedChapter ?: located.spineIndex
                    try {
                        if (!doc.isPaginated(spine, cfg)) {
                            val paginated = withContext(Dispatchers.Default) { doc.paginate(spine, cfg) }
                            doc.publish(spine, cfg, paginated) // main thread — the sanctioned install
                        }
                        // Main thread from here: every cache touch is single-threaded again.
                        target = if (snappedChapter != null) {
                            readerSurface.firstNonEmptyFrom(snappedChapter)
                        } else {
                            resolveScrubTarget(located)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A corrupt chapter surfaces on its first (lazy) read — right here, on the first
                        // scrub into it. Every other jump path reports and stays put; a scrub commit now
                        // does the same, falling through with no target into the no-op branch below.
                        // (The old code had NO guard: a corrupt chapter mid-scrub crashed the reader.)
                        showError(R.string.error_open_section, e)
                    }
                }
                // Only a commit that actually moves the reader is a jump: a null target (a chapter that
                // paginates to zero pages, e.g. image-only/cover content) or a target equal to where we
                // already are pushes nothing onto the back-stack and arms no ↩ — mirroring jumpToAnchor
                // (6d822a3), which pushes only after its target resolves, for the same reason.
                if (target != null && target != origin) {
                    jumpStack.push(origin)
                    updateBackControl()
                    showPage(target)
                    session.drainPending()?.let { persistPosition(it) }
                    resolved = true
                } else {
                    // A no-op commit: the drag ended on the page already being read (target == origin, or
                    // a target that paginates to nothing). showPage never runs, so the thumb — moved all
                    // over during the drag — would otherwise be left stranded, the slider lying about
                    // where the reader is. currentBookProgress still holds this page's fraction (no
                    // showPage ran during the drag), so snap the thumb back to the truth.
                    chapterScrubber.setProgress(currentBookProgress)
                    // The commit landed back where it started, so any down-payment now describes a
                    // chapter the reader never reached. The finally below puts the origin back.
                }
                // The page under the window is now correct (freshly rendered, or unchanged for a no-op):
                // the bridge has done its job, take the preview down. A commit cancelled before reaching
                // here (a new drag's race guard) leaves the window up on purpose — the new drag is
                // already re-blitting it, and its own commit will take it down.
                scrubPreviewFrame.visibility = View.GONE
                scrubPreview.setImageDrawable(null)
                shownPreviewEntry = null
            } finally {
                // The down-payment exists so a process death OR an Activity teardown mid-commit
                // reopens at the committed chapter rather than reverting — lift-off is a commitment,
                // not a draft (see the down-payment comment above), and onPause flushes the grace
                // straight into this call, so double-Back and swipe-away arrive here having
                // committed. A teardown-driven cancellation must therefore leave the down-payment
                // standing: repairing it would silently discard the navigation the down-payment
                // exists to preserve. (The overlay closing is NOT a cancellation trigger — commit
                // clears scrubOrigin synchronously above, so abandonScrub early-returns before it
                // ever reaches scrubJob?.cancel().)
                //
                // Only a commit that is cancelled while the reader STAYS in the book — a second
                // touch on the track, the race guard in onScrubStart — or one that resolves back to
                // where it started (the no-op branch above) ever repairs the down-payment.
                // persistPosition launches into the app-scoped writer, so the repair still commits
                // even though this coroutine is being cancelled.
                if (downPaymentWritten && !resolved && !isFinishing && !isDestroyed) {
                    originLocator?.let { persistPosition(it) }
                }
            }
        }
    }

    /** Pops one jump and navigates there, UNDER the still-open chrome — like a scrub commit, not a
     *  Contents jump. No fullRefresh (the overlay is open; the clean refresh lands when it closes)
     *  and no closeOverlay (so repeated taps walk back). Does NOT push — back is one-way. */
    private fun onBackJump() {
        val target = jumpStack.pop() ?: return
        // showPage paginates on the first read of a chapter no longer in the LRU, so this can throw
        // exactly as onTap and jumpToAnchor can — and this path had no guard at all. Report and
        // stay put; `state` is only reassigned inside showPage after its own chapter() succeeded.
        // The pop above is deliberately NOT undone on failure: the target is consumed either way,
        // because a retry would re-read the same unreadable chapter and fail identically. ↩ simply
        // moves on to the next entry (or hides, via updateBackControl below).
        try {
            showPage(target)
            session.drainPending()?.let { persistPosition(it) }
        } catch (e: Exception) {
            showError(R.string.error_open_section, e)
        }
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
        // The drag is being thrown away entirely — any decode still in flight for the last-crossed
        // entry has nothing left to paint (the window is about to go GONE below), so cancel it
        // rather than let it burn an IO thread to a result nobody will use.
        previewDecodeJob?.cancel()
        scrubOrigin = null
        scrubPreviewFrame.visibility = View.GONE
        scrubPreview.setImageDrawable(null)
        shownPreviewEntry = null
        // The origin page never left the screen during the drag, so there is NOTHING to repaint —
        // and repainting anyway (the old showPage(origin) here) flashed the panel mid-gesture,
        // which read as "the scrubber did something on its own" every time the system cancelled a
        // touch (the EMR pen hovering fires palm-rejection CANCELs on this hardware). Restore the
        // two things the drag actually moved: the readout text and the thumb.
        scrubberView.text = restingReadout
        // The view may still be holding an ARMED session (or an open grace window) — reset it
        // BEFORE moving the thumb, or the next touch would "resume" a session whose origin this
        // method just cleared.
        chapterScrubber.resetSession()
        chapterScrubber.setProgress(currentBookProgress)
    }

    /**
     * Turns a [BookLocation] into a real [ReadingState]. MAIN THREAD ONLY: `chapter()`'s cache is a
     * `LinkedHashMap(accessOrder = true)` where even a read mutates link order, so this must never
     * run on a background dispatcher. The commit path pre-warms the target chapter off-main via the
     * pure `paginate()` + main-thread `publish()` (the prefetch's own choreography), so the
     * `chapter()` call here is a cheap, safe cache hit.
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

    /** True when no commit render is in flight — a test waits on this after a commit or an abandon.
     *  `isCompleted`, not `isActive`: `cancel()` flips `isActive` false the instant it is CALLED,
     *  before the coroutine has unwound to its `finally` — a test polling `isActive` can observe
     *  "idle" while the repair write below is still in flight. `isCompleted` only goes true once the
     *  coroutine (finally included) has actually finished. */
    internal val scrubIdleForTest: Boolean get() = scrubJob?.isCompleted ?: true

    /** The reader's current position — a test's "did the page actually move" probe. */
    internal val currentStateForTest: ReadingState get() = state

    /** Drives the overlay-hide/Back abandon path directly, without a real touch dispatch. */
    internal fun abandonScrubForTest() = abandonScrub()

    /** Kills an in-flight commit resolution where a teardown's lifecycleScope cancellation would —
     *  the seam behind the durable-down-payment test. */
    internal fun cancelScrubJobForTest() {
        scrubJob?.cancel()
    }

    /** Drives a scrub lift-off directly — a test commits without synthesizing a touch stream. */
    internal fun commitScrubForTest(fraction: Float, snappedChapter: Int? = null) =
        onScrubCommitted(fraction, snappedChapter)

    /** Drives the ↩ control directly — a test pops a jump without hunting the view. */
    internal fun backJumpForTest() = onBackJump()

    // -- Preview-strip test seams -------------------------------------------------------------------
    // Strip GENERATION is Task 6; until then a test that needs the preview window to actually show
    // must generate a strip itself, against the Activity's own RenderConfig, then re-run the load
    // that normally only happens once, at open. These three seams are exactly that path.

    /** The Activity's own resolved RenderConfig for this open — what a test must generate a strip
     *  against for [previewStrip] to recognize it as a match. */
    internal val configForTest: RenderConfig? get() = config

    /** The Activity's own [PreviewStripStore] instance — a test's way to reach
     *  [PreviewStripStore.onGenerateStartedForTest] on the SAME instance [scheduleStripGeneration]
     *  actually uses (a freshly-constructed `PreviewStripStore(context)` elsewhere in a test is a
     *  different object operating on the same disk directories, but can't set a hook this Activity
     *  will ever call). Not called in production. */
    internal val stripStoreForTest: PreviewStripStore get() = stripStore

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

    /** The loaded [previewStrip]'s own config hash — lets a test tell WHICH config's strip ended up
     *  loaded (e.g. after a race between a settings-change reload and a superseded generation for a
     *  different config), not just that some strip is loaded. Null when none is. */
    internal val previewStripConfigHashForTest: String? get() = previewStrip?.configHash

    /** The open book's absolute path — a test's seam for building files/strips against the same book
     *  without reaching into [Intent] extras itself. Not called in production. */
    internal val bookPathForTest: String? get() = bookPath

    /** Drives a drag position directly — a test moves the thumb without a touch stream. */
    internal fun scrubMoveForTest(fraction: Float, snappedChapter: Int? = null) =
        onScrubMoved(fraction, snappedChapter)

    /** Whether the floating preview is currently showing a decoded page. */
    internal val previewBitmapShownForTest: Boolean
        get() = scrubPreviewFrame.visibility == View.VISIBLE && scrubPreview.drawable != null

    /** True once the in-flight preview decode ([previewDecodeJob]) has actually finished (null
     *  counts as finished) — unlike polling [previewBitmapShownForTest], this is also true when the
     *  decode resolved to the FAILURE branch (window stays GONE), so a test targeting that branch
     *  can wait for the decode to actually land instead of asserting against a bitmap that was
     *  never going to appear. Not called in production. */
    internal val previewDecodeIdleForTest: Boolean get() = previewDecodeJob?.isCompleted != false

    /** The current [previewDecodeJob] reference itself. [lifecycleScope] runs on
     *  `Dispatchers.Main.immediate`, so `launch { ... }` executes inline up to its first suspension
     *  point (the `withContext(Dispatchers.IO)` hop) — meaning a move that never reaches the decode
     *  block (previews off, no strip, snapped-chapter-with-no-opening) leaves this null the instant
     *  the triggering call returns. A race-free proof that no decode was ever scheduled, rather than
     *  one that merely hasn't landed yet. Not called in production. */
    internal val previewDecodeJobForTest: Job? get() = previewDecodeJob

    /** Sets [ReaderPrefs.previewsEnabled] directly and pushes it to the scrubber, without going
     *  through a real Aa-sheet tap — a test's way to drive the previews-off path. Not called in
     *  production; production always goes through [SettingsHost.togglePreviews]. */
    internal fun setPreviewsEnabledForTest(enabled: Boolean) {
        ReaderPrefs(this).previewsEnabled = enabled
        previewsEnabled = enabled
        chapterScrubber.setGenerationStateVisible(enabled)
    }

    /** Drives the Aa sheet's per-book delete without a real tap. Not called in production. */
    internal fun deletePreviewsForCurrentBookForTest() = settingsHost.deletePreviewsForCurrentBook()

    /** See [SettingsHost.hasPreviewsForCurrentBook] — a test's way to assert the decision directly,
     *  without needing to drive [SettingsSheet.refresh] through a real Aa-sheet open. Not called in
     *  production. */
    internal fun hasPreviewsForCurrentBookForTest(): Boolean = settingsHost.hasPreviewsForCurrentBook()

    /** Whether a strip generation is running right now. */
    internal val stripGenerationActiveForTest: Boolean get() = stripGenerationJob?.isActive == true

    /** The current [stripGenerationJob] itself — lets a test capture a REFERENCE to a specific
     *  generation and poll ITS OWN completion later, even if [stripGenerationJob] itself is
     *  reassigned or nulled out in the meantime (e.g. by a fix that supersedes it). Not called in
     *  production. */
    internal val stripGenerationJobForTest: Job? get() = stripGenerationJob

    /** Whether [stripGenerationJob] has actually stopped running (null counts as stopped). Unlike
     *  [stripGenerationActiveForTest], which goes false the instant `cancel()` is requested —
     *  cancellation is cooperative, so the coroutine can still be executing — this only goes true
     *  once the job reaches a terminal state. A test that cancels a real generation should poll
     *  this (e.g. via `idleUntil`) before returning, or the coroutine can still be burning a
     *  [Dispatchers.Default] pool thread when the next test starts. Not called in production. */
    internal val stripGenerationFinishedForTest: Boolean get() = stripGenerationJob?.isCompleted != false

    /** A snapshot of [generatedChapters] — lets a test confirm a superseded generation's late,
     *  stale-token callback did not repopulate it. Not called in production. */
    internal val generatedChaptersForTest: Set<Int> get() = generatedChapters.toSet()

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

        /**
         * Extensions this reader can open, for the standalone-launch walk only. The library's own
         * routing goes through `bookFormatOf`; this exists because the adb/no-extra launch path
         * has no index to consult.
         */
        private val READABLE_EXTENSIONS = setOf("epub", "mobi", "azw", "prc")
    }
}
