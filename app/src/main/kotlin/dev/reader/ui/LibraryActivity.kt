package dev.reader.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * Parses a stored [SortOrder] name, falling back to [SortOrder.TITLE] for `null` (nothing stored
 * yet — first launch) or an unrecognized name, rather than throwing. Shared by [LibraryPrefs],
 * which is now the single source of truth for the active sort (it persists across process death,
 * where the old saved-instance Bundle survived only a rotation).
 */
fun sortOrderFromSavedValue(value: String?): SortOrder =
    SortOrder.entries.firstOrNull { it.name == value } ?: SortOrder.TITLE

/**
 * The library grid — the app's entry point (see the manifest: this is now the LAUNCHER activity,
 * [ReaderActivity] is not). `RecyclerView` + `GridLayoutManager`, Views only.
 *
 * On every entry — cold start, a rotation, or simply returning to this screen from [ReaderActivity]
 * or the background — [onStart] re-runs [LibraryIndexer.sync] on the library roots (see its KDoc for
 * why "every entry" and not "once per process"). [onCreate] separately starts observing
 * [dev.reader.data.BookDao.observeAllSorted] for the chosen [SortOrder] and hands each emission
 * straight to [BookGridAdapter] — sorting is entirely SQL's job (see [BookDao]'s KDoc), this
 * Activity never re-sorts a list. Tapping a cell starts [ReaderActivity] with that book's path.
 *
 * **No animations, no background service.** [RecyclerView.setItemAnimator] is set to null:
 * `DefaultItemAnimator` cross-fades on every change, which is a smear on e-ink and animation is
 * banned outright regardless of panel. Each sync runs in a coroutine that completes and stops —
 * there is no `Service`/`WorkManager`/`FileObserver`/timer anywhere in this class, so the idle
 * promise (0% CPU once the grid has settled) is unaffected by re-syncing more often: a redundant
 * sync over an unchanged library is just a stat call per file (see [LibraryIndexer]'s KDoc), not a
 * scan that reopens anything.
 *
 * `open`, not `final`: [LibraryActivityRecreationTest]'s Robolectric coverage recreates this Activity via a
 * test subclass that overrides [isAllFilesAccessGranted] and [createSync] — the two points where
 * this class would otherwise reach out to real device permissions and a real EPUB-backed indexer,
 * neither of which a JVM test can exercise meaningfully. No other member is `open`.
 */
open class LibraryActivity : AppCompatActivity() {

    /**
     * [LibraryDatabase] lives on [ReaderApplication], not here — see its KDoc. This Activity is
     * recreated on every rotation; the Application is not, so the database had to move for it to
     * be built exactly once per process rather than once per Activity instance.
     */
    private val db: LibraryDatabase
        get() = (application as ReaderApplication).database

    /**
     * Persisted library settings — the source of truth for the active [SortOrder] (survives cold
     * launch, unlike the saved-instance Bundle it replaced) and the book root [createSync] walks.
     */
    private val prefs by lazy { LibraryPrefs(this) }

    /** `protected`, not `private`: [LibraryActivityRecreationTest] reads `itemCount` after recreation. */
    protected lateinit var adapter: BookGridAdapter

    /**
     * The persistent "grant all-files access" message shown instead of a silent blank grid when
     * the user comes back from Settings without granting. `protected`, not `private`:
     * [LibraryActivityInteractionTest] asserts on its visibility and text.
     */
    protected lateinit var emptyStateView: TextView

    /**
     * Debounces [openBook]. E-ink's delayed visual feedback makes double-taps routine — nothing
     * on screen changes for hundreds of milliseconds after the first tap, so users tap again —
     * and without this guard both taps start a [ReaderActivity]: two Activities stacked, two open
     * ZipFiles over the same book, and (once Task 6 wires position memory) two writers racing the
     * same row. Set when a launch starts, reset in [onResume] — the moment this Activity is
     * interactive again the next tap is a fresh intent. `singleTop` on [ReaderActivity] (see the
     * manifest) dedupes the cross-Activity re-entry case this same-frame guard can't see.
     */
    private var launching = false

    private var currentSort = SortOrder.TITLE
    private var observeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The active sort comes from prefs, not the savedInstanceState Bundle: prefs persists it
        // across process death, so a cold launch now restores the user's chosen order instead of
        // snapping back to TITLE. Rotation is covered too — the value was already written when the
        // user picked it, so the recreated Activity reads the same thing.
        currentSort = prefs.sortOrder

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

