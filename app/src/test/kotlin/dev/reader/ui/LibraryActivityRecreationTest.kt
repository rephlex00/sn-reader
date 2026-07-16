package dev.reader.ui

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import dev.reader.ReaderApplication
import dev.reader.data.BookEntity
import dev.reader.data.IndexResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric coverage for the P2 Task 5 review's fix-wave-3 decision: the owner ruled out
 * once-per-process indexing entirely. [LibraryActivity] now re-syncs on *every* [LibraryActivity.onStart]
 * — cold start, rotation, or simply returning to the screen — guarded only against a second sync
 * launching while one is already in flight ([ReaderApplication.librarySyncJob]). There is no longer
 * an "already synced" flag to reset between tests; see [ReaderApplication]'s KDoc for why it was
 * deleted rather than kept alongside the job guard.
 *
 * Two seams on [LibraryActivity] make an Activity-recreation test possible without a device or a
 * granted Android 11 all-files-access permission (which Robolectric cannot fake at the
 * `Environment.isExternalStorageManager()` level — no shadow method exists for it in Robolectric
 * 4.16.1): [LibraryActivity.isAllFilesAccessGranted] and [LibraryActivity.createSync], both
 * `protected open` for exactly this reason. [TestableLibraryActivity] overrides both.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryActivityRecreationTest {

    private lateinit var app: ReaderApplication

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as ReaderApplication
    }

    @After
    fun tearDown() {
        // ReaderApplication is a fresh instance per Robolectric test in principle, but its
        // `by lazy` database file lives in a directory Robolectric reuses across tests in the
        // same JVM fork; resetting the guard defensively costs nothing and avoids one test's
        // guard state leaking into another's if that ever changes.
        app.librarySyncJob = null
    }

    @Test
    fun `a recreation still observes the DB (the blank-grid regression)`() {
        // Stand in for "a sync already completed earlier in this process" (the common rotation
        // case): a row is already in the DB before this Activity instance (standing in for the
        // recreated one) is even built. onCreate must start observing regardless of any sync
        // guard state.
        runBlocking { app.database.bookDao().upsertAll(listOf(testBook())) }

        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.create().start().resume()

        idleUntil { controller.get().itemCount == 1 }

        assertThat(controller.get().itemCount).isEqualTo(1)
    }

    @Test
    fun `a sync cancelled by Activity destruction does not strand any state`() {
        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        // Never returns on its own — only cancellation (Activity destruction, below) ends it.
        // This lets the test force the "destroyed mid-sync" window deterministically, instead of
        // racing a real, unpredictably-timed EPUB scan.
        controller.get().sync = { awaitCancellation() }

        controller.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(app.librarySyncJob?.isActive).isTrue()

        // Destroying the Activity cancels its lifecycleScope, which cancels the suspended sync —
        // the rotation/Back-mid-sync scenario the blocker describes. With no "already synced"
        // flag left to strand, the only thing worth asserting is that the job itself reads as no
        // longer active — there's nothing else that could be left half-set.
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(app.librarySyncJob?.isActive).isFalse()
    }

    @Test
    fun `re-entry after a completed sync triggers another sync (the whole point of this change)`() {
        var syncCalls = 0
        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.get().sync = {
            syncCalls++
            IndexResult(added = 0, updated = 0, removed = 0, newlyUnreadable = 0)
        }

        controller.create().start().resume()
        idleUntil { syncCalls == 1 }
        assertThat(syncCalls).isEqualTo(1)

        // Simulate leaving the library (e.g. opening a book, or backgrounding the app) and coming
        // back, without destroying this Activity instance — the exact "side-loaded a book, left,
        // came back" scenario the owner's decision targets. No onCreate the second time, only the
        // stop/start pair.
        controller.pause().stop()
        controller.start().resume()
        idleUntil { syncCalls == 2 }

        assertThat(syncCalls).isEqualTo(2)
    }

    @Test
    fun `two overlapping entries do not launch two concurrent syncs`() {
        // Held open until the test explicitly releases it, so the first sync can be forced to
        // stay in flight while a second onStart fires — the "quick background/foreground bounce"
        // scenario, deterministically, instead of racing a real multi-second scan.
        val releaseFirstSync = CompletableDeferred<Unit>()
        var syncCalls = 0

        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.get().sync = {
            syncCalls++
            releaseFirstSync.await()
            IndexResult(added = 0, updated = 0, removed = 0, newlyUnreadable = 0)
        }

        // onCreate() and onStart() both run back-to-back in the standard Activity lifecycle.
        controller.create().start()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(syncCalls).isEqualTo(1)
        assertThat(app.librarySyncJob?.isActive).isTrue()

        // A second onStart while the first sync is still suspended on releaseFirstSync must not
        // launch a second, concurrent sync racing the first one against the same DAO.
        controller.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(syncCalls).isEqualTo(1)

        releaseFirstSync.complete(Unit)
        idleUntil { app.librarySyncJob?.isActive != true }
        assertThat(syncCalls).isEqualTo(1)
    }

    @Test
    fun `a rotation mid-sync does not overlap the old sync body with a new one`() {
        // The rotation race in full: Job.cancel() (here, the old Activity's destruction
        // cancelling its lifecycleScope) moves the Job to Cancelling, where isActive reads FALSE
        // immediately — while the sync body keeps executing on Dispatchers.IO until its next
        // suspension point. LibraryIndexer's walk() and each extract() are fully blocking, so on
        // a first scan that window is seconds long. A recreated Activity's onStart lands squarely
        // inside it, and a bare isActive check would wave a second, concurrent sync through.
        // The fake below blocks in a NON-suspending section to model exactly that.
        val firstEntered = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val firstUnwound = AtomicBoolean(false)
        val secondEntered = AtomicBoolean(false)
        val secondSawFirstStillRunning = AtomicBoolean(false)

        val first = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        first.get().sync = {
            try {
                withContext(Dispatchers.IO) {
                    firstEntered.countDown()
                    // CountDownLatch.await() is blocking and never observes coroutine
                    // cancellation — like a blocking extractor.extract() call mid-scan.
                    firstRelease.await()
                }
                IndexResult(added = 0, updated = 0, removed = 0, newlyUnreadable = 0)
            } finally {
                firstUnwound.set(true)
            }
        }
        first.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue()

        // The rotation: the old instance is destroyed, cancelling its lifecycleScope, while the
        // sync body is still inside its blocking section. isActive drops to false immediately
        // even though the body is still running — the exact window under test.
        first.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(app.librarySyncJob?.isActive).isFalse()
        assertThat(firstUnwound.get()).isFalse()

        // The recreated Activity's onStart fires runSync inside that window.
        val second = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        second.get().sync = {
            secondSawFirstStillRunning.set(!firstUnwound.get())
            secondEntered.set(true)
            IndexResult(added = 0, updated = 0, removed = 0, newlyUnreadable = 0)
        }
        second.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        // The invariant: no two sync bodies in flight, ever. The new sync must not have entered
        // while the old body is still unwinding.
        assertThat(secondEntered.get()).isFalse()

        firstRelease.countDown()
        idleUntil { secondEntered.get() }
        assertThat(secondEntered.get()).isTrue()
        assertThat(secondSawFirstStillRunning.get()).isFalse()

        second.pause().stop().destroy()
    }

    /** [LibraryActivity] subclass whose test seams are set per-test via mutable fields. */
    private class TestableLibraryActivity : LibraryActivity() {
        var accessGranted = true
        var sync: suspend () -> IndexResult =
            { IndexResult(added = 0, updated = 0, removed = 0, newlyUnreadable = 0) }

        override fun isAllFilesAccessGranted(): Boolean = accessGranted
        override fun createSync(): suspend () -> IndexResult = sync

        /** Exposes the protected [adapter]'s count — only a subclass can reach it at all. */
        val itemCount: Int get() = adapter.itemCount
    }

    /**
     * Bridges Robolectric's paused main-looper scheduler with the real background threads Room's
     * Flow query and [BookGridAdapter]'s `AsyncListDiffer` post back to it from — neither is tied
     * to Robolectric's shadow scheduler, so a single `idle()` call cannot be trusted to have
     * already-posted work land before it runs.
     */
    private fun idleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun testBook(path: String = "/Document/Books/a.epub") = BookEntity(
        path = path,
        sizeBytes = 1_000L,
        modifiedAtMs = 1_700_000_000_000L,
        title = "A Book",
        author = "An Author",
        coverPath = null,
        spineIndex = 0,
        charOffset = 0,
        unreadable = false,
        unreadableReason = null,
        addedAtMs = 1_700_000_000_000L,
        lastOpenedAtMs = null,
    )
}
