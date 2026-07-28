package dev.reader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import dev.reader.R
import dev.reader.data.BookEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

// NATIVE graphics: Task 28's scrubber wiring decodes real comic pages for the floating preview
// (ComicPreviewLoader -> ComicPageDecoder -> BitmapFactory), and this codebase has already been
// bitten once by Robolectric's shadow BitmapFactory silently faking a successful decode where the
// real decoder would return null (the blank-page bug behind ComicPageDecoder's bounds-pass elvis
// trap). Applied at the class level, matching ComicPreviewLoaderTest/PageViewTest/
// ChapterScrubberViewTest, so every decode this Activity triggers — including the ordinary
// page-turn tests already below — exercises the real decode path.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicActivityTest {
    // :formats has no testFixtures wiring to :app (see dev.reader.formats.comic.buildCbz in
    // formats/src/test), so this mirrors ComicMetadataExtractorTest's own local copy rather than
    // importing across a module boundary that doesn't exist.
    private fun buildCbz(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    private fun png(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    private fun cbz(name: String, pages: Int, comicInfo: String? = null): File {
        val ctx = RuntimeEnvironment.getApplication()
        val entries = LinkedHashMap<String, ByteArray>()
        for (i in 1..pages) entries["%03d.png".format(i)] = png(300, 450)
        if (comicInfo != null) entries["ComicInfo.xml"] = comicInfo.toByteArray()
        return File(ctx.filesDir, name).also { buildCbz(it, entries) }
    }

    private fun launch(path: String) =
        Robolectric.buildActivity(
            ComicActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), ComicActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_PATH, path),
        ).setup()

    private fun drainMain() = shadowOf(Looper.getMainLooper()).idle()

    /** The [ComicPageView], the tap sink production drives too — the first child of the content
     *  [ViewGroup], mirroring ReaderActivityTest's own `pageViewOf`. */
    private fun comicPageViewOf(activity: ComicActivity): ComicPageView {
        val container = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        return container.getChildAt(0) as ComicPageView
    }

    /** Forces a real layout pass at the given size, mirroring ReaderActivityTest's `layOutAt` —
     *  toggling the overlay from GONE to VISIBLE alone leaves the scrubber measured at 0x0 under
     *  Robolectric, which never runs a Choreographer pass on its own. */
    private fun layOutComicAt(controller: ActivityController<ComicActivity>, widthPx: Int, heightPx: Int) {
        val decor = controller.get().window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, widthPx, heightPx)
        drainMain()
    }

    /** Dispatches a real single-pointer touch to a laid-out [ChapterScrubberView] — the grace-flush
     *  test needs the view's own gesture state machine (DOWN/UP), not the callback seams the other
     *  scrub tests invoke directly. y is an arbitrary point inside the 88dp-tall strip. Mirrors
     *  ReaderActivityTest's own `touchScrubber`. */
    private fun touchScrubber(scrubber: ChapterScrubberView, action: Int, x: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, 30f, 0)
        scrubber.onTouchEvent(event)
        event.recycle()
    }

    /** Inverts the view's fractionAt geometry (padding-based track ends) for a real, laid-out
     *  scrubber — this one carries the 16dp horizontal padding from overlay_comic.xml, at
     *  Robolectric's default density 1.0 (16dp == 16px). Mirrors ReaderActivityTest's own
     *  `xForFraction`. */
    private fun xForFraction(scrubber: ChapterScrubberView, fraction: Float): Float {
        val left = scrubber.paddingLeft.toFloat()
        val right = scrubber.width - scrubber.paddingRight.toFloat()
        return left + fraction * (right - left)
    }

    /**
     * ComicActivity's open/turn path is a real multi-hop coroutine (several `withContext
     * (Dispatchers.IO)` file/DB reads, then a `Dispatchers.Default` bitmap decode) running under
     * Robolectric's PAUSED main looper. Each hop back to the main thread is a Runnable that sits
     * queued until something idles the looper, so a single [drainMain] only advances the chain one
     * hop — exactly the flake [ReaderActivityTest]'s `idleUntil` (and this project's own "never
     * block the test thread in Robolectric" lesson) already documents for this pattern. Poll +
     * idle, never a bare single idle, for anything that crosses a dispatcher hop.
     */
    private fun idleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            drainMain()
            Thread.sleep(20)
        }
        check(condition()) { "condition never became true within ${timeoutMs}ms" }
    }

    @Test fun `opens at the first page`() {
        val file = cbz("a.cbz", 5)
        val a = launch(file.path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        assertThat(a.currentPageForTest).isEqualTo(0)
    }

    @Test fun `a right-side tap advances, a left-side tap goes back (LTR)`() {
        val a = launch(cbz("b.cbz", 5).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        a.onTapForTest(TapZone.NEXT)
        idleUntil { a.currentPageForTest == 1 }
        assertThat(a.currentPageForTest).isEqualTo(1)
        a.onTapForTest(TapZone.PREVIOUS)
        idleUntil { a.currentPageForTest == 0 }
        assertThat(a.currentPageForTest).isEqualTo(0)
    }

    @Test fun `manga direction flips the tap zones`() {
        val a = launch(cbz("m.cbz", 5, "<ComicInfo><Manga>YesAndRightToLeft</Manga></ComicInfo>").path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        a.onTapForTest(TapZone.PREVIOUS) // left tap = forward in RTL
        idleUntil { a.currentPageForTest == 1 }
        assertThat(a.currentPageForTest).isEqualTo(1)
    }

    @Test fun `turning past the last page does nothing`() {
        val a = launch(cbz("s.cbz", 2).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        a.onTapForTest(TapZone.NEXT)
        idleUntil { a.currentPageForTest == 1 }
        // Already at the last page: out of range, so onTap never launches a coroutine at all — a
        // plain drain (nothing further to wait for) is the correct check here, not idleUntil.
        a.onTapForTest(TapZone.NEXT); drainMain()
        assertThat(a.currentPageForTest).isEqualTo(1)
    }

    @Test fun `toggling direction flips tap zones and persists the override`() = runBlocking {
        val file = cbz("dir.cbz", 5)
        val dao = (RuntimeEnvironment.getApplication() as dev.reader.ReaderApplication).database.bookDao()
        dao.upsertAll(listOf(BookEntity(
            path = file.path, sizeBytes = file.length(), modifiedAtMs = 0, title = "d", author = null,
            coverPath = null, spineIndex = 0, charOffset = 0, unreadable = false,
            unreadableReason = null, addedAtMs = 0, lastOpenedAtMs = null,
        )))
        val a = launch(file.path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        assertThat(a.rtlForTest).isFalse()
        a.toggleDirectionForTest()
        // rtl itself flips synchronously; the DB write is launched onto the app-scoped
        // positionWriteScope (a real Dispatchers.IO pool, not the Robolectric main looper), so
        // polling the row directly — not drainMain — is what actually waits for it, matching
        // ReaderActivityTest's rowFor/idleUntil pattern for the same kind of write.
        assertThat(a.rtlForTest).isTrue()
        a.onTapForTest(TapZone.PREVIOUS) // now left = forward
        idleUntil { a.currentPageForTest == 1 }
        assertThat(a.currentPageForTest).isEqualTo(1)
        idleUntil { runBlocking { dao.getByPath(file.path) }?.rightToLeftOverride == true }
        assertThat(dao.getByPath(file.path)!!.rightToLeftOverride).isTrue()
    }

    @Test fun `bookmarking toggles the current page on and off`() = runBlocking {
        val file = cbz("bm.cbz", 5)
        // BookmarkEntity.bookPath is a CASCADE foreign key to books.path (enforcement ON — see
        // LibraryDatabase_Impl's `PRAGMA foreign_keys = ON`), so a bookmark insert needs a books
        // row first, same as `toggling direction...` and `resumes at the stored page` below.
        val dao = (RuntimeEnvironment.getApplication() as dev.reader.ReaderApplication).database.bookDao()
        dao.upsertAll(listOf(BookEntity(
            path = file.path, sizeBytes = file.length(), modifiedAtMs = 0, title = "bm", author = null,
            coverPath = null, spineIndex = 0, charOffset = 0, unreadable = false,
            unreadableReason = null, addedAtMs = 0, lastOpenedAtMs = null,
        )))
        val a = launch(file.path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        a.toggleBookmarkForTest()
        idleUntil { a.bookmarkedPagesForTest.contains(0) }
        assertThat(a.bookmarkedPagesForTest).containsExactly(0)
        a.toggleBookmarkForTest()
        idleUntil { a.bookmarkedPagesForTest.isEmpty() }
        assertThat(a.bookmarkedPagesForTest).isEmpty()
    }

    @Test fun `a bookmark write no-ops instead of crashing when the library row is gone`() = runBlocking {
        // Regression for the library-sync delete race: LibraryActivity's background
        // LibraryIndexer.sync() runs on a scope cancelled at ON_DESTROY, not ON_STOP, so it can
        // still be running while this reader is foregrounded. If it decides the open file is
        // gone, it calls dao.deleteByPaths — which cascades away this book's bookmarks, since
        // BookmarkEntity.bookPath is a CASCADE foreign key to books.path. A stale in-memory
        // `existing` bookmark plus an unguarded insert would then throw an FK violation inside
        // lifecycleScope.launch with nothing to catch it. toggleBookmark()'s inLibrary check (and
        // its try/catch backstop) should turn that into a silent no-op instead.
        val file = cbz("gone.cbz", 5)
        val dao = (RuntimeEnvironment.getApplication() as dev.reader.ReaderApplication).database.bookDao()
        dao.upsertAll(listOf(BookEntity(
            path = file.path, sizeBytes = file.length(), modifiedAtMs = 0, title = "gone", author = null,
            coverPath = null, spineIndex = 0, charOffset = 0, unreadable = false,
            unreadableReason = null, addedAtMs = 0, lastOpenedAtMs = null,
        )))
        val a = launch(file.path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        a.toggleBookmarkForTest()
        idleUntil { a.bookmarkedPagesForTest.contains(0) }
        assertThat(a.bookmarkedPagesForTest).containsExactly(0)

        // Simulate the sync deleting the books row (and cascading away the bookmark) while the
        // comic is still open. This suspend call runs to completion here, in the same runBlocking
        // coroutine, so the row is gone before the next line.
        dao.deleteByPaths(listOf(file.path))

        a.toggleBookmarkForTest()
        idleUntil { a.bookmarkedPagesForTest.isEmpty() }
        assertThat(a.bookmarkedPagesForTest).isEmpty()
    }

    @Test fun `every overlay control from the comic chrome parity layout exists by its new id`() {
        val a = launch(cbz("chrome.cbz", 3).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        assertThat(a.findViewById<View>(R.id.comic_overlay)).isNotNull()
        assertThat(a.findViewById<View>(R.id.comic_back)).isNotNull()
        assertThat(a.findViewById<View>(R.id.comic_title)).isNotNull()
        assertThat(a.findViewById<View>(R.id.comic_bookmark_toggle)).isNotNull()
        assertThat(a.findViewById<View>(R.id.comic_bookmarks_button)).isNotNull()
        assertThat(a.findViewById<View>(R.id.comic_direction_button)).isNotNull()
        assertThat(a.findViewById<View>(R.id.comic_readout)).isNotNull()
        assertThat(a.findViewById<View>(R.id.comic_scrubber_back)).isNotNull()
        assertThat(a.findViewById<View>(R.id.comic_scrubber)).isNotNull()
        assertThat(a.findViewById<View>(R.id.comic_preview)).isNotNull()
    }

    @Test fun `the readout reads page 1 of N at open`() {
        val a = launch(cbz("readout.cbz", 7).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        val readout = a.findViewById<TextView>(R.id.comic_readout)
        assertThat(readout.text.toString()).isEqualTo("page 1 of 7")
    }

    @Test fun `resumes at the stored page`() = runBlocking {
        val file = cbz("r.cbz", 10)
        val dao = (RuntimeEnvironment.getApplication() as dev.reader.ReaderApplication).database.bookDao()
        dao.upsertAll(listOf(BookEntity(
            path = file.path, sizeBytes = file.length(), modifiedAtMs = 0, title = "r",
            author = null, coverPath = null, spineIndex = 4, charOffset = 0,
            unreadable = false, unreadableReason = null, addedAtMs = 0, lastOpenedAtMs = null,
        )))
        val a = launch(file.path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        assertThat(a.currentPageForTest).isEqualTo(4)
    }

    // -- Task 28: the comic timeline — scrubber, preview and ↩ -----------------------------------

    @Test fun `comicPageForFraction maps the ends and the middle of the track`() {
        assertThat(comicPageForFraction(0f, 10)).isEqualTo(0)
        assertThat(comicPageForFraction(1f, 10)).isEqualTo(9)
        assertThat(comicPageForFraction(0.5f, 10)).isEqualTo(4)
        assertThat(comicPageForFraction(0.5f, 0)).isEqualTo(0) // no pages: never a crash
    }

    @Test fun `comicPageForFraction inverts comicProgressForPage on its own grid`() {
        for (page in 0 until 10) {
            val progress = comicProgressForPage(page, 10)
            assertThat(comicPageForFraction(progress, 10)).isEqualTo(page)
        }
    }

    @Test fun `toggling chrome shows and hides the overlay, refreshing the page once on close`() {
        val a = launch(cbz("chrome.cbz", 5).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        val overlay = a.findViewById<View>(R.id.comic_overlay)
        val pv = comicPageViewOf(a)
        assertThat(overlay.visibility).isEqualTo(View.GONE)

        a.toggleChromeForTest()
        assertThat(overlay.visibility).isEqualTo(View.VISIBLE)

        val refreshesBefore = pv.fullRefreshCount
        a.toggleChromeForTest()
        assertThat(overlay.visibility).isEqualTo(View.GONE)
        assertThat(pv.fullRefreshCount).isGreaterThan(refreshesBefore)
    }

    // -- Lifecycle parity with ReaderActivity (onResume/onPause fast mode, onPause grace flush) ----

    @Test fun `onPause restores the screen mode even with the overlay still open`() {
        val controller = launch(cbz("pause-fastmode.cbz", 5).path)
        val a = controller.get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        val epd = object : EpdRefresher {
            override val available = true
            var fastModeHeld = false
            override fun cleanRefresh(): Boolean = true
            override fun enterFastMode(): Boolean { fastModeHeld = true; return true }
            override fun exitFastMode(): Boolean { fastModeHeld = false; return true }
        }
        comicPageViewOf(a).epd = epd

        a.toggleChromeForTest()
        assertThat(epd.fastModeHeld).isTrue()

        controller.pause()

        // The screen mode is device-wide runtime state. A leaked fast mode degrades the whole
        // device UI, so the restore must ride on the last callback Android guarantees, exactly as
        // ReaderActivity.onPause does — not on a close handler the user can bypass by swiping away.
        assertThat(epd.fastModeHeld).isFalse()
    }

    @Test fun `onResume re-enters fast mode when the overlay is still open`() {
        val controller = launch(cbz("resume-fastmode.cbz", 5).path)
        val a = controller.get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        val epd = object : EpdRefresher {
            override val available = true
            var fastModeHeld = false
            override fun cleanRefresh(): Boolean = true
            override fun enterFastMode(): Boolean { fastModeHeld = true; return true }
            override fun exitFastMode(): Boolean { fastModeHeld = false; return true }
        }
        comicPageViewOf(a).epd = epd

        a.toggleChromeForTest()
        assertThat(epd.fastModeHeld).isTrue()

        controller.pause()
        // onPause unconditionally gives the mode back — confirmed by the test above.
        assertThat(epd.fastModeHeld).isFalse()

        controller.resume()

        // A resume with the chrome still open must re-enter fast mode, or every chrome interaction
        // until the overlay is closed and reopened would run on the slow, full-quality waveform —
        // exactly the ComicActivity had no onResume at all to prevent before this fix.
        assertThat(epd.fastModeHeld).isTrue()
    }

    @Test fun `onPause flushes a page turn still inside its commit grace window`() {
        val controller = launch(cbz("pause-flush.cbz", 10).path)
        val a = controller.get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        a.toggleChromeForTest()
        // toggleChromeForTest only flips visibility (GONE -> VISIBLE); Robolectric does not run a
        // real Choreographer pass on its own, so the scrubber — measured out at 0x0 while GONE —
        // needs an explicit re-layout before its real pixel width can back xForFraction below.
        layOutComicAt(controller, 600, 800)
        val scrubber = a.findViewById<ChapterScrubberView>(R.id.comic_scrubber)

        touchScrubber(scrubber, MotionEvent.ACTION_DOWN, xForFraction(scrubber, 0.9f))
        touchScrubber(scrubber, MotionEvent.ACTION_UP, xForFraction(scrubber, 0.9f))
        // Grace is open; the app is backgrounded (Home, app switcher, an incoming call) before it
        // expires. onPause must resolve it now — the reverse bug silently discarded a lift like
        // this one, exactly as a lift the reader already committed to should never be discarded.
        controller.pause()

        idleUntil { a.currentPageForTest == comicPageForFraction(0.9f, 10) }
        assertThat(a.currentPageForTest).isEqualTo(comicPageForFraction(0.9f, 10))
    }

    @Test fun `the readout shows page and percent mid-drag, and reverts to rest after a commit`() {
        val a = launch(cbz("drag-readout.cbz", 10).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        val readout = a.findViewById<TextView>(R.id.comic_readout)
        assertThat(readout.text.toString()).isEqualTo("page 1 of 10")

        a.onScrubStartForTest()
        a.onScrubMoveForTest(0.55f)
        assertThat(readout.text.toString()).isEqualTo("page 6 of 10 · 55%")

        a.onScrubCommitForTest(0.55f)
        idleUntil { a.currentPageForTest == 5 }
        assertThat(a.currentPageForTest).isEqualTo(5)
        assertThat(readout.text.toString()).isEqualTo("page 6 of 10")
    }

    @Test fun `dragging the scrubber never repaints the page`() {
        val a = launch(cbz("noreflow.cbz", 20).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        val before = a.pagesShownForTest.toList()

        a.onScrubStartForTest()
        a.onScrubMoveForTest(0.1f)
        a.onScrubMoveForTest(0.5f)
        a.onScrubMoveForTest(0.9f)
        // A single drainMain only advances the decode chain one hop — nowhere near enough for a
        // violating onScrubMoved (one that called showPage) to have its Dispatchers.Default decode
        // round-trip back to main and append to pagesShownForTest. Poll on the last hover's own
        // decode job — a real dispatcher hop of the same shape — to give a violation many chances
        // to land before asserting it didn't.
        idleUntil { a.previewDecodeJobForTest?.isCompleted == true }

        assertThat(a.pagesShownForTest).isEqualTo(before)
    }

    @Test fun `a commit turns the page to the dragged fraction`() {
        val a = launch(cbz("commit.cbz", 8).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }

        a.onScrubStartForTest()
        a.onScrubMoveForTest(1f)
        a.onScrubCommitForTest(1f)
        idleUntil { a.currentPageForTest == 7 }

        assertThat(a.currentPageForTest).isEqualTo(7)
    }

    @Test fun `a commit that lands back on the origin page does not turn the page or push a jump`() {
        val a = launch(cbz("noop-commit.cbz", 10).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        val before = a.pagesShownForTest.toList()

        a.onScrubStartForTest()
        a.onScrubMoveForTest(0.1f) // comicPageForFraction(0.1, 10) == 0, the page already showing
        // Captured before the commit cancels it: waiting for ITS completion (a real dispatcher hop,
        // same as `dragging the scrubber never repaints the page`) gives a violating no-op-commit
        // branch (one that called showPage anyway) many pump chances to land before the assertion —
        // a bare drainMain would return long before such a decode round-trips back to main.
        val moveJob = a.previewDecodeJobForTest
        a.onScrubCommitForTest(0.1f)
        idleUntil { moveJob == null || moveJob.isCompleted }

        assertThat(a.pagesShownForTest).isEqualTo(before)
        assertThat(a.findViewById<View>(R.id.comic_scrubber_back).visibility).isEqualTo(View.GONE)
    }

    @Test fun `back-jump returns to the scrub origin and hides once the stack empties`() {
        val a = launch(cbz("jump.cbz", 10).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        val backControl = a.findViewById<View>(R.id.comic_scrubber_back)
        assertThat(backControl.visibility).isEqualTo(View.GONE)

        a.onScrubStartForTest()
        a.onScrubMoveForTest(0.9f)
        a.onScrubCommitForTest(0.9f)
        idleUntil { a.currentPageForTest == 8 }
        assertThat(backControl.visibility).isEqualTo(View.VISIBLE)

        a.backJumpForTest()
        idleUntil { a.currentPageForTest == 0 }
        assertThat(a.currentPageForTest).isEqualTo(0)
        assertThat(backControl.visibility).isEqualTo(View.GONE)
    }

    @Test fun `abandoning a scrub restores the resting readout and thumb without turning the page`() {
        val a = launch(cbz("abandon.cbz", 10).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        val readout = a.findViewById<TextView>(R.id.comic_readout)
        val before = a.pagesShownForTest.toList()

        a.onScrubStartForTest()
        a.onScrubMoveForTest(0.7f)
        assertThat(readout.text.toString()).isNotEqualTo("page 1 of 10")
        // Captured before abandonScrub cancels it — same reasoning as the no-op-commit test above:
        // waiting for its completion gives a violating abandonScrub (one that called showPage) many
        // pump chances to land before the assertion, instead of a single drainMain that returns long
        // before such a decode could round-trip back to main.
        val moveJob = a.previewDecodeJobForTest

        a.abandonScrubForTest()
        idleUntil { moveJob == null || moveJob.isCompleted }

        assertThat(a.pagesShownForTest).isEqualTo(before)
        assertThat(readout.text.toString()).isEqualTo("page 1 of 10")
    }

    @Test fun `bookmark glyphs land at the bookmarked pages' fractions`() = runBlocking {
        val file = cbz("glyphs.cbz", 10)
        val dao = (RuntimeEnvironment.getApplication() as dev.reader.ReaderApplication).database.bookDao()
        dao.upsertAll(listOf(BookEntity(
            path = file.path, sizeBytes = file.length(), modifiedAtMs = 0, title = "g", author = null,
            coverPath = null, spineIndex = 0, charOffset = 0, unreadable = false,
            unreadableReason = null, addedAtMs = 0, lastOpenedAtMs = null,
        )))
        val a = launch(file.path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        a.toggleBookmarkForTest() // bookmarks page 0 -> fraction (0+1)/10 == 0.1
        idleUntil { a.bookmarkedPagesForTest.contains(0) }

        val scrubber = a.findViewById<ChapterScrubberView>(R.id.comic_scrubber)
        idleUntil { scrubber.bookmarkFractionsForTest.isNotEmpty() }
        // `= runBlocking { ... }` infers the function's return type from the block's last
        // expression: containsExactly returns a chainable `Ordered`, not Unit, which JUnit rejects
        // as an invalid @Test signature — the trailing Unit keeps this a void test method.
        assertThat(scrubber.bookmarkFractionsForTest).containsExactly(0.1f)
        Unit
    }

    @Test fun `a new hover cancels the previous preview decode`() {
        val a = launch(cbz("cancel.cbz", 10).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }

        a.onScrubStartForTest()
        a.onScrubMoveForTest(0.1f)
        val firstJob = a.previewDecodeJobForTest
        assertThat(firstJob).isNotNull()
        assertThat(firstJob!!.isCancelled).isFalse()

        a.onScrubMoveForTest(0.9f) // a different page: must cancel the first hover's decode
        assertThat(firstJob.isCancelled).isTrue()
        assertThat(a.previewDecodeJobForTest).isNotSameInstanceAs(firstJob)

        drainMain() // let the surviving decode finish; nothing left to crash on
    }

    @Test fun `re-hovering the same page does not relaunch a decode`() {
        val a = launch(cbz("same-page.cbz", 10).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }

        a.onScrubStartForTest()
        a.onScrubMoveForTest(0.11f) // page 0 (comicPageForFraction(0.11, 10) == round(1.1)-1 == 0)
        val firstJob = a.previewDecodeJobForTest
        a.onScrubMoveForTest(0.12f) // still page 0 — same fraction bucket

        assertThat(a.previewDecodeJobForTest).isSameInstanceAs(firstJob)
        drainMain()
    }

    // -- Task 29: the comic bookmarks panel matches the reader's -----------------------------------

    /** Measures/lays out the comic Bookmarks RecyclerView (Robolectric does not on its own) and
     *  returns the row's itemView at [position], so a child click lands on a real holder — mirrors
     *  ReaderActivityTest's own layOutHighlightRow/clickTocRow helpers. */
    private fun layOutBookmarkRow(activity: ComicActivity, position: Int): View {
        val list = activity.findViewById<RecyclerView>(R.id.comic_bookmarks_list)
        list.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 800, 600)
        return (
            list.findViewHolderForAdapterPosition(position)
                ?: error("no bookmark row at position $position after layout")
            ).itemView
    }

    @Test fun `the bookmarks panel's close dismisses an empty panel without finishing the activity`() {
        // The defect this task fixes: a bare ScrollView list had no ✕, so on a device with no
        // hardware Back the only way out of an empty bookmarks panel was to exit the whole reader.
        val a = launch(cbz("empty-panel.cbz", 5).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }

        a.findViewById<View>(R.id.comic_bookmarks_button).performClick()
        val panel = a.findViewById<View>(R.id.comic_bookmarks_panel)
        assertThat(panel.visibility).isEqualTo(View.VISIBLE)

        a.findViewById<View>(R.id.comic_bookmarks_close).performClick()
        assertThat(panel.visibility).isEqualTo(View.GONE)
        assertThat(a.isFinishing).isFalse()
    }

    @Test fun `the bookmarks panel shows its empty state when there are no bookmarks`() {
        val a = launch(cbz("empty-state.cbz", 5).path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }

        a.findViewById<View>(R.id.comic_bookmarks_button).performClick()
        assertThat(a.findViewById<View>(R.id.comic_bookmarks_empty).visibility).isEqualTo(View.VISIBLE)
        assertThat(a.findViewById<View>(R.id.comic_bookmarks_list).visibility).isEqualTo(View.GONE)
    }

    @Test fun `a bookmark jump pushes the origin page, and back returns to it`() = runBlocking {
        val file = cbz("bookmark-jump.cbz", 10)
        val dao = (RuntimeEnvironment.getApplication() as dev.reader.ReaderApplication).database.bookDao()
        dao.upsertAll(listOf(BookEntity(
            path = file.path, sizeBytes = file.length(), modifiedAtMs = 0, title = "bj", author = null,
            coverPath = null, spineIndex = 0, charOffset = 0, unreadable = false,
            unreadableReason = null, addedAtMs = 0, lastOpenedAtMs = null,
        )))
        val a = launch(file.path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }

        // Move to page 5 (via a scrub commit, itself a jump) and bookmark it, then pop straight back
        // to page 0 so the jump stack starts empty and page 0 is a known origin.
        a.onScrubStartForTest()
        a.onScrubMoveForTest(0.6f) // comicPageForFraction(0.6, 10) == 5
        a.onScrubCommitForTest(0.6f)
        idleUntil { a.currentPageForTest == 5 }
        a.toggleBookmarkForTest()
        idleUntil { a.bookmarkedPagesForTest.contains(5) }
        a.backJumpForTest()
        idleUntil { a.currentPageForTest == 0 }
        val backControl = a.findViewById<View>(R.id.comic_scrubber_back)
        assertThat(backControl.visibility).isEqualTo(View.GONE)

        // Tap the page-5 bookmark row from the panel: a bookmark jump must push page 0 (the page
        // being left) exactly like a Contents/highlight jump does in the EPUB reader — this is the
        // parity gap ↩ existed to close.
        a.findViewById<View>(R.id.comic_bookmarks_button).performClick()
        layOutBookmarkRow(a, 0).findViewById<View>(R.id.bookmark_label).performClick()
        idleUntil { a.currentPageForTest == 5 }
        assertThat(a.currentPageForTest).isEqualTo(5)
        assertThat(backControl.visibility).isEqualTo(View.VISIBLE)

        a.backJumpForTest()
        idleUntil { a.currentPageForTest == 0 }
        assertThat(a.currentPageForTest).isEqualTo(0)
        assertThat(backControl.visibility).isEqualTo(View.GONE)
    }

    @Test fun `a bookmark row's ✕ deletes that bookmark and the list refreshes`() = runBlocking {
        val file = cbz("row-delete.cbz", 5)
        val dao = (RuntimeEnvironment.getApplication() as dev.reader.ReaderApplication).database.bookDao()
        dao.upsertAll(listOf(BookEntity(
            path = file.path, sizeBytes = file.length(), modifiedAtMs = 0, title = "rd", author = null,
            coverPath = null, spineIndex = 0, charOffset = 0, unreadable = false,
            unreadableReason = null, addedAtMs = 0, lastOpenedAtMs = null,
        )))
        val a = launch(file.path).get()
        a.openComic()
        idleUntil { a.pagesShownForTest.isNotEmpty() }
        a.toggleBookmarkForTest()
        idleUntil { a.bookmarkedPagesForTest.contains(0) }

        a.findViewById<View>(R.id.comic_bookmarks_button).performClick()
        assertThat(a.findViewById<View>(R.id.comic_bookmarks_list).visibility).isEqualTo(View.VISIBLE)
        layOutBookmarkRow(a, 0).findViewById<View>(R.id.bookmark_delete).performClick()

        idleUntil { a.bookmarkedPagesForTest.isEmpty() }
        assertThat(a.bookmarkedPagesForTest).isEmpty()
        assertThat(a.findViewById<View>(R.id.comic_bookmarks_empty).visibility).isEqualTo(View.VISIBLE)
        Unit
    }
}
