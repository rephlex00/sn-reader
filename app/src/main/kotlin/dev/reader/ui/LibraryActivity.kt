package dev.reader.ui

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import dev.reader.R
import dev.reader.data.BookEntity
import dev.reader.data.LibraryDatabase
import dev.reader.data.LibraryIndexer
import dev.reader.data.SortOrder
import dev.reader.library.EpubMetadataExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/** [MenuItem.getItemId] -> the [SortOrder] it selects, or null for an item this menu doesn't own. */
fun sortOrderForMenuItemId(itemId: Int): SortOrder? = when (itemId) {
    R.id.sort_title -> SortOrder.TITLE
    R.id.sort_author -> SortOrder.AUTHOR
    R.id.sort_recently_added -> SortOrder.RECENTLY_ADDED
    R.id.sort_recently_opened -> SortOrder.RECENTLY_OPENED
    else -> null
}

/**
 * How many grid columns fit [widthPx] at roughly [columnWidthPx] each, never fewer than 2 (a
 * single column would barely read as a "grid" on a 7.8" panel, even in an unexpectedly narrow
 * layout pass).
 */
fun spanCountFor(widthPx: Int, columnWidthPx: Int): Int = (widthPx / columnWidthPx).coerceAtLeast(2)

/** A grid cell's target width; kept in one place since [spanCountFor] and layout both use it. */
private const val COLUMN_WIDTH_PX = 260

/**
 * The library grid — the app's entry point (see the manifest: this is now the LAUNCHER activity,
 * [ReaderActivity] is not). `RecyclerView` + `GridLayoutManager`, Views only.
 *
 * On entry (once, not on a schedule): runs [LibraryIndexer.sync] on the library roots, then
 * observes [dev.reader.data.BookDao.observeAllSorted] for the chosen [SortOrder] and hands each
 * emission straight to [BookGridAdapter] — sorting is entirely SQL's job (see [BookDao]'s KDoc),
 * this Activity never re-sorts a list. Tapping a cell starts [ReaderActivity] with that book's
 * path.
 *
 * **No animations, no background service.** [RecyclerView.setItemAnimator] is set to null:
 * `DefaultItemAnimator` cross-fades on every change, which is a smear on e-ink and animation is
 * banned outright regardless of panel. Indexing runs once, right here, in a coroutine that
 * completes and stops — there is no `Service`/`WorkManager`/`FileObserver`/timer anywhere in this
 * class, so the idle promise (0% CPU once the grid has settled) is unaffected by any of this.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var db: LibraryDatabase
    private lateinit var adapter: BookGridAdapter

    private var currentSort = SortOrder.TITLE
    private var observeJob: Job? = null

    /**
     * Guards [runLibrary] to once per process, the same shape as [ReaderActivity]'s `opening`
     * flag and for the same reason: [onResume] re-arms after a permission grant, and without this
     * a resume that races the still-suspended first sync would start a second one.
     */
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = Room.databaseBuilder(applicationContext, LibraryDatabase::class.java, "library.db")
            .build()

        val recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@LibraryActivity, spanCountFor(resources.displayMetrics.widthPixels, COLUMN_WIDTH_PX))
            // The single most important line in this Activity: DefaultItemAnimator (the
            // RecyclerView default) cross-fades items in/out/through on every list change, which
            // on e-ink is a visible smear and is banned outright as animation, full stop.
            itemAnimator = null
        }
        adapter = BookGridAdapter(lifecycleScope, ::openBook)
        recyclerView.adapter = adapter

        val toolbar = Toolbar(this).apply {
            title = "Library"
            inflateMenu(R.menu.menu_library)
            setOnMenuItemClickListener(::onSortMenuItemClicked)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(recyclerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        if (!hasAllFilesAccess()) {
            requestAllFilesAccess()
            return
        }
        runLibrary()
    }

    override fun onResume() {
        super.onResume()
        if (!started && hasAllFilesAccess()) runLibrary()
    }

    private fun onSortMenuItemClicked(item: MenuItem): Boolean {
        val order = sortOrderForMenuItemId(item.itemId) ?: return false
        setSortOrder(order)
        return true
    }

    private fun setSortOrder(order: SortOrder) {
        if (order == currentSort) return
        currentSort = order
        observeSorted(order)
    }

    /**
     * Runs the incremental sync exactly once per entry to this screen, then starts observing the
     * (now up to date) index. [LibraryIndexer.sync] already confines all of its own work to
     * [kotlinx.coroutines.Dispatchers.IO] and returns — it is not scheduled, retried, or repeated
     * from here; a later visit to this Activity (a fresh process, or backgrounding and returning)
     * re-arms via [onResume] and runs it again exactly once, which is what "incremental" means: a
     * cheap stat diff, not a full re-crack.
     */
    private fun runLibrary() {
        if (started) return
        started = true

        val roots = listOf(
            File(Environment.getExternalStorageDirectory(), "Document"),
            File(Environment.getExternalStorageDirectory(), "EXPORT"),
        )
        val indexer = LibraryIndexer(db.bookDao(), roots, EpubMetadataExtractor(applicationContext))

        lifecycleScope.launch {
            try {
                indexer.sync()
            } catch (e: Exception) {
                // A failed sync (e.g. both roots denied) must not leave the grid stuck empty
                // with no explanation, but it also must not crash the launcher activity.
                Toast.makeText(this@LibraryActivity, "Couldn't scan your library: ${e.message}", Toast.LENGTH_LONG).show()
            }
            observeSorted(currentSort)
        }
    }

    private fun observeSorted(order: SortOrder) {
        observeJob?.cancel()
        observeJob = lifecycleScope.launch {
            db.bookDao().observeAllSorted(order).collectLatest { books -> adapter.submitList(books) }
        }
    }

    private fun openBook(book: BookEntity) {
        if (book.unreadable) {
            Toast.makeText(this, "Unreadable: ${book.unreadableReason ?: "unknown reason"}", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_PATH, book.path)
        )
    }
}
