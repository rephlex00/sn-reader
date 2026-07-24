package dev.reader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import com.google.common.truth.Truth.assertThat
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

@RunWith(RobolectricTestRunner::class)
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
}
