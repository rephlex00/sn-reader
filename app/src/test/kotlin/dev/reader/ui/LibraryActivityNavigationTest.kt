package dev.reader.ui

import android.content.Context
import android.os.Looper
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
 * Robolectric coverage for Task 8c's folder navigation and view/flatten toggles on
 * [LibraryActivity], via the same protected-open + test-subclass seams as
 * [LibraryActivityRecreationTest] (see that file's KDoc for why the seams exist at all). The pure
 * projection itself ([folderListing], [clampToRoot], [statusText], [humanReadableSize]) is tested
 * without a device in [FolderListingTest] and [BookGridAdapterTest]; this file pins only the
 * Activity wiring around it — where a tap lands, what Back does, what persists.
 *
 * Every test pins the library root to `/Document` so the seeded book paths are deterministic
 * regardless of the device's external-storage path.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryActivityNavigationTest {

    private lateinit var app: ReaderApplication

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as ReaderApplication
        LibraryPrefs(app).rootPath = ROOT
    }

    @After
    fun tearDown() {
        app.librarySyncJob = null
        app.getSharedPreferences("library_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // -- folder descent ------------------------------------------------------------------------

    @Test
    fun `tapping a folder descends into it and persists lastFolderPath`() {
        seed(
            book("$ROOT/Fiction/a.epub"),
            book("$ROOT/Fiction/b.epub"),
            book("$ROOT/Science/c.epub"),
            book("$ROOT/top.epub"),
        )
        val activity = launch()
        idleUntil { activity.rows.isNotEmpty() }
        // At the root: two folders (naturally sorted) then the one top-level book.
        assertThat(folderNames(activity.rows)).containsExactly("Fiction", "Science").inOrder()

        activity.tapFolder("$ROOT/Fiction")
        // The row list commits through an AsyncListDiffer (off the shadow scheduler), so wait for
        // it to reflect the descent before asserting on it.
        idleUntil { bookPaths(activity.rows) == listOf("$ROOT/Fiction/a.epub", "$ROOT/Fiction/b.epub") }

        assertThat(activity.currentFolderPath).isEqualTo("$ROOT/Fiction")
        assertThat(bookPaths(activity.rows)).containsExactly("$ROOT/Fiction/a.epub", "$ROOT/Fiction/b.epub")
        assertThat(folderNames(activity.rows)).isEmpty()
        assertThat(LibraryPrefs(app).lastFolderPath).isEqualTo("$ROOT/Fiction")
        assertThat(activity.toolbarTitle).isEqualTo("Fiction")
    }

    // -- Back navigation -----------------------------------------------------------------------

    @Test
    fun `Back inside a folder ascends one level and does not finish`() {
        seed(book("$ROOT/Fiction/a.epub"))
        val activity = launch()
        idleUntil { activity.rows.isNotEmpty() }
        activity.tapFolder("$ROOT/Fiction")
        assertThat(activity.currentFolderPath).isEqualTo("$ROOT/Fiction")

        activity.back()

        assertThat(activity.isFinishing).isFalse()
        assertThat(activity.currentFolderPath).isEqualTo(ROOT)
        assertThat(activity.toolbarTitle).isEqualTo("Library")
        // Ascending back to the root clears the remembered folder (its "the root" sentinel).
        assertThat(LibraryPrefs(app).lastFolderPath).isNull()
    }

    @Test
    fun `Back at the root finishes the Activity`() {
        seed(book("$ROOT/Fiction/a.epub"))
        val activity = launch()
        idleUntil { activity.rows.isNotEmpty() }
        assertThat(activity.currentFolderPath).isEqualTo(ROOT)

        activity.back()

        assertThat(activity.isFinishing).isTrue()
    }

    // -- flatten toggle ------------------------------------------------------------------------

    @Test
    fun `flatten hides folder rows and shows every book, and persists`() {
        seed(
            book("$ROOT/Fiction/a.epub"),
            book("$ROOT/Fiction/b.epub"),
            book("$ROOT/Science/c.epub"),
            book("$ROOT/top.epub"),
        )
        val activity = launch()
        idleUntil { activity.rows.isNotEmpty() }
        assertThat(folderNames(activity.rows)).isNotEmpty()

        activity.clickMenu(dev.reader.R.id.action_flatten)
        idleUntil { folderNames(activity.rows).isEmpty() && bookPaths(activity.rows).size == 4 }

        assertThat(folderNames(activity.rows)).isEmpty()
        assertThat(bookPaths(activity.rows)).containsExactly(
            "$ROOT/Fiction/a.epub", "$ROOT/Fiction/b.epub", "$ROOT/Science/c.epub", "$ROOT/top.epub",
        )
        assertThat(LibraryPrefs(app).flatten).isTrue()
    }

    // -- view toggle ---------------------------------------------------------------------------

    @Test
    fun `the list toggle persists and a fresh Activity renders books as list rows`() {
        seed(book("$ROOT/top.epub"))
        val first = launch()
        idleUntil { first.rows.isNotEmpty() }

        first.clickMenu(dev.reader.R.id.view_list)
        assertThat(LibraryPrefs(app).viewMode).isEqualTo(ViewMode.LIST)

        // A fresh Activity (process recreation, config change) inherits the persisted mode and
        // renders the same book as a list row, not a cover tile.
        val second = launch()
        idleUntil { second.rows.isNotEmpty() }
        assertThat(second.bookViewTypeAt(0)).isEqualTo(BookGridAdapter.VIEW_TYPE_BOOK_ROW)
    }

    // -- remember-last-folder across recreation ------------------------------------------------

    @Test
    fun `a fresh Activity restores the folder the previous one was showing`() {
        seed(book("$ROOT/Fiction/a.epub"))
        val first = launch()
        idleUntil { first.rows.isNotEmpty() }
        first.tapFolder("$ROOT/Fiction")

        val second = launch()
        idleUntil { second.rows.isNotEmpty() }

        assertThat(second.currentFolderPath).isEqualTo("$ROOT/Fiction")
        assertThat(second.toolbarTitle).isEqualTo("Fiction")
        assertThat(bookPaths(second.rows)).containsExactly("$ROOT/Fiction/a.epub")
    }

    // -- sort within a folder ------------------------------------------------------------------

    @Test
    fun `changing sort inside a folder re-orders only that folder's books`() {
        // Fiction holds two books whose TITLE and AUTHOR orders diverge; Science holds one, which
        // must never appear while we are scoped to Fiction.
        seed(
            book("$ROOT/Fiction/z.epub", title = "Zebra", author = "Adams"),
            book("$ROOT/Fiction/a.epub", title = "Apple", author = "Zephyr"),
            book("$ROOT/Science/c.epub", title = "Cosmos", author = "Bragg"),
        )
        val activity = launch()
        idleUntil { activity.rows.isNotEmpty() }
        activity.tapFolder("$ROOT/Fiction")
        // Default TITLE sort: Apple before Zebra, and only Fiction's books.
        idleUntil { bookTitles(activity.rows) == listOf("Apple", "Zebra") }
        assertThat(bookTitles(activity.rows)).containsExactly("Apple", "Zebra").inOrder()

        activity.clickMenu(dev.reader.R.id.sort_author)

        // AUTHOR sort flips them (Adams before Zephyr) — still only Fiction's two books, no Science.
        idleUntil { bookTitles(activity.rows) == listOf("Zebra", "Apple") }
        assertThat(bookTitles(activity.rows)).containsExactly("Zebra", "Apple").inOrder()
        assertThat(activity.currentFolderPath).isEqualTo("$ROOT/Fiction")
    }

    // -- helpers -------------------------------------------------------------------------------

    private fun launch(): TestableLibraryActivity {
        val controller = Robolectric.buildActivity(TestableLibraryActivity::class.java)
        controller.create().start().resume()
        return controller.get()
    }

    private fun seed(vararg books: BookEntity) {
        runBlocking { app.database.bookDao().upsertAll(books.toList()) }
    }

    private fun folderNames(rows: List<LibraryRow>) =
        rows.filterIsInstance<LibraryRow.Folder>().map { it.name }

    private fun bookPaths(rows: List<LibraryRow>) =
        rows.filterIsInstance<LibraryRow.Book>().map { it.entity.path }

    private fun bookTitles(rows: List<LibraryRow>) =
        rows.filterIsInstance<LibraryRow.Book>().map { it.entity.title }

    /** [LibraryActivity] subclass exposing the navigation seams and read-outs the tests drive. */
    private class TestableLibraryActivity : LibraryActivity() {
        override fun isAllFilesAccessGranted(): Boolean = true
        override fun createSync(): suspend () -> IndexResult =
            { IndexResult(added = 0, updated = 0, removed = 0, newlyUnreadable = 0) }

        val rows: List<LibraryRow> get() = adapter.currentList
        val currentFolderPath: String get() = currentFolder
        val toolbarTitle: String get() = toolbar.title.toString()

        fun tapFolder(path: String) = openFolder(path)
        fun back() = onBackPressedDispatcher.onBackPressed()
        fun clickMenu(id: Int) = toolbar.menu.performIdentifierAction(id, 0)
        fun bookViewTypeAt(position: Int): Int = adapter.getItemViewType(position)
    }

    private fun idleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun book(
        path: String,
        title: String = path.substringAfterLast('/'),
        author: String? = null,
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
        lastOpenedAtMs = null,
    )

    private companion object {
        const val ROOT = "/Document"
    }
}
