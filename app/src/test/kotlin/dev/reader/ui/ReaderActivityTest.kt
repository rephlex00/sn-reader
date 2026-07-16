package dev.reader.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import com.google.common.truth.Truth.assertThat
import dev.reader.engine.RenderConfig
import dev.reader.formats.epub.EpubDocument
import dev.reader.formats.epub.EpubException
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
}
