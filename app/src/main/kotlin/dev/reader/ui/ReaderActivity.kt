package dev.reader.ui

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import dev.reader.ReaderApplication
import dev.reader.engine.Locator
import dev.reader.engine.PageNavigator
import dev.reader.engine.ReadingState
import dev.reader.engine.RenderConfig
import dev.reader.engine.advance
import dev.reader.engine.pageIndexFor
import dev.reader.engine.retreat
import dev.reader.formats.epub.EpubDocument
import dev.reader.formats.epub.EpubException
import dev.reader.formats.epub.PaginatedChapter
import dev.reader.formats.render.AndroidMeasuredChapter
import dev.reader.formats.render.AndroidTextMeasurer
import dev.reader.formats.render.SpannedChapterBuilder
import dev.reader.formats.render.TypefaceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    private var document: EpubDocument? = null
    private var navigator: PageNavigator? = null
    private var state = ReadingState(0, 0)
    private var config: RenderConfig? = null

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
        setContentView(pageView)

        if (!isAllFilesAccessGranted()) {
            requestAllFilesAccess()
            return
        }
        pageView.doOnLayout { openFirstBook() }
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
     * Flushes the pending page-turn position on the way out — the whole of the debounce's write
     * side. Page turns only touch memory ([ReadingSession.recordPageTurn]); the single DB write
     * happens here, when the user leaves the reader. This is a deliberate design (see the class
     * design notes and [ReaderApplication.positionWriteScope]):
     *  - No timer, postDelayed, or periodic write coalesces turns — that would wake the process at
     *    rest and break the idle promise. In-memory coalescing plus a flush-on-exit does not.
     *  - The tradeoff: a hard power-loss that skips onStop entirely (a battery pull) loses only the
     *    progress made since the book was opened, because the open-time write already stamped the
     *    starting position. That window is the price of zero background wakeups; nothing here tries
     *    to narrow it with a periodic write.
     *  - The write is launched into the application scope, not lifecycleScope: onStop is immediately
     *    followed by onDestroy cancelling lifecycleScope, which could cancel this UPDATE before it
     *    commits. See [persistPosition].
     */
    override fun onStop() {
        session.drainPending()?.let { persistPosition(it) }
        super.onStop()
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
        app.positionWriteScope.launch {
            dao.updatePosition(path, locator.spineIndex, locator.charOffset, now)
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

        val renderConfig = RenderConfig(
            fontFamily = "serif",
            textSizePx = 34f,
            lineSpacingMultiplier = 1.4f,
            marginPx = 48,
            justified = true,
            hyphenated = true,
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
                bookPath = file.path
                navigator = PageNavigator(doc.spineSize)
                pageView.onTap = ::onTap

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
                //    0th) chapter, skipping empty chapters exactly as a page turn would. Real EPUBs
                //    almost always open on a cover-image page that paginates to zero pages, so a
                //    fresh read still lands on the first chapter with text.
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
                } else {
                    showPage(start)
                    // Write the resolved start back immediately: stamps lastOpenedAtMs (so the
                    // RECENTLY_OPENED sort works, and it survives a process kill that skips onStop)
                    // AND heals a stale or clamped stored position on disk. state was set by
                    // showPage, so its coerced pageIndex is the one actually shown.
                    persistPosition(Locator(state.spineIndex, firstChapter.pages[state.pageIndex].startOffset))
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
        AndroidTextMeasurer(SpannedChapterBuilder(), TypefaceProvider.Platform),
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
                // The overlay arrives with the reading chrome in Plan 4.
                TapZone.TOGGLE_OVERLAY -> null
            }
            // null = nowhere to go (start/end of book, or everything beyond is empty): stay put and
            // draw nothing, so a tap at the end of the book costs no invalidate.
            if (next != null) showPage(next)
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

        // Unchecked downcast through the TextMeasurer seam: MeasuredChapter itself stays
        // Android-free, but PageView needs the real StaticLayout to draw. Safe today because
        // this Activity is the only caller of EpubDocument.open, always with
        // AndroidTextMeasurer — this cast is the seam's one leak, and it stays that way rather
        // than widening MeasuredChapter's contract for a single caller.
        val layout = (chapter.measured as AndroidMeasuredChapter).layout
        pageView.show(layout, chapter.pages[pageIndex], cfg.marginPx)

        // Record — not write — the new position: the page's startOffset is the stable char offset a
        // later restore maps back to a page. This only overwrites an in-memory field (coalescing);
        // the actual DB write is deferred to onStop's flush. Zero I/O per page turn.
        session.recordPageTurn(Locator(state.spineIndex, chapter.pages[pageIndex].startOffset))
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** String extra: an absolute book path, set by [LibraryActivity] when opening a tap. */
        const val EXTRA_BOOK_PATH = "dev.reader.ui.EXTRA_BOOK_PATH"
    }
}