        emptyStateView = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(48, 96, 48, 96)
            visibility = View.GONE
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(emptyStateView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(recyclerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        if (!isAllFilesAccessGranted()) {
            requestAllFilesAccess()
            return
        }
        // Start observing before onStart's sync even launches — see this class's KDoc and
        // onStart's. Rows already in the DB (a prior process's index, or a sync this same process
        // already completed before a rotation) paint immediately instead of waiting on a fresh
        // scan; Room's Flow then re-emits on its own as onStart's sync writes rows.
        observeSorted(currentSort)
    }

    override fun onStart() {
        super.onStart()
        if (!isAllFilesAccessGranted()) {
            // The user came back from Settings without granting (or hasn't left yet). A silent
            // blank grid here is indistinguishable from a broken app — say what's missing, and
            // persistently (not a Toast): the user may sit on this screen for a while before
            // acting.
            emptyStateView.text = "Grant all-files access in Settings to see your library."
            emptyStateView.visibility = View.VISIBLE
            return
        }
        emptyStateView.visibility = View.GONE
        if (observeJob == null) {
            // Permission was granted after onCreate returned early above (the user just came back
            // from the system "All files access" settings screen, same Activity instance) — start
            // observing now so this instance isn't left with a permanently blank grid.
            observeSorted(currentSort)
        }
        runSync()
    }

    override fun onResume() {
        super.onResume()
        // Re-arm openBook: whatever launch set this flag has either landed (we're back from the
        // reader) or never happened; either way the next tap is a fresh intent, not a double-tap.
        launching = false
    }

    /**
     * Whether all-files access is currently granted. A thin `protected open` wrapper around the
     * real [hasAllFilesAccess] extension so [LibraryActivityRecreationTest]'s Robolectric coverage — which
     * cannot grant a real Android 11 all-files-access permission — can stub this one point via a
     * test subclass instead of faking the underlying `Environment.isExternalStorageManager()`.
     */
    protected open fun isAllFilesAccessGranted(): Boolean = hasAllFilesAccess()

    private fun onSortMenuItemClicked(item: MenuItem): Boolean {
        val order = sortOrderForMenuItemId(item.itemId) ?: return false
        setSortOrder(order)
        return true
    }

    private fun setSortOrder(order: SortOrder) {
        if (order == currentSort) return
        currentSort = order
        // Persist immediately so the choice survives cold launch, not only this Activity instance.
        // The write is a deferred apply() (see LibraryPrefs); no I/O lands on this callback's thread.
        prefs.sortOrder = order
        observeSorted(order)
    }

    /**
     * Runs the incremental sync — unless one is already in flight this process — every time
     * [onStart] fires: cold start, a rotation, returning from [ReaderActivity], or returning from
     * the background. This is the owner-ruled design: index **on every entry**, not once per
     * process. A book side-loaded while the process stays alive (which on this e-ink device can
     * mean days) must show up the next time the user looks at the library, not only after the
     * process happens to die and restart.
     *
     * **Why re-syncing this often is cheap.** [LibraryIndexer.sync] is a stat walk of
     * `(path, size, mtime)` that opens a file only when one is new or changed — for an unchanged
     * 15-book library that's 15 stat calls, not 15 file opens. Re-running it on every entry spends
     * exactly the budget "incremental" bought, rather than banking it behind a flag.
     *
     * **Why there is no "already synced" flag anymore.** There used to be one
     * (`ReaderApplication.librarySynced`), set only once `sync()` returned, precisely so a
     * cancelled run wouldn't be mistaken for a completed one. That reasoning was sound but the
     * premise — "a completed sync never needs to run again this process" — is exactly what the
     * owner ruled out. Removing the flag removes the entire bug class it enabled: there is no
     * longer any state that can say "already done" while the work that was supposed to do it got
     * cancelled or skipped a path. A rotation mid-sync now just means [onStart] re-runs the sync
     * on the recreated Activity and self-heals, instead of relying on a flag being set at exactly
     * the right moment.
     *
     * **Why [ReaderApplication.librarySyncJob] is still needed, on top of removing the flag.**
     * The invariant it guards is "no two sync bodies in flight, ever", and it takes two mechanisms
     * to hold it (see [ReaderApplication.librarySyncJob]'s KDoc for the Job semantics):
     * - The `isActive` check below handles the same-instance case: `onStart` can run again without
     *   an intervening `onDestroy` (the user backgrounds and quickly re-foregrounds the app while
     *   a slow first-run scan is still going), and the still-active job reads `true` and skips.
     * - The `previous?.join()` inside the launched coroutine handles the rotation case: the old
     *   instance's destruction *cancelled* the old job, so it reads `isActive == false` here even
     *   while its body is still executing a blocking walk/extract on Dispatchers.IO. The new sync
     *   therefore waits for the old body to actually unwind before doing anything. The join is
     *   wrapped in [NonCancellable] so the wait survives *this* activity's own destruction too —
     *   otherwise a second rotation would complete the waiter instantly and let its successor
     *   start alongside the still-unwinding original body.
     *
     * **Why [CancellationException] is caught and rethrown, not left to the broad `catch`
     * below it.** [CancellationException] extends [Exception] — this project has already been
     * bitten once by a catch-all silently swallowing it (see [LibraryIndexer.sync]'s own KDoc).
     * Swallowing it here would pop a spurious error Toast for a sync the user simply rotated or
     * backed away from, not one that actually failed.
     */
    private fun runSync() {
        val app = application as ReaderApplication
        if (app.librarySyncJob?.isActive == true) {
            Log.i(TAG, "runSync: SKIPPED (already in progress this process)")
            return
        }
        Log.i(TAG, "runSync: STARTED")

        // The predecessor may be cancelled-but-still-unwinding (see this method's KDoc): capture
        // it before overwriting the field, and make the new coroutine wait it out below.
        val previous = app.librarySyncJob
        app.librarySyncJob = lifecycleScope.launch {
            // join() on a job that already completed is free; on a cancelled one whose body is
            // still executing a blocking section on Dispatchers.IO, it suspends exactly until
            // that body has unwound. Never a deadlock: `previous` is already cancelled or
            // completed here (the isActive guard above returned otherwise), so it cannot be
            // waiting on anything this coroutine owns.
            //
            // NonCancellable, because join() is itself a cancellable suspend: without it, a
            // second destruction while we wait here would complete THIS job instantly (join
            // throws CancellationException) while `previous`'s body is still unwinding — and
            // the next runSync, joining us, would then start a sync concurrent with that body.
            // Wrapped, a cancelled waiter stays in Cancelling until its predecessor has actually
            // unwound, so the no-two-bodies invariant holds through any chain of recreations.
            // The wait is bounded: the predecessor is cancelled and exits at its next
            // ensureActive()/suspension. createSync() below stays outside the wrapper — a
            // cancelled waiter must still not START new work.
            withContext(NonCancellable) { previous?.join() }
            ensureActive()
            try {
                createSync().invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed sync (e.g. both roots denied) must not leave the grid stuck empty with
                // no explanation, but it also must not crash the launcher activity. There is no
                // flag to leave in a "failed" state: the next entry (or backgrounding and
                // returning) just tries again.
                Toast.makeText(this@LibraryActivity, "Couldn't scan your library: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Builds the suspend function [runSync] runs to perform one sync. `protected open`, not
     * inlined into [runSync], purely as a test seam: [LibraryActivityRecreationTest]'s Robolectric
     * coverage substitutes one that suspends until cancelled (via
     * [kotlinx.coroutines.awaitCancellation]) to deterministically exercise the
     * cancel-mid-sync path without needing a real multi-second EPUB scan to race against.
     * Production always returns the real EPUB-backed [LibraryIndexer] over the device's library
     * roots.
     */
    protected open fun createSync(): suspend () -> IndexResult = {
        // One configurable root, read fresh each sync so a change made in Settings is picked up on
        // the next onStart with no observer machinery. The old hardcoded second root (/EXPORT) is
        // dropped — owner-verified to hold only note exports, never books. Re-pointing the root is
        // safe: LibraryIndexer scopes its deletions to the current root (see its KDoc), so books
        // outside it are hidden, not deleted.
        val roots = listOf(File(prefs.rootPath))
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

    /**
     * The adapter's tap callback. `protected`, not `private`:
     * [LibraryActivityInteractionTest] drives it directly to exercise the debounce and the
     * unreadable-retry path without simulating RecyclerView touch dispatch.
     */
    protected fun openBook(book: BookEntity) {
        if (book.unreadable) {
            Toast.makeText(this, "Unreadable: ${book.unreadableReason ?: "unknown reason"}", Toast.LENGTH_LONG).show()
            // A transiently-unreadable book (half-synced file, permission hiccup) would otherwise
            // be unreadable forever: the indexer never re-cracks a row whose (size, mtime) is
            // unchanged. Tapping it is the user asking "try again" — invalidate the stored stat
            // so the sync triggered next re-cracks exactly this file. User-triggered and bounded:
            // one tap buys one retry, no timers, no polling (the idle promise holds).
            lifecycleScope.launch {
                db.bookDao().clearStat(book.path)
                runSync()
            }
            return
        }
        // Debounce, not a lock: see [launching]. A second tap in the same interactive window is
        // always the double-tap artifact, never a genuine second intent — the first tap's
        // ReaderActivity hasn't covered the screen yet.
        if (launching) return
        launching = true
        startActivity(
            Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_PATH, book.path)
        )
    }

    private companion object {
        const val TAG = "LibraryActivity"
    }
}
