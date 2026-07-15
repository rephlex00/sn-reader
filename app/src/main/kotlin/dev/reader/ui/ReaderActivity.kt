package dev.reader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import dev.reader.engine.PageNavigator
import dev.reader.engine.ReadingState
import dev.reader.engine.RenderConfig
import dev.reader.engine.advance
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
 * Opens the first EPUB found under the Supernote's /Document folder and turns its pages.
 *
 * All calls into [EpubDocument.chapter] happen from this Activity's UI thread (either directly
 * from a lifecycleScope coroutine resumed on Dispatchers.Main, or from [PageView]'s tap
 * callback, which View always delivers on the main thread) because `chapter()`'s cache is
 * documented as not thread-safe. Only opening the document (pure I/O, no cache involved yet)
 * runs on Dispatchers.IO.
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var pageView: PageView
    private var document: EpubDocument? = null
    private var navigator: PageNavigator? = null
    private var state = ReadingState(0, 0)
    private var config: RenderConfig? = null

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

        if (!Environment.isExternalStorageManager()) {
            requestAllFilesAccess()
            return
        }
        pageView.doOnLayout { openFirstBook() }
    }

    override fun onResume() {
        super.onResume()
        if (document == null && !opening && Environment.isExternalStorageManager()) {
            pageView.doOnLayout { openFirstBook() }
        }
    }

    override fun onDestroy() {
        document?.close()
        document = null
        super.onDestroy()
    }

    /**
     * Supernote keeps user books in /Document; reading them in place is the whole point,
     * so all-files access is the only workable permission on Android 11.
     */
    private fun requestAllFilesAccess() {
        showMessage("Grant all-files access so Reader can open books in your Document folder.")
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
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
                val file = withContext(Dispatchers.IO) { findFirstEpub() }
                if (file == null) {
                    // Not a permanent failure: the user may drop a book in and come back, and
                    // onResume re-arms only while `opening` is false.
                    opening = false
                    showMessage("No EPUB found in /Document.")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    opened = EpubDocument.open(
                        file,
                        AndroidTextMeasurer(SpannedChapterBuilder(), TypefaceProvider.Platform),
                    )
                }
                val doc = opened!!
                document = doc
                navigator = PageNavigator(doc.spineSize)
                pageView.onTap = ::onTap

                val firstChapter = doc.chapter(0, renderConfig)
                if (firstChapter.pages.isEmpty()) {
                    // A missing or empty chapter file paginates to zero pages. showPage() would
                    // return silently and leave a blank white screen — indistinguishable from a
                    // broken app — so name the problem, and say that the book may still be
                    // readable from the next chapter on (advance() skips empty chapters).
                    showMessage("This book's first chapter is empty. Tap the right edge to read on.")
                } else {
                    showPage(ReadingState(0, 0))
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

    private fun findFirstEpub(): File? = try {
        val documents = File(Environment.getExternalStorageDirectory(), "Document")
        documents.walkTopDown()
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

        val next = when (zone) {
            TapZone.NEXT -> advance(nav, state, pageCountFor)
            TapZone.PREVIOUS -> retreat(nav, state, pageCountFor)
            // The overlay arrives with the reading chrome in Plan 4.
            TapZone.TOGGLE_OVERLAY -> null
        }
        // null = nowhere to go (start/end of book, or everything beyond is empty): stay put and
        // draw nothing, so a tap at the end of the book costs no invalidate.
        if (next != null) showPage(next)
    }

    private fun showPage(next: ReadingState) {
        val doc = document ?: return
        val cfg = config ?: return
        val chapter: PaginatedChapter = doc.chapter(next.spineIndex, cfg)
        if (chapter.pages.isEmpty()) return

        val pageIndex = next.pageIndex.coerceIn(0, chapter.pages.lastIndex)
        state = next.copy(pageIndex = pageIndex)

        val layout = (chapter.measured as AndroidMeasuredChapter).layout
        pageView.show(layout, chapter.pages[pageIndex], cfg.marginPx)
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
