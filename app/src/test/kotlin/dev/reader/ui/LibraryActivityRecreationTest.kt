package dev.reader.ui

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import dev.reader.ReaderApplication
import dev.reader.data.BookEntity
import dev.reader.data.IndexResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric coverage for the P2 Task 5 review's fix-wave-2 blocker: the guard that used to be a
 * single `librarySynced` flag set *before* launching the sync had two holes once the flag moved
 * to process scope while the work it guards (`LibraryIndexer.sync()` on `lifecycleScope`) stayed
 * Activity-scoped — see [LibraryActivity.runLibrary]'s KDoc for the full narrative. Nothing before
 * this file exercised the Activity lifecycle at all: [LibraryActivityTest] only covers this
 * class's pure top-level functions.
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
        // same JVM fork; resetting the guards defensively costs nothing and avoids one test's
        // guard state leaking into another's if that ever changes.
        app.librarySynced = false
        app.librarySyncJob = null
    }

    @Test
    fun `the skipped-sync branch still populates the grid`() {
        // Stand in for "a sync already completed earlier in this process" (the common rotation
        // case): a row is already in the DB and the guard is already set, before this Activity
        // instance (standing in for the recreated one) is even built.
        runBlocking { app.database.bookDao().upsertAll(listOf(testBook())) }
        app.librarySynced = true

        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.create().start().resume()

        idleUntil { controller.get().itemCount == 1 }

        assertThat(controller.get().itemCount).isEqualTo(1)
    }

    @Test
    fun `a sync cancelled by Activity destruction does not mark the process synced`() {
        app.librarySynced = false

        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        // Never returns on its own — only cancellation (Activity destruction, below) ends it.
        // This lets the test force the "destroyed mid-sync" window deterministically, instead of
        // racing a real, unpredictably-timed EPUB scan.
        controller.get().sync = { awaitCancellation() }

        controller.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(app.librarySyncJob?.isActive).isTrue()

        // Destroying the Activity cancels its lifecycleScope, which cancels the suspended sync —
        // the rotation/Back-mid-sync scenario the blocker describes.
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(app.librarySynced).isFalse()
        assertThat(app.librarySyncJob?.isActive).isFalse()
    }

    @Test
    fun `onResume racing onCreate does not start a second concurrent sync`() {
        app.librarySynced = false
        var syncCalls = 0

        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.get().sync = {
            syncCalls++
            IndexResult(added = 0, updated = 0, removed = 0, newlyUnreadable = 0)
        }

        // onCreate() and onResume() both call runLibrary() back-to-back in the standard Activity
        // lifecycle, before the first launch has any chance to reach its first suspension point.
        // Robolectric's controller.create().start().resume() chain reproduces that exact order.
        controller.create().start().resume()
        idleUntil { syncCalls > 0 }

        assertThat(syncCalls).isEqualTo(1)
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
