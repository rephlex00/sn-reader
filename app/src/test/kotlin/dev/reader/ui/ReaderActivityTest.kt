package dev.reader.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import dev.reader.R
import dev.reader.ReaderApplication
import dev.reader.data.BookEntity
import dev.reader.data.HighlightEntity
import dev.reader.engine.ReadingState
import dev.reader.engine.RenderConfig
import dev.reader.engine.TocEntry
import dev.reader.engine.snapToWords
import dev.reader.formats.epub.EpubDocument
import dev.reader.formats.epub.EpubException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Robolectric coverage for [ReaderActivity]'s three documented load-bearing behaviors — the
 * [ReaderActivity]-private `opening` flag's double-layout race, the cancel-mid-open path that
 * closes the just-opened document, and failed-open recoverability — plus the stale
 * [ReaderActivity.EXTRA_BOOK_PATH] handling. Uses the same protected-open + test-subclass seam
 * pattern as [LibraryActivityRecreationTest]: [TestableReaderActivity] overrides the three points
 * where this Activity reaches out to real permissions, real EPUB opens, and a real /Document tree.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderActivityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private companion object {
        /**
         * The default test viewport, PORTRAIT — taller than it is wide, like the panel this reader
         * runs on. The shape is load-bearing, not decoration: `renderConfig` reads two columns out
         * of any viewport wider than it is tall (see `columnCountFor`), and in two columns a page
         * turn moves TWO pages. This harness laid out at 800x600 until landscape support landed, so
         * every test in this class was silently asserting single-page turns against a viewport that
         * now asks for spreads. Tests that want a spread say so explicitly — see the landscape
         * section — and pass [LANDSCAPE_W] / [LANDSCAPE_H] to `launchAndLayOut`.
         */
        const val VIEWPORT_W = 600
        const val VIEWPORT_H = 800

        const val LANDSCAPE_W = 800
        const val LANDSCAPE_H = 600
    }

    // -- I2: a stale EXTRA_BOOK_PATH must not silently open a different book ------------------

    @Test
    fun `a stale book extra shows a message and finishes instead of opening another book`() {
        // The tapped file vanished between grid paint and tap. Falling back to findFirstEpub
        // (the old behavior) silently opens whatever EPUB sorts first — and once Task 6 wires
        // position memory, writes the wrong row's position.
        val controller = readerFor(intentWithExtra("${tempFolder.root}/gone.epub"))
        controller.get().firstEpub = tempFolder.newFile("decoy.epub") // must NOT be used

        launchAndLayOut(controller)
        idleUntil { controller.get().isFinishing }

        assertThat(controller.get().isFinishing).isTrue()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("That book is no longer there.")
        assertThat(controller.get().findFirstCalls).isEqualTo(0)
        assertThat(controller.get().openCalls).isEqualTo(0)
    }

    @Test
    fun `no extra at all still falls back to findFirstEpub (the adb launch path)`() {
        val controller = readerFor(Intent(RuntimeEnvironment.getApplication(), TestableReaderActivity::class.java))
        controller.get().firstEpub = null

        launchAndLayOut(controller)
        // Wait on the toast, not on findFirstCalls. The counter increments inside findFirstEpub on
        // Dispatchers.IO while the toast is posted to the main thread afterwards, so waiting on the
        // counter can return before the toast exists and the assertion below reads null. That race
        // is a real intermittent failure, not a theoretical one.
        idleUntil { ShadowToast.getTextOfLatestToast() != null }

        assertThat(controller.get().findFirstCalls).isEqualTo(1)
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("No EPUB found in /Document.")
        // Recoverable, not finished: the user may drop a book in and come back.
        assertThat(controller.get().isFinishing).isFalse()
    }

    // -- The `opening` flag: one layout pass arming two listeners opens the book once ----------

    @Test
    fun `the double-armed layout listeners open the book exactly once`() {
        // Cold start with permission granted: onCreate and onResume both arm doOnLayout before
        // the first layout pass, so both callbacks fire on that one pass. The `opening` flag —
        // set synchronously, before the coroutine's first suspension — is the only thing that
        // keeps the second callback from opening the book (and its ZipFile) a second time.
        val book = minimalEpub(tempFolder.newFile("book.epub"))
        val controller = readerFor(intentWithExtra(book.path))

        launchAndLayOut(controller)
        idleUntil { controller.get().openedDocuments.isNotEmpty() }

        assertThat(controller.get().openCalls).isEqualTo(1)
        // Control for the cancel-mid-open test below: a document that was NOT cancelled is
        // readable — chapter() succeeds against its open ZipFile.
        val doc = controller.get().openedDocuments.single()
        doc.chapter(0, testRenderConfig())
    }

    // -- Cancel-mid-open closes the just-opened document (the leaked-ZipFile guard) ------------

    @Test
    fun `destroying the activity mid-open closes the document that open() returned`() {
        val book = minimalEpub(tempFolder.newFile("book.epub"))
        val controller = readerFor(intentWithExtra(book.path))
        val activity = controller.get()
        activity.openEntered = CountDownLatch(1)
        activity.blockOpen = CountDownLatch(1)

        launchAndLayOut(controller)
        // Wait by PUMPING the main looper, never by blocking on the latch. The open path makes two
        // hops to Dispatchers.IO — first to resolve the file, then to open it — and the resumption
        // between them is posted to the main looper, which Robolectric leaves paused until
        // something idles it. A bare `openEntered.await(5s)` blocks this thread with that
        // continuation still sitting in the queue, so openDocument is never reached and the latch
        // times out having proved nothing. That is what made this test fail about one run in ten
        // (and only alongside its siblings, never alone) until 2026-07-21, when it blocked a
        // release. `idleUntil` runs the queue between checks, which is the only thing that lets the
        // coroutine advance.
        idleUntil { activity.openEntered!!.count == 0L }
        assertThat(activity.openEntered!!.count).isEqualTo(0L)

        // Back-press/destroy while open() is still in flight on Dispatchers.IO: lifecycleScope
        // is cancelled, but the blocking open only returns after the latch is released.
        controller.pause().stop().destroy()
        activity.blockOpen!!.countDown()

        // The CancellationException branch must close the returned document — `document = doc`
        // never ran, so onDestroy saw null and this branch is the only thing standing between a
        // back-press during load and a leaked ZipFile. Observable as: reading from the document
        // now fails (its ZipFile is closed), where the test above proves an uncancelled one reads
        // fine.
        //
        // Every probe uses a config the document has NOT seen. `chapter()` memoises per
        // (config, spineIndex) via `cache.getOrPut`, so probing twice with the SAME config reads
        // the cache the second time and reports success against an archive that is by then closed
        // — the probe would stop measuring the thing it is named for. The first probe can
        // legitimately land before the cancellation handler has run (the wait exists because that
        // ordering is not guaranteed), and it would then poison every later one. A config it has
        // not seen clears the cache and forces a real read of the archive.
        //
        // This was latent, not the cause of the flake above: the runs that failed never got far
        // enough to probe at all.
        var probe = 0
        fun archiveIsClosed(): Boolean {
            val doc = activity.openedDocuments.singleOrNull() ?: return false
            val unseen = testRenderConfig().copy(textSizePx = 30f + probe++)
            return runCatching { doc.chapter(0, unseen) }.isFailure
        }

        idleUntil { archiveIsClosed() }
        assertThat(archiveIsClosed()).isTrue()
    }

    // -- Failed-open recoverability ------------------------------------------------------------

    @Test
    fun `a failed open is recoverable on the next resume`() {
        val stub = tempFolder.newFile("corrupt.epub").apply { writeText("not a zip") }
        val controller = readerFor(intentWithExtra(stub.path))
        val activity = controller.get()
        activity.throwOnOpen = EpubException.Malformed("boom")

        launchAndLayOut(controller)
        // Wait for the failure to round-trip back to the main thread (the toast), not merely for
        // openDocument to have been entered on Dispatchers.IO.
        idleUntil { ShadowToast.getTextOfLatestToast() != null }
        // Plain language, and crucially NOT the exception text: "boom" belongs in the log.
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(RuntimeEnvironment.getApplication().getString(R.string.error_open_book))
        assertThat(ShadowToast.getTextOfLatestToast()).doesNotContain("boom")
        assertThat(activity.isFinishing).isFalse()

        // The failure reset `opening`, so coming back to this screen retries rather than
        // sitting dead: onResume re-arms only while `opening` is false.
        controller.pause().resume()
        idleUntil { activity.openCalls == 2 }

        assertThat(activity.openCalls).isEqualTo(2)
    }

    // -- Task 6: per-page-turn, cancel-proof position persistence ------------------------------

    @Test
    fun `a page turn persists its position immediately, while the reader is still open`() {
        // This is the one thing ReadingSessionTest (pure) cannot cover: that the wiring persists the
        // turned-to position to the DAO on the turn itself — not deferred to onStop — so progress on
        // disk is current even if the process is later killed. It also pins that the write is on the
        // application-scoped writer (see the destroy-survival test below for the cancel-proof half).
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        // The reader is not the indexer: it UPDATEs an existing row. Pre-insert one, never opened
        // (lastOpenedAtMs null, charOffset 0), so the open-time write is detectable via
        // lastOpenedAtMs and the page-turn write via a changed charOffset.
        runBlocking { app.database.bookDao().upsertAll(listOf(dbBook(book.path))) }

        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)

        // Wait for the open-time write-back to land: it stamps lastOpenedAtMs (null -> non-null).
        idleUntil { rowFor(app, book.path)?.lastOpenedAtMs != null }
        assertThat(rowFor(app, book.path)!!.charOffset).isEqualTo(0) // page 0 starts at offset 0

        // Turn the page. The Activity delivers taps to PageView.onTap on the main thread; invoking
        // it directly is the same entry point. This advances to page 1 and persists it.
        pageViewOf(controller.get()).onTap!!.invoke(TapZone.NEXT)

        // No onStop, no destroy: the turn alone must move the on-disk offset off page 0. The write is
        // async on the write scope, so idle until it lands rather than reading it synchronously.
        idleUntil { rowFor(app, book.path)!!.charOffset != 0 }
        val turned = rowFor(app, book.path)!!
        assertThat(turned.charOffset).isGreaterThan(0) // page 1 begins past offset 0
        assertThat(turned.spineIndex).isEqualTo(0)
        assertThat(turned.lastOpenedAtMs).isNotNull()
    }

    @Test
    fun `a page turn's position write survives an immediate onDestroy`() {
        // The write is launched on the application-scoped writer, not lifecycleScope, so tearing the
        // Activity down right after a turn (onDestroy cancels lifecycleScope) cannot lose it — the
        // "work cancelled before it ran" trap this project has hit before.
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        runBlocking { app.database.bookDao().upsertAll(listOf(dbBook(book.path))) }

        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        idleUntil { rowFor(app, book.path)?.lastOpenedAtMs != null }

        // Turn the page, then immediately tear the Activity all the way down before draining the
        // write scope. A write on lifecycleScope would be cancelled here; the app-scoped one is not.
        pageViewOf(controller.get()).onTap!!.invoke(TapZone.NEXT)
        controller.pause().stop().destroy()

        idleUntil { rowFor(app, book.path)!!.charOffset != 0 }
        val persisted = rowFor(app, book.path)!!
        assertThat(persisted.charOffset).isGreaterThan(0)
        assertThat(persisted.spineIndex).isEqualTo(0)
    }

    // -- M3: denied permission is a message + finish, not a silent blank page ------------------

    @Test
    fun `a denied permission finishes with a message instead of a silent blank page`() {
        val controller = readerFor(Intent(RuntimeEnvironment.getApplication(), TestableReaderActivity::class.java))
        controller.get().accessGranted = false

        controller.setup()

        assertThat(controller.get().isFinishing).isTrue()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo("Reader needs all-files access to open books.")
    }

    // -- Plan 4 Task 2: the reading overlay (the way out) --------------------------------------

    @Test
    fun `scrubberText reads chapter-relative, one-based`() {
        assertThat(scrubberText(0, 5)).isEqualTo("page 1 of 5 · 4 left in chapter")
        assertThat(scrubberText(4, 5)).isEqualTo("page 5 of 5 · 0 left in chapter")
    }

    @Test
    fun `a center tap shows the overlay and a second center tap hides it`() {
        val controller = openedMultiPage()
        val activity = controller.get()
        assertThat(overlayOf(activity).visibility).isEqualTo(View.GONE)

        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        assertThat(overlayOf(activity).visibility).isEqualTo(View.VISIBLE)

        // The real second physical tap lands on the overlay, but onTap is the specified toggle and
        // is what the harness can drive; invoking it again must hide.
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        assertThat(overlayOf(activity).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `a page-area tap while the overlay is shown dismisses it without turning the page`() {
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        val before = scrubberTextOf(activity)

        // A PREVIOUS/NEXT/center tap that reaches pageView while the overlay is up only dismisses.
        pageViewOf(activity).onTap!!.invoke(TapZone.PREVIOUS)

        assertThat(overlayOf(activity).visibility).isEqualTo(View.GONE)
        assertThat(scrubberTextOf(activity)).isEqualTo(before) // page did not change
    }

    @Test
    fun `a NEXT tap while the overlay is shown does not advance the page`() {
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        val before = scrubberTextOf(activity)

        pageViewOf(activity).onTap!!.invoke(TapZone.NEXT)

        assertThat(overlayOf(activity).visibility).isEqualTo(View.GONE)
        assertThat(scrubberTextOf(activity)).isEqualTo(before)
        assertThat(before).startsWith("page 1 of ") // still on the first page
    }

    @Test
    fun `by default every page turn triggers a clean refresh`() {
        clearReaderPrefs()
        val controller = openedMultiPage()
        val activity = controller.get()
        val calls = intArrayOf(0)
        pageViewOf(activity).epd = object : EpdRefresher {
            override val available = true
            override fun cleanRefresh(): Boolean { calls[0]++; return true }
            override fun enterFastMode(): Boolean = false
            override fun exitFastMode(): Boolean = false
        }
        // Three genuine page turns (NEXT within the multi-page chapter — multiPageEpub's 60
        // paragraphs paginate to well more than 3 pages, so none of these run off the end).
        repeat(3) { pageViewOf(activity).onTap!!.invoke(TapZone.NEXT) }
        assertThat(calls[0]).isEqualTo(3)
    }

    @Test
    fun `faster mode refreshes only every N turns and the frequency row toggles with it`() {
        clearReaderPrefs()
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.settings_button).performClick()

        // Frequency row hidden until faster mode is on.
        assertThat(activity.findViewById<View>(R.id.refresh_frequency_row).visibility).isEqualTo(View.GONE)
        activity.findViewById<View>(R.id.toggle_faster_turns).performClick()
        assertThat(activity.findViewById<View>(R.id.refresh_frequency_row).visibility).isEqualTo(View.VISIBLE)
        activity.findViewById<View>(R.id.refresh_freq_3).performClick() // N = 3

        val calls = intArrayOf(0)
        pageViewOf(activity).epd = object : EpdRefresher {
            override val available = true
            override fun cleanRefresh(): Boolean { calls[0]++; return true }
            override fun enterFastMode(): Boolean = false
            override fun exitFastMode(): Boolean = false
        }
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY) // hide overlay so taps turn pages
        repeat(3) { pageViewOf(activity).onTap!!.invoke(TapZone.NEXT) }
        // Two: one from hiding the overlay (its own clean-on-close refresh, by design — see
        // hideOverlay), plus one more clean refresh across the 3 turns at N=3.
        assertThat(calls[0]).isEqualTo(2)
    }

    @Test
    fun `overlay toggles do not compound beyond one clean refresh per close`() {
        val controller = openedMultiPage()
        val activity = controller.get()
        val pv = pageViewOf(activity)

        // Center taps toggle the overlay, not turn pages, so they never reach the page-turn ghost-
        // clear cadence on their own. Hiding the overlay does trigger its own clean-on-close refresh
        // (by design — see hideOverlay), so 10 hides across 20 toggles (each pair returns to hidden)
        // means 10 refreshes — not the compounding page-turn-cadence count this would be otherwise.
        repeat(20) { pv.onTap!!.invoke(TapZone.TOGGLE_OVERLAY) }
        assertThat(pv.fullRefreshCount).isEqualTo(10)
    }

    // -- Fast e-ink screen mode for chrome --------------------------------------------------------

    @Test
    fun `showing the overlay enters fast mode and hiding it restores and refreshes`() {
        val controller = openedMultiPage()
        val activity = controller.get()
        val epd = object : EpdRefresher {
            override val available = true
            var fastModeHeld = false
            override fun cleanRefresh(): Boolean = true
            override fun enterFastMode(): Boolean { fastModeHeld = true; return true }
            override fun exitFastMode(): Boolean { fastModeHeld = false; return true }
        }
        pageViewOf(activity).epd = epd

        activity.showOverlayForTest()
        assertThat(epd.fastModeHeld).isTrue()

        val refreshesBefore = pageViewOf(activity).fullRefreshCount
        activity.hideOverlayForTest()

        assertThat(epd.fastModeHeld).isFalse()
        assertThat(pageViewOf(activity).fullRefreshCount).isGreaterThan(refreshesBefore)
    }

    @Test
    fun `onPause restores the screen mode even with the overlay still open`() {
        val controller = openedMultiPage()
        val activity = controller.get()
        val epd = object : EpdRefresher {
            override val available = true
            var fastModeHeld = false
            override fun cleanRefresh(): Boolean = true
            override fun enterFastMode(): Boolean { fastModeHeld = true; return true }
            override fun exitFastMode(): Boolean { fastModeHeld = false; return true }
        }
        pageViewOf(activity).epd = epd

        activity.showOverlayForTest()
        assertThat(epd.fastModeHeld).isTrue()

        controller.pause()

        // The screen mode is device-wide runtime state. A leaked fast mode degrades the whole device
        // UI, so the restore must ride on the last callback Android guarantees, not on a close handler
        // the user can bypass by swiping the app away.
        assertThat(epd.fastModeHeld).isFalse()
    }

    @Test
    fun `a Contents jump clean-refreshes only the destination page, not the page being left`() {
        // The bug this guards: hideOverlay's own clean-on-close refresh used to fire unconditionally
        // on every close, including the Contents/Bookmarks/Highlights jump path — flashing the OLD
        // page clean (via closeOverlay) before goTo ever drew the destination, and leaving the
        // destination itself un-clean-refreshed (showPage only invalidate()s).
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = tocEpub(tempFolder.newFile("book.epub"))
        runBlocking { app.database.bookDao().upsertAll(listOf(dbBook(book.path))) }
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        val activity = controller.get()
        idleUntil { scrubberTextOf(activity).isNotEmpty() }
        // Opens on chapter 0.
        assertThat(activity.currentTopOffsetForTest()!!.spineIndex).isEqualTo(0)

        // Records which chapter was on screen (per the Activity's own state) at the moment each
        // clean refresh actually fired — a refresh recorded against chapter 0 would mean the page
        // being LEFT flashed clean before the jump landed.
        val refreshedAtSpineIndex = mutableListOf<Int?>()
        pageViewOf(activity).epd = object : EpdRefresher {
            override val available = true
            override fun cleanRefresh(): Boolean {
                refreshedAtSpineIndex += activity.currentTopOffsetForTest()?.spineIndex
                return true
            }
            override fun enterFastMode(): Boolean = false
            override fun exitFastMode(): Boolean = false
        }

        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.contents_button).performClick()
        val refreshesBefore = pageViewOf(activity).fullRefreshCount
        clickTocRow(activity, position = 1) // tocEpub's second nav entry -> chapter index 1

        // Exactly one clean refresh for the whole jump (not zero, not two — no flash of the old page
        // AND no missing refresh of the new one), and it landed once chapter 1 was already on screen.
        assertThat(pageViewOf(activity).fullRefreshCount - refreshesBefore).isEqualTo(1)
        assertThat(refreshedAtSpineIndex).containsExactly(1)
        assertThat(overlayOf(activity).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `system Back closes the overlay when shown and finishes when hidden`() {
        val controller = openedMultiPage()
        val activity = controller.get()

        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        assertThat(overlayOf(activity).visibility).isEqualTo(View.VISIBLE)

        // Overlay shown: Back only closes it, does not finish.
        activity.onBackPressedDispatcher.onBackPressed()
        assertThat(overlayOf(activity).visibility).isEqualTo(View.GONE)
        assertThat(activity.isFinishing).isFalse()

        // Overlay hidden: Back leaves the book.
        activity.onBackPressedDispatcher.onBackPressed()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun `the scrubber shows the current page and updates after a turn`() {
        val controller = openedMultiPage()
        val activity = controller.get()

        // Y comes from the Activity's own pagination (its production RenderConfig can paginate
        // differently from testRenderConfig), read straight off the live readout.
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        val opened = scrubberTextOf(activity)
        assertThat(opened).matches("""page 1 of \d+ · \d+ left in chapter""")
        val y = Regex("""of (\d+)""").find(opened)!!.groupValues[1].toInt()
        assertThat(opened).isEqualTo(scrubberText(0, y))

        // Hide, then turn a page (turns only happen while the overlay is hidden). The readout must
        // reflect the new page the next time the overlay opens.
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        pageViewOf(activity).onTap!!.invoke(TapZone.NEXT)
        assertThat(scrubberTextOf(activity)).isEqualTo(scrubberText(1, y))
    }

    @Test
    fun `the overlay Back control finishes to the library`() {
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        runBlocking { app.database.bookDao().upsertAll(listOf(dbBook(book.path))) }
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        val activity = controller.get()
        // The first page must be shown (scrubber populated) before onTap is wired.
        idleUntil { scrubberTextOf(activity).isNotEmpty() }

        // Turn a page so there is a real position on disk, then leave via the Back control.
        pageViewOf(activity).onTap!!.invoke(TapZone.NEXT)
        idleUntil { rowFor(app, book.path)!!.charOffset != 0 }

        activity.findViewById<View>(R.id.back).performClick()

        assertThat(activity.isFinishing).isTrue()
        // The turned-to position is persisted (exit flushes; every turn already persisted its own).
        assertThat(rowFor(app, book.path)!!.charOffset).isGreaterThan(0)
    }

    @Test
    fun `the overlay title comes from the book metadata`() {
        val controller = openedMultiPage()
        val activity = controller.get()
        // multiPageEpub's OPF declares <dc:title>Long</dc:title>.
        assertThat(activity.findViewById<TextView>(R.id.book_title).text.toString()).isEqualTo("Long")
    }

    // -- Plan 4 Task 3: the Aa typography sheet ------------------------------------------------

    @Test
    fun `the Aa button opens the sheet showing current values and a second tap closes it`() {
        clearReaderPrefs()
        val controller = openedMultiPage()
        val activity = controller.get()
        // The sheet lives inside the overlay; open the overlay first, then the sheet via Aa.
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        val sheet = activity.findViewById<View>(R.id.settings_sheet)
        assertThat(sheet.visibility).isEqualTo(View.GONE)

        activity.findViewById<View>(R.id.settings_button).performClick()
        assertThat(sheet.visibility).isEqualTo(View.VISIBLE)
        // Reflects current (default) values: the size readout and each toggle switch read the prefs.
        assertThat(activity.findViewById<TextView>(R.id.size_value).text.toString()).isEqualTo("34px")
        assertThat(activity.findViewById<ToggleSwitchView>(R.id.toggle_justify_switch).checked).isTrue()

        activity.findViewById<View>(R.id.settings_button).performClick()
        assertThat(sheet.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `each panel's close button hides that panel and leaves the reading toolbar up`() {
        // The device has no hardware Back, so every panel/sheet must be dismissible on-screen. Each
        // top-right ✕ peels its own layer back to the bare overlay (not out of the chrome).
        clearReaderPrefs()
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)

        data class CloseCase(val openId: Int, val closeId: Int, val panelId: Int)
        val cases = listOf(
            CloseCase(R.id.contents_button, R.id.toc_close, R.id.toc_panel),
            CloseCase(R.id.bookmarks_button, R.id.bookmarks_close, R.id.bookmarks_panel),
            CloseCase(R.id.highlights_button, R.id.highlights_close, R.id.highlights_panel),
            CloseCase(R.id.settings_button, R.id.settings_close, R.id.settings_sheet),
        )
        for (c in cases) {
            activity.findViewById<View>(c.openId).performClick()
            assertThat(activity.findViewById<View>(c.panelId).visibility).isEqualTo(View.VISIBLE)

            activity.findViewById<View>(c.closeId).performClick()
            assertThat(activity.findViewById<View>(c.panelId).visibility).isEqualTo(View.GONE)
            // Closing a panel returns to the reading toolbar, it does not dismiss the whole overlay.
            assertThat(overlayOf(activity).visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun `bumping the text size writes the pref, re-paginates, and keeps the reader on a valid page`() {
        clearReaderPrefs()
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.settings_button).performClick()

        activity.findViewById<View>(R.id.size_plus).performClick()

        // The pref moved one step (34 -> 36) and the readout followed.
        assertThat(ReaderPrefs(RuntimeEnvironment.getApplication()).textSizePx).isEqualTo(36f)
        assertThat(activity.findViewById<TextView>(R.id.size_value).text.toString()).isEqualTo("36px")
        // The chapter re-paginated live without crashing; the reader is still on a valid page and the
        // sheet stayed open.
        assertThat(scrubberTextOf(activity)).matches("""page \d+ of \d+ · \d+ left in chapter""")
        assertThat(activity.findViewById<View>(R.id.settings_sheet).visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `tapping a font option writes that family and re-paginates`() {
        clearReaderPrefs()
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.settings_button).performClick()
        // Fresh install opens on the default face.
        assertThat(ReaderPrefs(RuntimeEnvironment.getApplication()).fontFamily).isEqualTo("literata")

        activity.findViewById<View>(R.id.font_bitter).performClick()

        assertThat(ReaderPrefs(RuntimeEnvironment.getApplication()).fontFamily).isEqualTo("bitter")
        // Re-paginated live without crashing; still on a valid page, sheet still open.
        assertThat(scrubberTextOf(activity)).matches("""page \d+ of \d+ · \d+ left in chapter""")
        assertThat(activity.findViewById<View>(R.id.settings_sheet).visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `selecting a font marks only that option and clears the previous selection`() {
        // Regression: the selected option used to be marked by bolding it, but bold could not be
        // stripped back off a bundled font's already-bold instance, so switching fonts left the
        // previous option still marked and every font eventually looked selected. The boxed-outline
        // background must move to exactly the active option and clear from the others.
        clearReaderPrefs()
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.settings_button).performClick()

        // Default is literata: only its option is marked.
        assertThat(activity.findViewById<View>(R.id.font_literata).background).isNotNull()
        assertThat(activity.findViewById<View>(R.id.font_bitter).background).isNull()
        assertThat(activity.findViewById<View>(R.id.font_atkinson).background).isNull()

        activity.findViewById<View>(R.id.font_atkinson).performClick()

        // Selection MOVED: atkinson marked, literata cleared (the bug left literata marked too).
        assertThat(activity.findViewById<View>(R.id.font_atkinson).background).isNotNull()
        assertThat(activity.findViewById<View>(R.id.font_literata).background).isNull()
        assertThat(activity.findViewById<View>(R.id.font_bitter).background).isNull()

        activity.findViewById<View>(R.id.font_bitter).performClick()

        assertThat(activity.findViewById<View>(R.id.font_bitter).background).isNotNull()
        assertThat(activity.findViewById<View>(R.id.font_literata).background).isNull()
        assertThat(activity.findViewById<View>(R.id.font_atkinson).background).isNull()
    }

    @Test
    fun `flipping the publisher-styling toggle writes the pref and updates its switch`() {
        clearReaderPrefs()
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.settings_button).performClick()
        val switch = activity.findViewById<ToggleSwitchView>(R.id.toggle_publisher_switch)
        assertThat(switch.checked).isTrue()

        // The whole row is the tap target; performClick on it flips the pref and re-renders.
        activity.findViewById<View>(R.id.toggle_publisher).performClick()

        assertThat(ReaderPrefs(RuntimeEnvironment.getApplication()).publisherStyling).isFalse()
        assertThat(activity.findViewById<ToggleSwitchView>(R.id.toggle_publisher_switch).checked).isFalse()
    }

    @Test
    fun `system Back closes the Aa sheet first, then the overlay, then the book`() {
        clearReaderPrefs()
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.settings_button).performClick()
        assertThat(activity.findViewById<View>(R.id.settings_sheet).visibility).isEqualTo(View.VISIBLE)

        // First Back: sheet only.
        activity.onBackPressedDispatcher.onBackPressed()
        assertThat(activity.findViewById<View>(R.id.settings_sheet).visibility).isEqualTo(View.GONE)
        assertThat(overlayOf(activity).visibility).isEqualTo(View.VISIBLE)
        assertThat(activity.isFinishing).isFalse()

        // Second Back: overlay. Third: the book.
        activity.onBackPressedDispatcher.onBackPressed()
        assertThat(overlayOf(activity).visibility).isEqualTo(View.GONE)
        assertThat(activity.isFinishing).isFalse()
        activity.onBackPressedDispatcher.onBackPressed()
        assertThat(activity.isFinishing).isTrue()
    }

    // -- Plan 4 Task 4: TOC navigation ---------------------------------------------------------

    @Test
    fun `tocRows maps entries in spine order, preserving depth and marking the current chapter`() {
        val toc = listOf(
            TocEntry(title = "One", spineIndex = 0, depth = 0),
            TocEntry(title = "Two", spineIndex = 1, depth = 0),
            TocEntry(title = "Two-a", spineIndex = 2, depth = 1),
        )

        val rows = tocRows(toc, currentSpineIndex = 1) { _, _ -> 0f }

        // Order and depth are passed through untouched.
        assertThat(rows.map { it.title }).containsExactly("One", "Two", "Two-a").inOrder()
        assertThat(rows.map { it.depth }).containsExactly(0, 0, 1).inOrder()
        // Only the entry whose chapter is the current one is marked.
        assertThat(rows.map { it.isCurrent }).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun `tocRows on an empty toc is empty (the No contents case)`() {
        assertThat(tocRows(emptyList(), currentSpineIndex = 0) { _, _ -> 0f }).isEmpty()
    }

    @Test
    fun `rows carry a whole-book percentage from progressFor`() {
        val toc = listOf(
            TocEntry(title = "Ashes", spineIndex = 0, charOffset = 0, depth = 0),
            TocEntry(title = "Winter", spineIndex = 2, charOffset = 40, depth = 0),
        )

        val rows = tocRows(toc, currentSpineIndex = 0) { spineIndex, _ ->
            if (spineIndex == 0) 0f else 0.436f
        }

        assertThat(rows.map { it.progressPercent }).containsExactly(0, 44).inOrder()
    }

    @Test
    fun `tocTarget lands an anchored entry on the page containing its char offset`() {
        // A chapter with pages: the offset->page resolution is the pageIndexFor seam. An anchored
        // entry (charOffset 500) must land on the page whose range contains 500, NOT page 0.
        val target = tocTarget(
            spineIndex = 2,
            charOffset = 500,
            pageCountFor = { 4 }, // non-empty chapter
            offsetToPageIndex = { spine, offset ->
                assertThat(spine).isEqualTo(2)
                assertThat(offset).isEqualTo(500)
                3 // the page pageIndexFor would return for offset 500
            },
            firstNonEmptyFrom = { error("must not degrade a non-empty chapter") },
        )

        assertThat(target).isEqualTo(ReadingState(2, 3))
    }

    @Test
    fun `tocTarget degrades an entry at an empty chapter to the nearest readable one`() {
        // The tapped chapter paginates to zero pages: skip forward to the nearest readable chapter
        // instead of showing a blank page, exactly like the open path's advance empty-skip.
        val target = tocTarget(
            spineIndex = 1,
            charOffset = 0,
            pageCountFor = { 0 }, // empty chapter
            offsetToPageIndex = { _, _ -> error("must not resolve an offset on an empty chapter") },
            firstNonEmptyFrom = { from ->
                assertThat(from).isEqualTo(1)
                ReadingState(2, 0) // chapter 2 is the next readable one
            },
        )

        assertThat(target).isEqualTo(ReadingState(2, 0))
    }

    @Test
    fun `tocTarget yields null when the tapped chapter and everything after it are empty`() {
        val target = tocTarget(
            spineIndex = 3,
            charOffset = 0,
            pageCountFor = { 0 },
            offsetToPageIndex = { _, _ -> error("must not resolve") },
            firstNonEmptyFrom = { null }, // nothing readable remains
        )

        assertThat(target).isNull()
    }

    @Test
    fun `the Contents button opens the panel listing every chapter with the current one marked`() {
        val controller = openedWithToc()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        val panel = activity.findViewById<View>(R.id.toc_panel)
        assertThat(panel.visibility).isEqualTo(View.GONE)

        activity.findViewById<View>(R.id.contents_button).performClick()

        assertThat(panel.visibility).isEqualTo(View.VISIBLE)
        assertThat(activity.findViewById<View>(R.id.toc_empty).visibility).isEqualTo(View.GONE)
        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.toc_list)
        assertThat(list.visibility).isEqualTo(View.VISIBLE)
        // tocEpub's nav declares three chapters; the reader opens on chapter 0.
        assertThat(list.adapter!!.itemCount).isEqualTo(3)
    }

    @Test
    fun `opening the Contents panel does not paginate a chapter it has not visited`() {
        // The bug this guards: TocPanel.refresh() used to build each row's percentage through
        // ReaderSurface.progressFor, which paginates its chapter on a cache miss — so opening a
        // per-chapter Contents list paginated every chapter in the book just to display, undoing
        // the whole point of lazy pagination. isChapterCachedForTest is the same read-only cache
        // peek the prefetch tests use, so this proves it without needing a fake EpubDocument
        // (which isn't possible: EpubDocument has a private constructor and isn't open).
        val controller = openedWithToc()
        val activity = controller.get()
        // Opening chapter 0 paginates it; settling on it schedules a background prefetch of
        // chapter 1 (the only forward neighbour). Chapter 2 is never touched by either — it is
        // the one tocEpub entry the panel could only reach by paginating it itself.
        assertThat(activity.isChapterCachedForTest(2)).isFalse()

        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.contents_button).performClick()
        assertThat(activity.findViewById<View>(R.id.toc_panel).visibility).isEqualTo(View.VISIBLE)

        assertThat(activity.isChapterCachedForTest(2)).isFalse()
    }

    @Test
    fun `tapping a Contents entry navigates to that chapter and persists it`() {
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = tocEpub(tempFolder.newFile("book.epub"))
        runBlocking { app.database.bookDao().upsertAll(listOf(dbBook(book.path))) }
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        val activity = controller.get()
        idleUntil { scrubberTextOf(activity).isNotEmpty() }
        idleUntil { rowFor(app, book.path)?.lastOpenedAtMs != null }
        // Opens on chapter 0.
        assertThat(rowFor(app, book.path)!!.spineIndex).isEqualTo(0)

        // Open the overlay, the Contents panel, then click the second entry (chapter index 1).
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.contents_button).performClick()
        clickTocRow(activity, position = 1)

        // The jump navigated to chapter 1 and closed the chrome to the page.
        idleUntil { rowFor(app, book.path)!!.spineIndex == 1 }
        assertThat(rowFor(app, book.path)!!.spineIndex).isEqualTo(1)
        assertThat(overlayOf(activity).visibility).isEqualTo(View.GONE)
        assertThat(activity.findViewById<View>(R.id.toc_panel).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `system Back closes the Contents panel first, then the overlay, then the book`() {
        val controller = openedWithToc()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.contents_button).performClick()
        assertThat(activity.findViewById<View>(R.id.toc_panel).visibility).isEqualTo(View.VISIBLE)

        // First Back: the TOC panel only.
        activity.onBackPressedDispatcher.onBackPressed()
        assertThat(activity.findViewById<View>(R.id.toc_panel).visibility).isEqualTo(View.GONE)
        assertThat(overlayOf(activity).visibility).isEqualTo(View.VISIBLE)
        assertThat(activity.isFinishing).isFalse()

        // Second Back: the overlay. Third: the book.
        activity.onBackPressedDispatcher.onBackPressed()
        assertThat(overlayOf(activity).visibility).isEqualTo(View.GONE)
        assertThat(activity.isFinishing).isFalse()
        activity.onBackPressedDispatcher.onBackPressed()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun `opening the Contents panel closes the Aa sheet, and vice versa`() {
        clearReaderPrefs()
        val controller = openedWithToc()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)

        // Aa first, then Contents: Contents replaces the sheet.
        activity.findViewById<View>(R.id.settings_button).performClick()
        assertThat(activity.findViewById<View>(R.id.settings_sheet).visibility).isEqualTo(View.VISIBLE)
        activity.findViewById<View>(R.id.contents_button).performClick()
        assertThat(activity.findViewById<View>(R.id.settings_sheet).visibility).isEqualTo(View.GONE)
        assertThat(activity.findViewById<View>(R.id.toc_panel).visibility).isEqualTo(View.VISIBLE)

        // Contents open, then Aa: the sheet replaces the panel.
        activity.findViewById<View>(R.id.settings_button).performClick()
        assertThat(activity.findViewById<View>(R.id.toc_panel).visibility).isEqualTo(View.GONE)
        assertThat(activity.findViewById<View>(R.id.settings_sheet).visibility).isEqualTo(View.VISIBLE)
    }

    // -- Bookmarks panel ------------------------------------------------------------------------

    @Test
    fun `adding then reopening the panel shows the bookmark and marks the page`() {
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        // The bookmarks table's bookPath column is a FOREIGN KEY on books.path (CASCADE delete) —
        // a book not in the library cannot take a bookmark. Match BookmarkDaoTest's convention:
        // insert the row first.
        runBlocking { app.database.bookDao().upsertAll(listOf(dbBook(book.path))) }
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        val activity = controller.get()
        idleUntil { scrubberTextOf(activity).isNotEmpty() }
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)

        // Open the bookmarks panel; nothing bookmarked yet -> empty view, toggle says "Bookmark".
        val toggle = overlayOf(activity).findViewById<android.widget.TextView>(R.id.bookmark_toggle)
        overlayOf(activity).findViewById<View>(R.id.bookmarks_button).performClick()
        // The async refresh sets the toggle label when it completes; wait for that.
        idleUntil { toggle.text.isNotBlank() }
        assertThat(overlayOf(activity).findViewById<View>(R.id.bookmarks_empty).visibility).isEqualTo(View.VISIBLE)
        assertThat(toggle.text.toString()).contains("Bookmark this page")

        // Tap the toggle: bookmarks the current page, list now non-empty, toggle flips to "Remove".
        toggle.performClick()
        idleUntil { toggle.text.toString().contains("Remove") }
        assertThat(overlayOf(activity).findViewById<View>(R.id.bookmarks_empty).visibility).isEqualTo(View.GONE)
        assertThat(overlayOf(activity).findViewById<View>(R.id.bookmarks_list).visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `the bookmarks button does not turn the page`() {
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        val before = scrubberTextOf(activity)
        overlayOf(activity).findViewById<View>(R.id.bookmarks_button).performClick()
        assertThat(scrubberTextOf(activity)).isEqualTo(before)
    }

    @Test
    fun `toggling the bookmark for a book not in the library does not crash and writes nothing`() {
        // Deliberately do NOT upsert a books row (unlike the test above) — this is the standalone-
        // launch path: a sideloaded EPUB, or one the library indexer hasn't reached yet.
        // BookmarkEntity.bookPath is a FOREIGN KEY on books.path with enforcement on, so an INSERT
        // for this book would violate the FK. Before Task 4's fix that threw an uncaught
        // SQLiteConstraintException inside the coroutine and crashed the app.
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        // No bookDao().upsertAll(...) here — that's the whole point of this test.
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        val activity = controller.get()
        idleUntil { scrubberTextOf(activity).isNotEmpty() }
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)

        val toggle = overlayOf(activity).findViewById<android.widget.TextView>(R.id.bookmark_toggle)
        overlayOf(activity).findViewById<View>(R.id.bookmarks_button).performClick()
        idleUntil { toggle.text.isNotBlank() }

        // Tap the toggle: the handler must complete (no crash) without inserting a bookmark, and
        // must tell the user why instead of silently doing nothing.
        toggle.performClick()
        idleUntil { ShadowToast.getTextOfLatestToast() != null }
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("This book isn't in your library yet.")
        assertThat(runBlocking { app.database.bookmarkDao().bookmarksFor(book.path) }).isEmpty()
    }

    // -- Highlights: gesture state machine, chapter cache, panel ------------------------------

    @Test
    fun `a stylus commit inserts one word-snapped highlight with the selected text`() {
        val (controller, path) = openedMultiPageInLibrary()
        val activity = controller.get()

        // The raw span [4,20) is word-snapped by the engine before it is stored; drive the commit
        // through the handler with explicit offsets so the assertion does not depend on Robolectric's
        // coarse pixel measurement, and compare against snapToWords over the same chapter text.
        val text = activity.currentChapterTextForTest()!!
        val expected = snapToWords(text, 4, 20)!!
        activity.commitHighlight(4, 20)

        idleUntil { highlightsOf(path).isNotEmpty() }
        val stored = highlightsOf(path)
        assertThat(stored).hasSize(1)
        assertThat(stored[0].spineIndex).isEqualTo(0)
        assertThat(stored[0].startOffset).isEqualTo(expected.start)
        assertThat(stored[0].endOffset).isEqualTo(expected.end)
        // The captured text is exactly the snapped substring (a whole-word span, never a mid-word cut).
        assertThat(stored[0].text).isEqualTo(text.substring(expected.start, expected.end))
    }

    @Test
    fun `a commit overlapping an existing highlight merges into a single row`() {
        val (controller, path) = openedMultiPageInLibrary()
        val activity = controller.get()

        activity.commitHighlight(0, 10)
        idleUntil { highlightsOf(path).size == 1 }
        val first = highlightsOf(path)[0]

        // A second span that starts inside the first and reaches well past its end must union with it
        // — one row out, not two, with the merged range covering both.
        activity.commitHighlight(first.startOffset, first.endOffset + 40)
        idleUntil { highlightsOf(path).firstOrNull()?.let { it.endOffset > first.endOffset } == true }

        val merged = highlightsOf(path)
        assertThat(merged).hasSize(1)
        assertThat(merged[0].startOffset).isAtMost(first.startOffset)
        assertThat(merged[0].endOffset).isAtLeast(first.endOffset)
    }

    @Test
    fun `a pen tap inside a highlight reveals the delete chip and tapping it deletes`() {
        val (controller, path) = openedMultiPageInLibrary()
        val activity = controller.get()

        activity.commitHighlight(0, 12)
        idleUntil { highlightsOf(path).size == 1 }
        // onStylusTap consults the in-memory chapter cache, which reloads off-main after the write;
        // wait for the cache to reflect the new highlight before tapping into it.
        idleUntil { activity.chapterHighlightsForTest.isNotEmpty() }
        val hl = highlightsOf(path)[0]

        // A tap at the highlight's start offset falls inside its half-open range -> reveal the chip.
        assertThat(activity.deleteChipForTest.visibility).isEqualTo(View.GONE)
        activity.onStylusTap(hl.startOffset)
        assertThat(activity.deleteChipForTest.visibility).isEqualTo(View.VISIBLE)

        // Tapping the chip removes the highlight and hides the chip again.
        activity.deleteChipForTest.performClick()
        idleUntil { highlightsOf(path).isEmpty() }
        assertThat(highlightsOf(path)).isEmpty()
        assertThat(activity.deleteChipForTest.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `opening a panel from the toolbar dismisses a floating delete chip`() {
        val (controller, path) = openedMultiPageInLibrary()
        val activity = controller.get()

        activity.commitHighlight(0, 12)
        idleUntil { highlightsOf(path).size == 1 }
        idleUntil { activity.chapterHighlightsForTest.isNotEmpty() }
        val hl = highlightsOf(path)[0]

        activity.onStylusTap(hl.startOffset)
        assertThat(activity.deleteChipForTest.visibility).isEqualTo(View.VISIBLE)

        // A toolbar tap is NOT routed through PageView, so opening a panel must itself hide the chip —
        // otherwise it floats over the panel pointing at a now-hidden highlight.
        activity.findViewById<View>(R.id.highlights_button).performClick()
        assertThat(activity.deleteChipForTest.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `tapping a highlight to remove it also abandons a pending bracket anchor`() {
        val (controller, path) = openedMultiPageInLibrary()
        val activity = controller.get()

        activity.commitHighlight(0, 12)
        idleUntil { highlightsOf(path).size == 1 }
        idleUntil { activity.chapterHighlightsForTest.isNotEmpty() }
        val hl = highlightsOf(path)[0]

        // Arm a bracket-start just past the highlight's end -> outside its half-open range, so this
        // tap finds no existing highlight and arms the anchor instead of revealing the chip.
        val anchorOffset = hl.endOffset
        activity.onStylusTap(anchorOffset)
        assertThat(activity.bracketAnchorForTest).isEqualTo(anchorOffset)

        // A tap inside the existing highlight reveals the delete chip AND must clear that stale anchor
        // — otherwise the reader's next tap would silently commit an unexpected span.
        activity.onStylusTap(hl.startOffset)
        assertThat(activity.deleteChipForTest.visibility).isEqualTo(View.VISIBLE)
        assertThat(activity.bracketAnchorForTest).isNull()
    }

    @Test
    fun `arming a bracket then changing chapter clears the anchor and messages`() {
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = tocEpub(tempFolder.newFile("book.epub"))
        runBlocking { app.database.bookDao().upsertAll(listOf(dbBook(book.path))) }
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        val activity = controller.get()
        idleUntil { scrubberTextOf(activity).isNotEmpty() }

        // First pen tap on empty text arms a bracket-start anchor (no highlight there to remove).
        activity.onStylusTap(3)
        assertThat(activity.bracketAnchorForTest).isEqualTo(3)

        // Finger-turn to the last page of chapter 0, then one more turn crosses into chapter 1. The
        // chapter change (not a same-chapter page turn) is what drops the anchor, with a message.
        val pages = pageCountOf(scrubberTextOf(activity))
        repeat(pages) { pageViewOf(activity).onTap!!.invoke(TapZone.NEXT) }

        idleUntil { activity.bracketAnchorForTest == null }
        assertThat(activity.bracketAnchorForTest).isNull()
        assertThat(ShadowToast.getTextOfLatestToast()).contains("another chapter")
    }

    @Test
    fun `the Highlights panel lists highlights, jumps on tap, and deletes with the empty state`() {
        val (controller, path) = openedMultiPageInLibrary()
        val activity = controller.get()
        val panel = activity.findViewById<View>(R.id.highlights_panel)
        val empty = activity.findViewById<View>(R.id.highlights_empty)
        val list = activity.findViewById<RecyclerView>(R.id.highlights_list)

        // Open the panel with nothing highlighted -> the empty state, list hidden.
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.highlights_button).performClick()
        idleUntil { empty.visibility == View.VISIBLE }
        assertThat(panel.visibility).isEqualTo(View.VISIBLE)
        assertThat(list.adapter!!.itemCount).isEqualTo(0)

        // Add a highlight on the page, then reopen the panel (no standing observer -> a close/open
        // reload) so it lists the new row.
        activity.commitHighlight(0, 12)
        idleUntil { highlightsOf(path).size == 1 }
        activity.findViewById<View>(R.id.highlights_button).performClick() // close
        activity.findViewById<View>(R.id.highlights_button).performClick() // reopen + refresh
        idleUntil { list.adapter!!.itemCount == 1 }
        assertThat(empty.visibility).isEqualTo(View.GONE)

        // Tapping the row jumps to its page and closes the chrome down to the page.
        clickHighlightBody(activity, position = 0)
        idleUntil { overlayOf(activity).visibility == View.GONE }
        assertThat(overlayOf(activity).visibility).isEqualTo(View.GONE)
        assertThat(panel.visibility).isEqualTo(View.GONE)

        // Reopen and delete via the row's ✕: the store empties and the empty state returns.
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.highlights_button).performClick()
        idleUntil { list.adapter!!.itemCount == 1 }
        clickHighlightDelete(activity, position = 0)
        idleUntil { highlightsOf(path).isEmpty() }
        assertThat(highlightsOf(path)).isEmpty()
        idleUntil { empty.visibility == View.VISIBLE }
        assertThat(empty.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `committing a highlight for a book not in the library writes nothing and says why`() {
        // The standalone-launch path: no books row, so the highlights FK cannot be satisfied. The
        // commit must bail with a message instead of throwing SQLiteConstraintException.
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        // No bookDao().upsertAll(...) — that is the point of this test.
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        val activity = controller.get()
        idleUntil { scrubberTextOf(activity).isNotEmpty() }

        activity.commitHighlight(0, 12)
        idleUntil { ShadowToast.getTextOfLatestToast() != null }
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("This book isn't in your library yet.")
        assertThat(highlightsOf(book.path)).isEmpty()
    }

    // -- Plan 4 Task 6b: adjacent-chapter prefetch --------------------------------------------

    @Test
    fun `settling on the last page prefetches the next chapter and the coroutine then terminates`() {
        val controller = openedWithToc()
        val activity = controller.get()
        // The reader opens on chapter 0; chapter 1 is not yet paginated.
        assertThat(activity.isChapterCachedForTest(1)).isFalse()

        // Turn to the LAST page of chapter 0 (read its page count off the live scrubber), so the
        // policy targets chapter 1 — the chapter the next boundary turn would cross into.
        val pages = pageCountOf(scrubberTextOf(activity))
        repeat(pages - 1) { pageViewOf(activity).onTap!!.invoke(TapZone.NEXT) }
        assertThat(scrubberTextOf(activity)).isEqualTo(scrubberText(pages - 1, pages))

        // The background prefetch paginates chapter 1 off the main thread and publishes it; idle
        // until it lands as a cache hit — proving the wiring computes AND publishes the neighbour.
        idleUntil { activity.isChapterCachedForTest(1) }
        assertThat(activity.isChapterCachedForTest(1)).isTrue()
        // One-shot: the coroutine ran to completion, leaving no steady-state work.
        assertThat(activity.prefetchJobForTest!!.isActive).isFalse()
    }

    @Test
    fun `the prefetch does not re-arm while the reader sits idle`() {
        // The idle promise: after a prefetch completes, an untouched reader launches no further
        // work — no timer, no polling, no re-arm. Only a page turn may start the next prefetch.
        val controller = openedWithToc()
        val activity = controller.get()
        val pages = pageCountOf(scrubberTextOf(activity))
        repeat(pages - 1) { pageViewOf(activity).onTap!!.invoke(TapZone.NEXT) }
        idleUntil { activity.isChapterCachedForTest(1) }

        val settledJob = activity.prefetchJobForTest
        // Sit idle: pump the looper and wait, with no page turn in between.
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        // No new coroutine was armed, and the one that ran has terminated.
        assertThat(activity.prefetchJobForTest).isSameInstanceAs(settledJob)
        assertThat(settledJob!!.isActive).isFalse()
    }

    private fun pageCountOf(scrubber: String): Int =
        Regex("""of (\d+)""").find(scrubber)!!.groupValues[1].toInt()

    // -- Whole-book progress bar ------------------------------------------------------------------

    @Test
    fun `the progress bar is on by default and reflects whole-book position`() {
        val controller = openedMultiPage()
        val activity = controller.get()

        // Default on: showPage handed PageView a fraction in [0,1] (0 on the opening page).
        val p = pageViewOf(activity).progress
        assertThat(p).isNotNull()
        assertThat(p!!).isAtLeast(0f)
        assertThat(p).isAtMost(1f)
    }

    @Test
    fun `toggling the progress bar off hides it, and toggling again restores it without turning the page`() {
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY) // show the overlay
        val before = scrubberTextOf(activity)
        assertThat(ReaderPrefs(activity).showProgressBar).isTrue()

        overlayOf(activity).findViewById<View>(R.id.toggle_progress).performClick()

        assertThat(ReaderPrefs(activity).showProgressBar).isFalse()
        assertThat(pageViewOf(activity).progress).isNull()
        // Display-only: the page did not turn.
        assertThat(scrubberTextOf(activity)).isEqualTo(before)

        // Toggling a second time must restore a real fraction and flip the pref back — not leave
        // the bar permanently hidden after one off/on cycle.
        overlayOf(activity).findViewById<View>(R.id.toggle_progress).performClick()

        assertThat(ReaderPrefs(activity).showProgressBar).isTrue()
        assertThat(pageViewOf(activity).progress).isNotNull()
        assertThat(scrubberTextOf(activity)).isEqualTo(before)
    }

    @Test
    fun `the progress fraction increases across a forward page turn`() {
        // Guards against an implementation that hardcodes pageIndex = 0 into the bookProgress(...)
        // call in showPage — that would still pass the "on by default, in [0,1]" test above but
        // would never move as the reader actually turns pages.
        val controller = openedMultiPage()
        val activity = controller.get()
        val before = pageViewOf(activity).progress
        assertThat(before).isNotNull()

        pageViewOf(activity).onTap!!.invoke(TapZone.NEXT)

        val after = pageViewOf(activity).progress
        assertThat(after).isNotNull()
        assertThat(after!!).isGreaterThan(before!!)
        assertThat(after).isAtLeast(0f)
        assertThat(after).isAtMost(1f)
    }

    // -- Task 6: chapter scrubber — no page render during a drag, only on release ---------------

    @Test
    fun `dragging the scrubber renders no page — only the readout moves`() {
        val controller = openedWithToc()
        val activity = controller.get()
        activity.showOverlayForTest()
        val scrubber = activity.findViewById<ChapterScrubberView>(R.id.chapter_scrubber)

        val pagesBefore = activity.pagesShownForTest

        scrubber.onScrubMove?.invoke(0.4f)
        scrubber.onScrubMove?.invoke(0.6f)
        scrubber.onScrubMove?.invoke(0.9f)

        assertThat(activity.pagesShownForTest).isEqualTo(pagesBefore)
    }

    @Test
    fun `committing the scrub renders the selected page and persists it`() {
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = tocEpub(tempFolder.newFile("book.epub"))
        runBlocking { app.database.bookDao().upsertAll(listOf(dbBook(book.path))) }
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        val activity = controller.get()
        idleUntil { scrubberTextOf(activity).isNotEmpty() }
        idleUntil { rowFor(app, book.path)?.lastOpenedAtMs != null }
        activity.showOverlayForTest()
        val scrubber = activity.findViewById<ChapterScrubberView>(R.id.chapter_scrubber)

        val pagesBefore = activity.pagesShownForTest

        scrubber.onScrubMove?.invoke(0.9f)
        scrubber.onScrubCommit?.invoke(0.9f)
        idleUntil { activity.scrubIdleForTest }

        assertThat(activity.pagesShownForTest).isGreaterThan(pagesBefore)
        assertThat(activity.currentStateForTest.spineIndex).isGreaterThan(0)
        // The DB row is the load-bearing assertion — persistPosition takes a Locator, so this is
        // how a commit's write is observed, not an invented in-memory seam.
        idleUntil { rowFor(app, book.path)!!.spineIndex > 0 }
        assertThat(rowFor(app, book.path)!!.spineIndex).isGreaterThan(0)
    }

    @Test
    fun `abandoning a scrub restores the starting position and persists nothing`() {
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = tocEpub(tempFolder.newFile("book.epub"))
        runBlocking { app.database.bookDao().upsertAll(listOf(dbBook(book.path))) }
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        val activity = controller.get()
        idleUntil { scrubberTextOf(activity).isNotEmpty() }
        idleUntil { rowFor(app, book.path)?.lastOpenedAtMs != null }
        val startState = activity.currentStateForTest
        // The open-time write already landed; capture the row as it stands so a scrub that
        // persists nothing can be proven by the row staying exactly here.
        val rowBeforeScrub = rowFor(app, book.path)!!
        activity.showOverlayForTest()
        val scrubber = activity.findViewById<ChapterScrubberView>(R.id.chapter_scrubber)

        scrubber.onScrubStart?.invoke()
        scrubber.onScrubMove?.invoke(0.9f)
        activity.abandonScrubForTest()
        idleUntil { activity.scrubIdleForTest }

        assertThat(activity.currentStateForTest).isEqualTo(startState)
        val rowAfterAbandon = rowFor(app, book.path)!!
        assertThat(rowAfterAbandon.spineIndex).isEqualTo(rowBeforeScrub.spineIndex)
        assertThat(rowAfterAbandon.charOffset).isEqualTo(rowBeforeScrub.charOffset)
    }

    // -- Harness --------------------------------------------------------------------------------

    /** Clears the reader_prefs store so a test starts from the shipped defaults; Robolectric reuses
     *  the SharedPreferences file within a JVM fork. */
    private fun clearReaderPrefs() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("reader_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }


    /** [ReaderActivity] subclass whose test seams are set per-test via mutable fields. */
    private class TestableReaderActivity : ReaderActivity() {
        var accessGranted = true

        /** What [findFirstEpub] returns; the real /Document walk never runs under Robolectric. */
        var firstEpub: File? = null
        var findFirstCalls = 0

        var openCalls = 0

        /** Counted down when [openDocument] is entered, so a test can destroy mid-open. */
        var openEntered: CountDownLatch? = null

        /** Blocks [openDocument] (on Dispatchers.IO) until released — a slow real open. */
        var blockOpen: CountDownLatch? = null

        /** Thrown by [openDocument] instead of opening, for the failed-open path. */
        var throwOnOpen: EpubException? = null

        /** Every document the real open produced, so tests can observe closure. */
        val openedDocuments = mutableListOf<EpubDocument>()

        override fun isAllFilesAccessGranted(): Boolean = accessGranted

        override fun findFirstEpub(): File? {
            findFirstCalls++
            return firstEpub
        }

        override fun openDocument(file: File): EpubDocument {
            openCalls++
            openEntered?.countDown()
            blockOpen?.await()
            throwOnOpen?.let { throw it }
            return super.openDocument(file).also { openedDocuments += it }
        }
    }

    private fun readerFor(intent: Intent): ActivityController<TestableReaderActivity> =
        Robolectric.buildActivity(TestableReaderActivity::class.java, intent)

    /**
     * A reader opened on a real multi-page book and driven until its first page is shown (the
     * scrubber readout populated), so overlay tests can drive taps against a live document.
     */
    // -- Landscape: two columns per screen ------------------------------------------------------

    @Test
    fun `a landscape viewport shows two pages side by side`() {
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller, LANDSCAPE_W, LANDSCAPE_H)
        idleUntil { scrubberTextOf(controller.get()).isNotEmpty() }

        val pageView = pageViewOf(controller.get())
        assertThat(pageView.secondPageForTest).isNotNull()
        // The right column holds the page immediately after the left one — a spread is two
        // CONSECUTIVE pages, not two arbitrary ones.
        assertThat(pageView.secondPageForTest!!.index).isEqualTo(1)
    }

    @Test
    fun `a landscape page turn moves a whole spread, and back again returns to it`() {
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller, LANDSCAPE_W, LANDSCAPE_H)
        idleUntil { scrubberTextOf(controller.get()).isNotEmpty() }
        val pageView = pageViewOf(controller.get())

        pageView.onTap!!.invoke(TapZone.NEXT)
        // Pages 0-1 were showing; the next spread is 2-3, NOT 1-2 (which would re-show page 1).
        assertThat(pageView.secondPageForTest!!.index).isEqualTo(3)

        pageView.onTap!!.invoke(TapZone.PREVIOUS)
        assertThat(pageView.secondPageForTest!!.index).isEqualTo(1)
    }

    @Test
    fun `rotating re-paginates into columns and keeps the reader on the same text`() {
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller) // portrait
        idleUntil { scrubberTextOf(controller.get()).isNotEmpty() }
        val pageView = pageViewOf(controller.get())
        assertThat(pageView.secondPageForTest).isNull()

        // Read a few pages in, so the restored position is a real offset rather than 0 — landing on
        // the same TEXT (not the same page number) is the property that matters.
        pageView.onTap!!.invoke(TapZone.NEXT)
        pageView.onTap!!.invoke(TapZone.NEXT)
        val anchor = controller.get().currentTopOffsetForTest()!!

        // Rotate: the configuration change arrives first, the re-measure follows on the next layout
        // pass — exactly the order the real system delivers them in.
        controller.get().onConfigurationChanged(landscapeConfiguration(controller))
        layOutAt(controller, LANDSCAPE_W, LANDSCAPE_H)

        assertThat(pageViewOf(controller.get()).secondPageForTest).isNotNull()
        // The reader is on the page whose range contains the offset it was reading, not on page 2.
        val after = controller.get().currentTopOffsetForTest()!!
        assertThat(after.spineIndex).isEqualTo(anchor.spineIndex)
        // The spread STARTS at or before the text that was on screen, and the text is still within
        // it — which is what "the same words, not the same page number" means once a spread shows
        // two pages' worth at once.
        assertThat(after.charOffset).isAtMost(anchor.charOffset)
    }

    @Test
    fun `a book opened while the device is already sideways still paginates into columns`() {
        // The activity is created portrait (the library is pinned portrait), measures a portrait
        // viewport, and installs a portrait config SYNCHRONOUSLY — then the system rotates the
        // window while the archive is still opening on IO. onConfigurationChanged sees a null
        // document and can do nothing; reconcileViewport is what catches it after the open. Without
        // that, the book renders as one narrow portrait column stranded on a landscape screen.
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        val controller = readerFor(intentWithExtra(book.path))
        controller.setup()
        // Portrait layout arms the open, but do NOT idle: the open coroutine stays suspended on IO
        // with a portrait config already installed.
        val decor = controller.get().window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_H, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, VIEWPORT_W, VIEWPORT_H)

        // The system rotates the window under the in-flight open.
        controller.get().onConfigurationChanged(landscapeConfiguration(controller))
        layOutAt(controller, LANDSCAPE_W, LANDSCAPE_H)
        idleUntil { scrubberTextOf(controller.get()).isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(pageViewOf(controller.get()).secondPageForTest).isNotNull()
    }

    /** The activity's configuration with orientation flipped to landscape, as the system delivers
     *  it to [ReaderActivity.onConfigurationChanged] on a rotation. */
    private fun landscapeConfiguration(
        controller: ActivityController<TestableReaderActivity>,
    ): Configuration = Configuration(controller.get().resources.configuration).apply {
        orientation = Configuration.ORIENTATION_LANDSCAPE
    }

    private fun openedMultiPage(): ActivityController<TestableReaderActivity> {
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        idleUntil { scrubberTextOf(controller.get()).isNotEmpty() }
        return controller
    }

    /**
     * A reader opened on a real multi-page book that IS in the library, so the highlights FK
     * (`highlights.bookPath -> books.path`) is satisfied and a commit can write. Returns the
     * controller and the book path, driven until its first page is shown.
     */
    private fun openedMultiPageInLibrary(): Pair<ActivityController<TestableReaderActivity>, String> {
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        runBlocking { app.database.bookDao().upsertAll(listOf(dbBook(book.path))) }
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        idleUntil { scrubberTextOf(controller.get()).isNotEmpty() }
        return controller to book.path
    }

    /** This book's stored highlights in reading order, read off the main thread (Room forbids it). */
    private fun highlightsOf(path: String): List<HighlightEntity> {
        val app = RuntimeEnvironment.getApplication() as ReaderApplication
        return runBlocking { app.database.highlightDao().highlightsForBook(path) }
    }

    /** Lays out the Highlights RecyclerView and clicks the tap-to-jump body of the row at [position]. */
    private fun clickHighlightBody(activity: ReaderActivity, position: Int) {
        layOutHighlightRow(activity, position).findViewById<View>(R.id.highlight_body).performClick()
    }

    /** Lays out the Highlights RecyclerView and clicks the ✕ (delete) of the row at [position]. */
    private fun clickHighlightDelete(activity: ReaderActivity, position: Int) {
        layOutHighlightRow(activity, position).findViewById<View>(R.id.highlight_delete).performClick()
    }

    /** Measures/lays out the Highlights list (Robolectric does not on its own) and returns the row's
     *  itemView, so a child click lands on a real holder. */
    private fun layOutHighlightRow(activity: ReaderActivity, position: Int): View {
        val list = activity.findViewById<RecyclerView>(R.id.highlights_list)
        list.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 800, 600)
        return (
            list.findViewHolderForAdapterPosition(position)
                ?: error("no highlight row at position $position after layout")
            ).itemView
    }

    /** A reader opened on a real multi-chapter book carrying a nav TOC, driven until its first page
     *  is shown — so the Contents-panel tests have a real `doc.toc` to list and jump through. */
    private fun openedWithToc(): ActivityController<TestableReaderActivity> {
        val book = tocEpub(tempFolder.newFile("book.epub"))
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        idleUntil { scrubberTextOf(controller.get()).isNotEmpty() }
        return controller
    }

    /**
     * Lays out the Contents RecyclerView and clicks the row at [position]. Robolectric does not lay
     * out a freshly-shown RecyclerView on its own, so the panel subtree is measured/laid out here to
     * force holder creation before the child click.
     */
    private fun clickTocRow(activity: ReaderActivity, position: Int) {
        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.toc_list)
        list.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 800, 600)
        val holder = list.findViewHolderForAdapterPosition(position)
            ?: error("no TOC row at position $position after layout")
        holder.itemView.performClick()
    }

    private fun intentWithExtra(path: String): Intent =
        Intent(RuntimeEnvironment.getApplication(), TestableReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_PATH, path)

    /**
     * Runs the activity to resumed and forces a real, non-zero layout pass so the `doOnLayout`
     * arms actually fire — Robolectric does not lay out a window on its own, and openFirstBook
     * deliberately waits for a pass with real bounds.
     */
    private fun launchAndLayOut(
        controller: ActivityController<TestableReaderActivity>,
        widthPx: Int = VIEWPORT_W,
        heightPx: Int = VIEWPORT_H,
    ) {
        controller.setup()
        layOutAt(controller, widthPx, heightPx)
    }

    /** Forces a layout pass at the given size — a second call with a different shape is how a test
     *  rotates the device (paired with [ReaderActivity.onConfigurationChanged]). */
    private fun layOutAt(
        controller: ActivityController<TestableReaderActivity>,
        widthPx: Int,
        heightPx: Int,
    ) {
        val decor = controller.get().window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, widthPx, heightPx)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Same shape as ReaderActivity's production RenderConfig, at the test's default viewport. */
    private fun testRenderConfig() = RenderConfig(
        fontFamily = "serif",
        textSizePx = 34f,
        lineSpacingMultiplier = 1.4f,
        marginPx = 72,
        justified = true,
        hyphenated = true,
        viewportWidthPx = VIEWPORT_W,
        viewportHeightPx = VIEWPORT_H,
    )

    /**
     * A tiny but real EPUB on disk — [EpubDocument.open] parses it for real; only the tests
     * that never reach a real open use throwing/counting seams instead. Inlined here because
     * :formats' TestEpub helpers are test sources of another module.
     */
    private fun minimalEpub(file: File): File {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""",
            )
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Tiny</dc:title></metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
  </spine>
</package>""",
            )
            entry(
                "OEBPS/ch1.xhtml",
                "<html><body><h1>One</h1><p>A paragraph long enough to lay out and paginate " +
                    "into at least one page of readable text on any sane viewport.</p></body></html>",
            )
        }
        return file
    }

    private fun idleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    /**
     * The [PageView], the tap sink production drives too. It is now the first child of the content
     * [android.widget.FrameLayout] container that also holds the overlay on top of it (Plan 4 Task 2).
     */
    private fun pageViewOf(activity: ReaderActivity): PageView {
        val container = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        return container.getChildAt(0) as PageView
    }

    /** The reading overlay root — visibility is the observable overlay state. */
    private fun overlayOf(activity: ReaderActivity): View = activity.findViewById(R.id.reader_overlay)

    private fun scrubberTextOf(activity: ReaderActivity): String =
        activity.findViewById<TextView>(R.id.scrubber).text.toString()

    /** Reads back the row for [path] on a background thread (Room forbids main-thread queries). */
    private fun rowFor(app: ReaderApplication, path: String): BookEntity? =
        runBlocking { app.database.bookDao().getByPath(path) }

    /** A library row for [path], never opened, at the start of the book. */
    private fun dbBook(path: String) = BookEntity(
        path = path,
        sizeBytes = 1_000L,
        modifiedAtMs = 1_700_000_000_000L,
        title = "A Book",
        author = null,
        coverPath = null,
        spineIndex = 0,
        charOffset = 0,
        unreadable = false,
        unreadableReason = null,
        addedAtMs = 1_700_000_000_000L,
        lastOpenedAtMs = null,
    )

    /**
     * An EPUB whose single chapter is long enough to paginate into more than one page at the test's
     * 800x600 viewport, so a NEXT tap lands on a page whose `startOffset` is past 0 — the signal the
     * flush test asserts on. Same structure as [minimalEpub], just far more body text.
     */
    private fun multiPageEpub(file: File): File {
        val body = buildString {
            repeat(60) { i ->
                append("<p>Paragraph number $i. ")
                append("It carries enough words to fill several lines once laid out and justified ")
                append("on the viewport, so that sixty of them together span well past a single ")
                append("page and force the paginator to cut at least one page boundary.</p>")
            }
        }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""",
            )
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Long</dc:title></metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
  </spine>
</package>""",
            )
            entry("OEBPS/ch1.xhtml", "<html><body><h1>One</h1>$body</body></html>")
        }
        return file
    }

    /**
     * A three-chapter EPUB carrying a real EPUB 3 nav document, so [EpubDocument.toc] parses to three
     * entries in spine order (the third nested one level, to exercise depth) rather than the flat
     * spine synthesis. Every chapter has enough body text to paginate to at least one page, so a
     * Contents jump lands on real text.
     */
    private fun tocEpub(file: File): File {
        fun chapterBody(label: String) = buildString {
            repeat(12) {
                append("<p>$label paragraph $it with enough words to lay out into a line or two ")
                append("on the test viewport so the chapter paginates to at least one page.</p>")
            }
        }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""",
            )
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Contents Book</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
    <itemref idref="ch3"/>
  </spine>
</package>""",
            )
            entry(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
  <body>
    <nav epub:type="toc">
      <ol>
        <li><a href="ch1.xhtml">Chapter One</a></li>
        <li><a href="ch2.xhtml">Chapter Two</a>
          <ol>
            <li><a href="ch3.xhtml">Chapter Three</a></li>
          </ol>
        </li>
      </ol>
    </nav>
  </body>
</html>""",
            )
            entry("OEBPS/ch1.xhtml", "<html><body><h1>One</h1>${chapterBody("One")}</body></html>")
            entry("OEBPS/ch2.xhtml", "<html><body><h1>Two</h1>${chapterBody("Two")}</body></html>")
            entry("OEBPS/ch3.xhtml", "<html><body><h1>Three</h1>${chapterBody("Three")}</body></html>")
        }
        return file
    }
}
