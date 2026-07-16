package dev.reader.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import dev.reader.R
import dev.reader.ReaderApplication
import dev.reader.data.BookEntity
import dev.reader.engine.ReadingState
import dev.reader.engine.RenderConfig
import dev.reader.engine.TocEntry
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
import java.util.concurrent.TimeUnit
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
        idleUntil { controller.get().findFirstCalls > 0 }

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
        assertThat(activity.openEntered!!.await(5, TimeUnit.SECONDS)).isTrue()

        // Back-press/destroy while open() is still in flight on Dispatchers.IO: lifecycleScope
        // is cancelled, but the blocking open only returns after the latch is released.
        controller.pause().stop().destroy()
        activity.blockOpen!!.countDown()

        // The CancellationException branch must close the returned document — `document = doc`
        // never ran, so onDestroy saw null and this branch is the only thing standing between a
        // back-press during load and a leaked ZipFile. Observable as: reading from the document
        // now fails (its ZipFile is closed), where the test above proves an uncancelled one reads
        // fine.
        idleUntil {
            activity.openedDocuments.size == 1 &&
                runCatching { activity.openedDocuments.single().chapter(0, testRenderConfig()) }.isFailure
        }
        assertThat(
            runCatching { activity.openedDocuments.single().chapter(0, testRenderConfig()) }.isFailure,
        ).isTrue()
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
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Couldn't open this book: boom")
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
    fun `a full-panel refresh fires every REFRESH_CADENCE page turns`() {
        val controller = openedMultiPage()
        val activity = controller.get()
        val pv = pageViewOf(activity)
        assertThat(pv.fullRefreshCount).isEqualTo(0)

        // Alternate NEXT/PREVIOUS on a 2-page book: each is a genuine turn (there is always
        // somewhere to go between pages 0 and 1), so 8 of them reaches the cadence exactly once.
        repeat(8) { i -> pv.onTap!!.invoke(if (i % 2 == 0) TapZone.NEXT else TapZone.PREVIOUS) }
        assertThat(pv.fullRefreshCount).isEqualTo(1)

        // The counter reset: the next 8 turns fire it again, the 7 in between do not.
        repeat(7) { i -> pv.onTap!!.invoke(if (i % 2 == 0) TapZone.NEXT else TapZone.PREVIOUS) }
        assertThat(pv.fullRefreshCount).isEqualTo(1)
        pv.onTap!!.invoke(TapZone.NEXT)
        assertThat(pv.fullRefreshCount).isEqualTo(2)
    }

    @Test
    fun `overlay toggles do not count toward the refresh cadence`() {
        val controller = openedMultiPage()
        val activity = controller.get()
        val pv = pageViewOf(activity)

        // Center taps only toggle the overlay — they are not page turns, so no number of them
        // reaches the ghost-clear cadence. (Each pair of toggles returns to the hidden state.)
        repeat(20) { pv.onTap!!.invoke(TapZone.TOGGLE_OVERLAY) }
        assertThat(pv.fullRefreshCount).isEqualTo(0)
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
        // Reflects current (default) values: size readout and a toggle label read the live prefs.
        assertThat(activity.findViewById<TextView>(R.id.size_value).text.toString()).isEqualTo("34px")
        assertThat(activity.findViewById<TextView>(R.id.toggle_justify).text.toString()).isEqualTo("Justify: On")

        activity.findViewById<View>(R.id.settings_button).performClick()
        assertThat(sheet.visibility).isEqualTo(View.GONE)
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
    fun `flipping the publisher-styling toggle writes the pref and updates its label`() {
        clearReaderPrefs()
        val controller = openedMultiPage()
        val activity = controller.get()
        pageViewOf(activity).onTap!!.invoke(TapZone.TOGGLE_OVERLAY)
        activity.findViewById<View>(R.id.settings_button).performClick()
        assertThat(activity.findViewById<TextView>(R.id.toggle_publisher).text.toString())
            .isEqualTo("Publisher styling: On")

        activity.findViewById<View>(R.id.toggle_publisher).performClick()

        assertThat(ReaderPrefs(RuntimeEnvironment.getApplication()).publisherStyling).isFalse()
        assertThat(activity.findViewById<TextView>(R.id.toggle_publisher).text.toString())
            .isEqualTo("Publisher styling: Off")
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

        val rows = tocRows(toc, currentSpineIndex = 1)

        // Order and depth are passed through untouched.
        assertThat(rows.map { it.title }).containsExactly("One", "Two", "Two-a").inOrder()
        assertThat(rows.map { it.depth }).containsExactly(0, 0, 1).inOrder()
        // Only the entry whose chapter is the current one is marked.
        assertThat(rows.map { it.isCurrent }).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun `tocRows on an empty toc is empty (the No contents case)`() {
        assertThat(tocRows(emptyList(), currentSpineIndex = 0)).isEmpty()
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
    private fun openedMultiPage(): ActivityController<TestableReaderActivity> {
        val book = multiPageEpub(tempFolder.newFile("book.epub"))
        val controller = readerFor(intentWithExtra(book.path))
        launchAndLayOut(controller)
        idleUntil { scrubberTextOf(controller.get()).isNotEmpty() }
        return controller
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
    private fun launchAndLayOut(controller: ActivityController<TestableReaderActivity>) {
        controller.setup()
        val decor = controller.get().window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 800, 600)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Same shape as ReaderActivity's production RenderConfig, at the test's fixed 800x600. */
    private fun testRenderConfig() = RenderConfig(
        fontFamily = "serif",
        textSizePx = 34f,
        lineSpacingMultiplier = 1.4f,
        marginPx = 48,
        justified = true,
        hyphenated = true,
        viewportWidthPx = 800,
        viewportHeightPx = 600,
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
