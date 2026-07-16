package dev.reader.ui

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R
import dev.reader.ReaderApplication
import dev.reader.data.BookEntity
import dev.reader.data.LibraryDatabase
import dev.reader.data.LibraryIndexer
import dev.reader.data.SortOrder
import dev.reader.library.EpubMetadataExtractor
import kotlinx.coroutines.Job
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

/**
 * A grid cell's target width in raw pixels, used only by [spanCountFor] to compute the span count
 * from the recycler's measured width (also in raw pixels). This is **not** shared with
 * `item_book.xml`'s layout, which sizes its cover/text views with `match_parent`/dp and never
 * references this constant — an earlier version of this comment claimed otherwise. Because it's
 * px rather than dp, the column count this yields will vary with screen density in a way dp-based
 * layout wouldn't; left as-is since the value itself is a call for whoever owns the visual
 * density, not a bug this pass is fixing.
 */
private const val COLUMN_WIDTH_PX = 260

/** The [Bundle] key [LibraryActivity] saves [LibraryActivity.currentSort] under. */
private const val KEY_SORT_ORDER = "dev.reader.ui.SORT_ORDER"

/**
 * How [LibraryActivity] serializes the current [SortOrder] into `onSaveInstanceState`'s Bundle so
 * it survives Activity recreation (e.g. a device rotation) instead of resetting to [SortOrder.TITLE].
 */
fun sortOrderToSavedValue(order: SortOrder): String = order.name

/**
 * The inverse of [sortOrderToSavedValue]. Falls back to [SortOrder.TITLE] for `null` (nothing
 * saved yet — first launch) or an unrecognized name, rather than throwing.
 */
fun sortOrderFromSavedValue(value: String?): SortOrder =
    SortOrder.entries.firstOrNull { it.name == value } ?: SortOrder.TITLE

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

    /**
     * [LibraryDatabase] and the once-per-process sync guard both live on [ReaderApplication] now,
     * not here — see its KDoc. This Activity is recreated on every rotation; the Application is
     * not, so both had to move for "once per process" to actually mean once per process rather
     * than once per Activity instance.
     */
    private val db: LibraryDatabase
        get() = (application as ReaderApplication).database

    private lateinit var adapter: BookGridAdapter

    private var currentSort = SortOrder.TITLE
    private var observeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentSort = sortOrderFromSavedValue(savedInstanceState?.getString(KEY_SORT_ORDER))

        // Verification instrumentation for the rotation fix (Plan 2 Task 5 review, fix wave 1):
        // db's identityHashCode staying constant across onCreate calls proves it's the same
        // ReaderApplication-scoped Room instance, not a fresh one per Activity/rotation.
        Log.i(TAG, "onCreate db=${System.identityHashCode(db)} restoredSort=$currentSort")

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
        if (!(application as ReaderApplication).librarySynced && hasAllFilesAccess()) runLibrary()
    }

    /**
     * Persists [currentSort] across Activity recreation (e.g. a device rotation), which otherwise
     * snaps the user's chosen sort order back to [SortOrder.TITLE] — user-visible on this device.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SORT_ORDER, sortOrderToSavedValue(currentSort))
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
     * Runs the incremental sync exactly once per **process**, then starts observing the (now up
     * to date) index. [LibraryIndexer.sync] already confines all of its own work to
     * [kotlinx.coroutines.Dispatchers.IO] and returns — it is not scheduled, retried, or repeated
     * from here.
     *
     * The guard is [ReaderApplication.librarySynced], not an Activity field: a rotation destroys
     * and recreates this Activity (resetting any field it owned) but not the Application, and a
     * rotation is not a fresh entry to the library screen per the plan's "indexing runs once on
     * library entry" constraint. [onResume] still re-arms this call after a permission grant (the
     * guard not being set yet is what lets that first real entry through); a later *process*
     * launch (a fresh process, not merely backgrounding and returning) runs it again exactly once,
     * which is what "incremental" means: a cheap stat diff, not a full re-crack.
     *
     * When the sync already ran earlier in this process (the guard is already set — the common
     * case on a rotation), this still calls [observeSorted]: the guard exists to skip a redundant
     * *sync*, not to skip populating the brand-new [BookGridAdapter] this recreated Activity just
     * built. Skipping it here would leave a freshly rotated grid permanently empty.
     */
    private fun runLibrary() {
        val app = application as ReaderApplication
        if (app.librarySynced) {
            Log.i(TAG, "runLibrary: sync SKIPPED (already synced this process)")
            observeSorted(currentSort)
            return
        }
        app.librarySynced = true
        Log.i(TAG, "runLibrary: sync STARTED")

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
            // `collect`, not `collectLatest`: ListAdapter.submitList doesn't suspend, so
            // collectLatest's cancel-the-previous-collector-body semantics never actually engage
            // here — there is nothing in flight for a new emission to cancel. Plain `collect` says
            // what's really happening: every emission is submitted in order.
            db.bookDao().observeAllSorted(order).collect { books -> adapter.submitList(books) }
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

    private companion object {
        const val TAG = "LibraryActivity"
    }
}
