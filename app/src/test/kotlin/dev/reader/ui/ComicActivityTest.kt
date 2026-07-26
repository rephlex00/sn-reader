package dev.reader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
        assertThat(a.findViewById<View>(R.id.comic_bookmark_button)).isNotNull()
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
        drainMain()

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
        a.onScrubCommitForTest(0.1f)
        drainMain()

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

        a.abandonScrubForTest()
        drainMain()

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
}
