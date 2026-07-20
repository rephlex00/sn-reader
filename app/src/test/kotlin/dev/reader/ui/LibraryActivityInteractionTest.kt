package dev.reader.ui

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.appcompat.widget.SearchView
import com.google.common.truth.Truth.assertThat
import dev.reader.R
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
        // Pinned so the seeded "/Document/..." paths used throughout this file (and the new
        // search/filter tests below) fall under the root deterministically, regardless of the
        // device's real external-storage path.
        LibraryPrefs(app).rootPath = ROOT
    }

    @After
    fun tearDown() {
        app.librarySyncJob = null
        app.getSharedPreferences("library_prefs", Context.MODE_PRIVATE).edit().clear().commit()
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

    // -- LF Task 2: search + status filter wiring ---------------------------------------------
    // Exercises the real toolbar menu/SearchView wiring end to end (not an internal seam):
    // `setQuery` on the actual SearchView action view drives onQueryTextChange exactly as the
    // framework does for a real keystroke, and filter taps go through the same
    // toolbar.menu.performIdentifierAction path LibraryActivityNavigationTest already uses for
    // sort/view/flatten.

    @Test
    fun `a search query renders only matching book rows and no folder rows`() {
        seed(
            book("$ROOT/Fiction/martian.epub", title = "The Martian", author = "Andy Weir"),
            book("$ROOT/Fiction/hail-mary.epub", title = "Project Hail Mary", author = "Andy Weir"),
            book("$ROOT/Science/cosmos.epub", title = "Cosmos", author = "Carl Sagan"),
        )
        val activity = launch()
        idleUntil { activity.rows.isNotEmpty() }

        activity.setSearchQuery("martian")
        idleUntil { bookTitles(activity.rows) == listOf("The Martian") }

        assertThat(bookTitles(activity.rows)).containsExactly("The Martian")
        assertThat(folderNames(activity.rows)).isEmpty()
    }

    @Test
    fun `a non-ALL status filter narrows the flat results`() {
        seed(
            book("$ROOT/Fiction/a.epub", title = "A", opened = null),
            book("$ROOT/Fiction/b.epub", title = "B", opened = 5L),
            book("$ROOT/Science/c.epub", title = "C", opened = null),
        )
        val activity = launch()
        idleUntil { activity.rows.isNotEmpty() }

        activity.clickMenu(R.id.filter_in_progress)
        idleUntil { bookTitles(activity.rows) == listOf("B") }

        assertThat(bookTitles(activity.rows)).containsExactly("B")
        assertThat(folderNames(activity.rows)).isEmpty()
    }

    @Test
    fun `clearing the query and status restores the folder listing at currentFolder`() {
        seed(
            book("$ROOT/Fiction/a.epub", title = "A"),
            book("$ROOT/Science/c.epub", title = "C"),
        )
        val activity = launch()
        idleUntil { activity.rows.isNotEmpty() }
        activity.tapFolder("$ROOT/Fiction")
        idleUntil { bookPaths(activity.rows) == listOf("$ROOT/Fiction/a.epub") }

        activity.setSearchQuery("cosmos-does-not-exist")
        idleUntil { activity.rows.isEmpty() }
        activity.setSearchQuery("")
        activity.clickMenu(R.id.filter_all)

        // Back to the folder listing at the same currentFolder ("Fiction"), not the root and not
        // the flat filtered view.
        idleUntil { bookPaths(activity.rows) == listOf("$ROOT/Fiction/a.epub") }
        assertThat(activity.currentFolderPath).isEqualTo("$ROOT/Fiction")
        assertThat(activity.emptyStateVisibility).isEqualTo(View.GONE)
    }

    @Test
    fun `Back with an active filter clears it instead of ascending or finishing`() {
        seed(
            book("$ROOT/Fiction/a.epub", title = "A"),
            book("$ROOT/Science/c.epub", title = "C"),
        )
        val activity = launch()
        idleUntil { activity.rows.isNotEmpty() }
        activity.tapFolder("$ROOT/Fiction")
        idleUntil { bookPaths(activity.rows) == listOf("$ROOT/Fiction/a.epub") }

        activity.setSearchQuery("cosmos")
        idleUntil { activity.rows.isEmpty() }

        activity.back()

        // Back cleared the filter and re-rendered the folder listing right where the user left
        // it, rather than ascending a folder level or finishing the Activity.
        assertThat(activity.isFinishing).isFalse()
        idleUntil { bookPaths(activity.rows) == listOf("$ROOT/Fiction/a.epub") }
        assertThat(activity.currentFolderPath).isEqualTo("$ROOT/Fiction")
    }

    @Test
    fun `a query with no matches shows the No books match empty state`() {
        seed(book("$ROOT/Fiction/a.epub", title = "A"))
        val activity = launch()
        idleUntil { activity.rows.isNotEmpty() }

        activity.setSearchQuery("nothing-matches-this")
        idleUntil { activity.rows.isEmpty() }

        assertThat(activity.rows).isEmpty()
        assertThat(activity.emptyStateVisibility).isEqualTo(View.VISIBLE)
        assertThat(activity.emptyStateText).contains("No books match")
    }

    @Test
    fun `backgrounding and reopening with a zero-match filter keeps the empty-state message`() {
        // Regression for onStart unconditionally forcing emptyStateView GONE: render() is now the
        // second writer to that view (a zero-match filter shows "No books match."), and an
        // unchanged library's sync() does no DB writes, so observeAllSorted never re-emits and
        // render() never reruns on its own. Before the fix, stop/start here wiped the message to
        // GONE and left a blank grid until the next unrelated interaction.
        seed(book("$ROOT/Fiction/a.epub", title = "A"))
        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.create().start().resume()
        val activity = controller.get()
        idleUntil { activity.rows.isNotEmpty() }

        activity.setSearchQuery("nothing-matches-this")
        idleUntil { activity.rows.isEmpty() }
        assertThat(activity.emptyStateVisibility).isEqualTo(View.VISIBLE)

        // Background the app and reopen it — same Activity instance, stop/start pair — with the
        // zero-match filter still active.
        controller.pause().stop().start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(activity.emptyStateVisibility).isEqualTo(View.VISIBLE)
        assertThat(activity.emptyStateText).contains("No books match")
    }

    // -- toolbar title reflects the filter, not the folder, while one is active ---------------

    @Test
    fun `toolbar title falls back to Library while a filter is active and restores the folder name when cleared`() {
        seed(
            book("$ROOT/Fiction/a.epub", title = "A"),
            book("$ROOT/Science/c.epub", title = "C"),
        )
        val activity = launch()
        idleUntil { activity.rows.isNotEmpty() }
        activity.tapFolder("$ROOT/Fiction")
        idleUntil { bookPaths(activity.rows) == listOf("$ROOT/Fiction/a.epub") }
        assertThat(activity.toolbarTitle).isEqualTo("Fiction")

        activity.setSearchQuery("cosmos-does-not-exist")
        idleUntil { activity.rows.isEmpty() }
        assertThat(activity.toolbarTitle).isEqualTo("Library")

        activity.setSearchQuery("")
        idleUntil { bookPaths(activity.rows) == listOf("$ROOT/Fiction/a.epub") }
        assertThat(activity.toolbarTitle).isEqualTo("Fiction")
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

        val rows: List<LibraryRow> get() = adapter.currentList
        val currentFolderPath: String get() = currentFolder
        val toolbarTitle: String get() = toolbar.title.toString()

        fun tapFolder(path: String) = openFolder(path)
        fun back() = onBackPressedDispatcher.onBackPressed()
        fun clickMenu(id: Int) = toolbar.menu.performIdentifierAction(id, 0)

        /**
         * Drives the query through the real SearchView action view rather than an internal seam:
         * `setQuery` sets the EditText's text, which fires the same `onQueryTextChange` the
         * framework calls for a real keystroke.
         */
        fun setSearchQuery(query: String) {
            val searchView = toolbar.menu.findItem(R.id.action_search).actionView as SearchView
            searchView.setQuery(query, false)
        }
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

    // -- shared with the LF Task 2 tests above -------------------------------------------------

    private fun launch(): TestableLibraryActivity {
        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.create().start().resume()
        return controller.get()
    }

    private fun seed(vararg books: BookEntity) {
        runBlocking { app.database.bookDao().upsertAll(books.toList()) }
    }

    private fun book(
        path: String,
        title: String = path.substringAfterLast('/'),
        author: String? = null,
        opened: Long? = null,
    ) = BookEntity(
        path = path,
        sizeBytes = 1_000L,
        modifiedAtMs = 1_700_000_000_000L,
        title = title,
        author = author,
        coverPath = null,
        spineIndex = 0,
        charOffset = 0,
        unreadable = false,
        unreadableReason = null,
        addedAtMs = 1_700_000_000_000L,
        lastOpenedAtMs = opened,
    )

    private fun folderNames(rows: List<LibraryRow>) =
        rows.filterIsInstance<LibraryRow.Folder>().map { it.name }

    private fun bookPaths(rows: List<LibraryRow>) =
        rows.filterIsInstance<LibraryRow.Book>().map { it.entity.path }

    private fun bookTitles(rows: List<LibraryRow>) =
        rows.filterIsInstance<LibraryRow.Book>().map { it.entity.title }

    private companion object {
        const val ROOT = "/Document"
    }
}
