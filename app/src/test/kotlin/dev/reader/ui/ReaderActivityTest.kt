package dev.reader.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.google.common.truth.Truth.assertThat
import dev.reader.ReaderApplication
import dev.reader.data.BookEntity
import dev.reader.engine.RenderConfig
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

    // -- Harness --------------------------------------------------------------------------------

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

    /** The [PageView] the Activity set as its content view — the tap sink production drives too. */
    private fun pageViewOf(activity: ReaderActivity): PageView =
        (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as PageView)

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
}
