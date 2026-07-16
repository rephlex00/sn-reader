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
import dev.reader.data.IndexResult
import dev.reader.data.LibraryDatabase
import dev.reader.data.LibraryIndexer
import dev.reader.data.SortOrder
import dev.reader.library.EpubMetadataExtractor
import kotlinx.coroutines.CancellationException
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
 * The inverse of [sortOrderForMenuItemId]: which menu item's `checkable` state should reflect
 * [order] being the active sort. Used to restore the check-mark after a rotation rebuilds the
 * toolbar's menu from scratch (a fresh [MenuItem] is unchecked by default regardless of
 * [LibraryActivity.currentSort]).
 */
fun menuItemIdForSortOrder(order: SortOrder): Int = when (order) {
    SortOrder.TITLE -> R.id.sort_title
    SortOrder.AUTHOR -> R.id.sort_author
    SortOrder.RECENTLY_ADDED -> R.id.sort_recently_added
    SortOrder.RECENTLY_OPENED -> R.id.sort_recently_opened
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
 *
 * `open`, not `final`: [LibraryActivityRecreationTest]'s Robolectric coverage recreates this Activity via a
 * test subclass that overrides [isAllFilesAccessGranted] and [createSync] — the two points where
 * this class would otherwise reach out to real device permissions and a real EPUB-backed indexer,
 * neither of which a JVM test can exercise meaningfully. No other member is `open`.
 */
open class LibraryActivity : AppCompatActivity() {

    /**
     * [LibraryDatabase] and the sync guards both live on [ReaderApplication] now, not here — see
     * its KDoc. This Activity is recreated on every rotation; the Application is not, so both had
     * to move for "once per process" to actually mean once per process rather than once per
     * Activity instance.
     */
    private val db: LibraryDatabase
        get() = (application as ReaderApplication).database

    /** `protected`, not `private`: [LibraryActivityRecreationTest] reads `itemCount` after recreation. */
    protected lateinit var adapter: BookGridAdapter

    private var currentSort = SortOrder.TITLE
    private var observeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentSort = sortOrderFromSavedValue(savedInstanceState?.getString(KEY_SORT_ORDER))

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
            // menu_library.xml's group is checkableBehavior="single", so tapping an item checks
            // it and unchecks its siblings automatically — but a freshly inflated menu (every
            // rotation gets one) starts with nothing checked regardless of currentSort. Restore
            // the mark here so the active order is still visible after restore, not just correct.
            menu.findItem(menuItemIdForSortOrder(currentSort))?.isChecked = true
            setOnMenuItemClickListener(::onSortMenuItemClicked)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(recyclerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        if (!isAllFilesAccessGranted()) {
            requestAllFilesAccess()
            return
        }
        // Verification instrumentation for the rotation fix (Plan 2 Task 5 review, fix waves 1 &
        // 2): db's identityHashCode staying constant across onCreate calls proves it's the same
        // ReaderApplication-scoped Room instance, not a fresh one per Activity/rotation. Placed
        // after the permission gate (not before, as fix wave 1 had it) so a permission-denied
        // launch never forces the lazy Room build just to log a line nothing needs yet.
        Log.i(TAG, "onCreate db=${System.identityHashCode(db)} restoredSort=$currentSort")
        runLibrary()
    }

    override fun onResume() {
        super.onResume()
        val app = application as ReaderApplication
        if (!app.librarySynced && app.librarySyncJob?.isActive != true && isAllFilesAccessGranted()) {
            runLibrary()
        }
    }

    /**
     * Whether all-files access is currently granted. A thin `protected open` wrapper around the
     * real [hasAllFilesAccess] extension so [LibraryActivityRecreationTest]'s Robolectric coverage — which
     * cannot grant a real Android 11 all-files-access permission — can stub this one point via a
     * test subclass instead of faking the underlying `Environment.isExternalStorageManager()`.
     */
    protected open fun isAllFilesAccessGranted(): Boolean = hasAllFilesAccess()

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
     * Starts observing the index immediately, then — unless a sync already succeeded or is
     * already running in this process — runs the incremental sync once. [LibraryIndexer.sync]
     * already confines all of its own work to [kotlinx.coroutines.Dispatchers.IO] and returns; it
     * is not scheduled, retried on a timer, or repeated from here.
     *
     * **Why [observeSorted] runs unconditionally, before the guard check.** Rows already in the
     * DB — from a prior sync this process already completed, or from a previous process entirely
     * — should paint immediately instead of waiting for this (possibly first-run, possibly
     * multi-second) sync to finish; a blank grid for the whole first-run scan fails the plan's own
     * "does not visibly stall the grid" checklist item. Room's Flow re-emits on its own as
     * [LibraryIndexer.sync] writes rows, so nothing needs to re-subscribe once the sync completes.
     * This also fixes the fix-wave-1 regression this same method used to special-case: a rotation
     * whose sync was already done previously must still populate the brand-new [BookGridAdapter]
     * the recreated Activity just built, not just skip straight past it.
     *
     * **Why [ReaderApplication.librarySynced] is set only on success, never before launching.**
     * The guard lives on [ReaderApplication], not this Activity, because a rotation destroys and
     * recreates the Activity (resetting any field it owned) but not the Application. But the
     * *work* the guard guards — [createSync]'s coroutine — runs on `lifecycleScope`, which is
     * Activity-scoped: a rotation or a Back press cancels it mid-diff. Setting the flag *before*
     * launching (as an earlier version of this method did) would make the recreated Activity see
     * "already synced" and never retry — every book the aborted sync never reached would be
     * permanently missing from the grid until the process dies. Setting it only when `sync()`
     * actually *returns* means a cancelled or thrown run is retried on the next entry instead,
     * which is cheap: the diff is incremental, so unchanged files open nothing. The same applies
     * to the `catch (e: Exception)` branch below — a failed sync (e.g. both roots denied) leaves
     * the flag false too, so it is retried rather than given up on forever.
     *
     * **Why [CancellationException] is caught and rethrown, not left to the broad `catch`
     * below it.** [CancellationException] extends [Exception] — this project has already been
     * bitten once by a catch-all silently swallowing it (see [LibraryIndexer.sync]'s own KDoc).
     * Swallowing it here would both mark a merely-cancelled sync as "handled" (masking the
     * intentional early-return this whole method depends on) and pop a spurious error Toast for a
     * sync the user simply rotated or backed away from, not one that failed.
     *
     * **Why [ReaderApplication.librarySyncJob] exists at all, on top of the flag.** `onResume`
     * re-checks this guard immediately after `onCreate` returns — the two fire back-to-back in
     * the normal Activity lifecycle, before the sync just launched here has had any chance to
     * even reach its first `Dispatchers.IO` suspension point. Without tracking the in-flight
     * [Job] itself, that second call would see the flag still `false` and launch a *second*,
     * concurrent [LibraryIndexer.sync] racing the first one against the same DB.
     */
    private fun runLibrary() {
        val app = application as ReaderApplication
        observeSorted(currentSort)

        if (app.librarySynced || app.librarySyncJob?.isActive == true) {
            Log.i(TAG, "runLibrary: sync SKIPPED (already synced or in progress this process)")
            return
        }
        Log.i(TAG, "runLibrary: sync STARTED")

        app.librarySyncJob = lifecycleScope.launch {
            try {
                createSync().invoke()
                app.librarySynced = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed sync (e.g. both roots denied) must not leave the grid stuck empty
                // with no explanation, but it also must not crash the launcher activity. The flag
                // stays false (see the KDoc above), so the next entry retries instead of giving up.
                Toast.makeText(this@LibraryActivity, "Couldn't scan your library: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Builds the suspend function [runLibrary] runs to perform one sync. `protected open`, not
     * inlined into [runLibrary], purely as a test seam: [LibraryActivityRecreationTest]'s Robolectric
     * coverage substitutes one that suspends until cancelled (via
     * [kotlinx.coroutines.awaitCancellation]) to deterministically exercise the
     * cancel-mid-sync path without needing a real multi-second EPUB scan to race against.
     * Production always returns the real EPUB-backed [LibraryIndexer] over the device's library
     * roots.
     */
    protected open fun createSync(): suspend () -> IndexResult = {
        val roots = listOf(
            File(Environment.getExternalStorageDirectory(), "Document"),
            File(Environment.getExternalStorageDirectory(), "EXPORT"),
        )
        LibraryIndexer(db.bookDao(), roots, EpubMetadataExtractor(applicationContext)).sync()
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
