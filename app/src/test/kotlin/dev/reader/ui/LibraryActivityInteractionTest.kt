package dev.reader.ui

import android.os.Looper
import android.view.View
import com.google.common.truth.Truth.assertThat
import dev.reader.ReaderApplication
import dev.reader.data.BookEntity
import dev.reader.data.IndexResult
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
 * Robolectric coverage for [LibraryActivity]'s tap handling and permission-denied UI, via the
 * same protected-open + test-subclass seams as [LibraryActivityRecreationTest] (see that file's
 * KDoc for why the seams exist at all).
 */
@RunWith(RobolectricTestRunner::class)
class LibraryActivityInteractionTest {

    private lateinit var app: ReaderApplication

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as ReaderApplication
    }

    @After
    fun tearDown() {
        app.librarySyncJob = null
    }

    // -- I3: double-tap debounce ------------------------------------------------------------

    @Test
    fun `a double-tap opens exactly one ReaderActivity`() {
        // E-ink's delayed visual feedback makes double-taps routine: nothing on screen changes
        // for hundreds of milliseconds after the first tap, so the user taps again. Without the
        // guard that stacked two ReaderActivities: two open ZipFiles and, once Task 6 lands,
        // dueling position writers over the same row.
        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.create().start().resume()
        val activity = controller.get()
        val book = testBook()

        activity.tap(book)
        activity.tap(book)

        val shadow = shadowOf(activity)
        val first = shadow.nextStartedActivity
        assertThat(first).isNotNull()
        assertThat(first!!.getStringExtra(ReaderActivity.EXTRA_BOOK_PATH)).isEqualTo(book.path)
        assertThat(shadow.nextStartedActivity).isNull()
    }

    @Test
    fun `returning to the library re-arms opening a book`() {
        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.create().start().resume()
        val activity = controller.get()
        val book = testBook()

        activity.tap(book)
        assertThat(shadowOf(activity).nextStartedActivity).isNotNull()

        // Coming back from the reader: pause/resume is the tail of that round trip, and
        // onResume is what re-arms the guard.
        controller.pause().resume()
        activity.tap(book)

        assertThat(shadowOf(activity).nextStartedActivity).isNotNull()
    }

    // -- I5: tapping an unreadable book retries it once ---------------------------------------

    @Test
    fun `tapping an unreadable book clears its stat and triggers a re-sync`() {
        val broken = testBook(path = "/Document/Books/broken.epub").copy(
            unreadable = true,
            unreadableReason = "half-synced",
        )
        runBlocking { app.database.bookDao().upsertAll(listOf(broken)) }

        var syncCalls = 0
        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.get().sync = {
            syncCalls++
            IndexResult(added = 0, updated = 0, removed = 0, newlyUnreadable = 0)
        }
        controller.create().start().resume()
        idleUntil { syncCalls == 1 }

        controller.get().tap(broken)
        idleUntil { syncCalls == 2 }

        // The tap bought exactly one retry: the stored stat is invalidated (so the sync it
        // triggered re-cracks this one file) and no ReaderActivity was started for it.
        assertThat(syncCalls).isEqualTo(2)
        val row = runBlocking { app.database.bookDao().getByPath(broken.path)!! }
        assertThat(row.modifiedAtMs).isEqualTo(-1L)
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
    }

    // -- M3: denied permission is a message, not a silent blank grid --------------------------

    @Test
    fun `a denied permission shows a persistent empty-state message`() {
        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.get().accessGranted = false

        controller.create().start().resume()

        val activity = controller.get()
        assertThat(activity.emptyStateVisibility).isEqualTo(View.VISIBLE)
        assertThat(activity.emptyStateText).contains("all-files access")
    }

    @Test
    fun `granting permission on return from Settings hides the message and syncs`() {
        var syncCalls = 0
        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        val activity = controller.get()
        activity.accessGranted = false
        activity.sync = {
            syncCalls++
            IndexResult(added = 0, updated = 0, removed = 0, newlyUnreadable = 0)
        }
        controller.create().start().resume()
        assertThat(activity.emptyStateVisibility).isEqualTo(View.VISIBLE)
        assertThat(syncCalls).isEqualTo(0)

        // The user grants access in Settings and comes back: same instance, stop/start pair.
        activity.accessGranted = true
        controller.pause().stop()
        controller.start().resume()
        idleUntil { syncCalls == 1 }

        assertThat(activity.emptyStateVisibility).isEqualTo(View.GONE)
        assertThat(syncCalls).isEqualTo(1)
    }

    /** [LibraryActivity] subclass whose test seams are set per-test via mutable fields. */
    private class TestableLibraryActivity : LibraryActivity() {
        var accessGranted = true
        var sync: suspend () -> IndexResult =
            { IndexResult(added = 0, updated = 0, removed = 0, newlyUnreadable = 0) }

        override fun isAllFilesAccessGranted(): Boolean = accessGranted
        override fun createSync(): suspend () -> IndexResult = sync

        /** Drives the protected [openBook] — the adapter's tap callback — directly. */
        fun tap(book: BookEntity) = openBook(book)

        val emptyStateVisibility: Int get() = emptyStateView.visibility
        val emptyStateText: String get() = emptyStateView.text.toString()
    }

    /** Same looper/background-thread bridge as [LibraryActivityRecreationTest]'s idleUntil. */
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
