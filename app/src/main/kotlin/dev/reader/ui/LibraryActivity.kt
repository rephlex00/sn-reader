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
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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

/** [MenuItem.getItemId] -> the [StatusFilter] it selects, or null for an item this menu doesn't own. */
fun statusFilterForMenuItemId(itemId: Int): StatusFilter? = when (itemId) {
    R.id.filter_all -> StatusFilter.ALL
    R.id.filter_not_started -> StatusFilter.NOT_STARTED
    R.id.filter_in_progress -> StatusFilter.IN_PROGRESS
    R.id.filter_finished -> StatusFilter.FINISHED
    else -> null
}

/**
 * The inverse of [statusFilterForMenuItemId]: which menu item's `checkable` state should reflect
 * [status] being the active filter. The mirror of [menuItemIdForSortOrder] for the filter group,
 * used the same way — to restore the check-mark after a rotation rebuilds the toolbar's menu.
 */
fun menuItemIdForStatusFilter(status: StatusFilter): Int = when (status) {
    StatusFilter.ALL -> R.id.filter_all
    StatusFilter.NOT_STARTED -> R.id.filter_not_started
    StatusFilter.IN_PROGRESS -> R.id.filter_in_progress
    StatusFilter.FINISHED -> R.id.filter_finished
}

/**
 * Which view-mode menu item's `checkable` state should reflect [mode] being active. The mirror of
 * the click handling, used to restore the check-mark after a rotation rebuilds the toolbar's menu
 * (a fresh [MenuItem] is unchecked regardless of the persisted [ViewMode]) — exactly as
 * [menuItemIdForSortOrder] does for the sort group.
 */
fun menuItemIdForViewMode(mode: ViewMode): Int = when (mode) {
    ViewMode.TILES -> R.id.view_tiles
    ViewMode.LIST -> R.id.view_list
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
 * [dev.reader.data.BookDao.observeAllSorted] for the chosen [SortOrder]; each emission is held in
 * [latestBooks] and projected through [folderListing] into the folder-aware rows [BookGridAdapter]
 * renders. Sorting is entirely SQL's job (see [BookDao]'s KDoc) — this Activity never re-sorts;
 * within-folder book order simply falls out of that SQL order. Root-filtering and tree-building are
 * [folderListing]'s job, recomputed purely on each emission and on each toggle, with no new queries
 * or watchers.
 *
 * **Folder navigation and toggles.** [currentFolder] is the directory being shown, initialized from
 * `prefs.lastFolderPath` (clamped to the root by [clampToRoot], so a stale folder after a root
 * change collapses to the root) and persisted as the user descends. Tapping a [LibraryRow.Folder]
 * descends; system Back ascends one level until the root, then finishes (an
 * [androidx.activity.OnBackPressedCallback]). The toolbar shows "Library" at the root and the
 * folder's name inside one. The overflow menu adds a tiles/list view toggle and a flatten toggle
 * alongside the existing sort group; each writes [LibraryPrefs] immediately and re-renders from
 * [latestBooks] without waiting for a new Room emission. Tapping a book still starts [ReaderActivity]
 * with its path, unchanged, including the unreadable-retry tap.
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
     * The toolbar, held as a field (not an `onCreate` local) so [render] can retitle it as the user
     * navigates and the toggle handlers can read/flip their menu items' check state. `protected`:
     * [LibraryActivityNavigationTest] drives its menu items and reads its title.
     */
    protected lateinit var toolbar: Toolbar

    /**
     * The directory currently shown, an invariant-clamped path that is [rootPath][LibraryPrefs.rootPath]
     * itself or somewhere beneath it (see [clampToRoot]). Initialized from `prefs.lastFolderPath`,
     * updated on folder descent/ascent, and persisted to `prefs.lastFolderPath`. `protected`:
     * [LibraryActivityNavigationTest] asserts where navigation lands.
     */
    protected var currentFolder: String = ""

    /**
     * The most recent [dev.reader.data.BookDao.observeAllSorted] emission, held so a view/flatten
     * toggle or a folder descent can re-render immediately through [folderListing] without waiting
     * for a new Room emission. The whole library (root-filtering is [folderListing]'s job), in SQL
     * sort order.
     */
    private var latestBooks: List<BookEntity> = emptyList()

    /**
     * The live search query and reading-status filter, both transient — neither is persisted to
     * [LibraryPrefs] (unlike sort/view/flatten), so a fresh Activity always starts unfiltered.
     * [render] branches to [findBooks] over [latestBooks] whenever [isFilterActive] is true;
     * see [render]'s KDoc.
     */
    private var searchQuery: String = ""
    private var statusFilter: StatusFilter = StatusFilter.ALL

    /**
     * The [rootPath][LibraryPrefs.rootPath] this Activity last rendered against, so [onStart] can
     * detect a root change made in Settings and reset [currentFolder] accordingly before the sync
     * repopulates (Task 8a already nulls `lastFolderPath` on a root change).
     */
    private var lastRoot: String = ""

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

        // Where to land: the remembered folder, clamped so a stale path (a since-deleted folder,
        // or one under a now-changed root) collapses to the root — the same clamp folderListing
        // applies, kept here too so the toolbar title and Back agree with what is shown.
        lastRoot = prefs.rootPath
        currentFolder = clampToRoot(prefs.lastFolderPath ?: lastRoot, lastRoot)

        val spanCount = spanCountFor(resources.displayMetrics.widthPixels, COLUMN_WIDTH_PX)
        val gridLayoutManager = GridLayoutManager(this@LibraryActivity, spanCount).apply {
            // Book tiles take one column; everything else — folder tiles (full-width dividers
            // above the covers) and every list-mode row — spans the whole width. Reads the
            // adapter's own view type so there is a single source of truth for "is this a tile".
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.getItemViewType(position) == BookGridAdapter.VIEW_TYPE_BOOK_TILE) 1 else spanCount
            }
        }
        val recyclerView = RecyclerView(this).apply {
            layoutManager = gridLayoutManager
            // The single most important line in this Activity: DefaultItemAnimator (the
            // RecyclerView default) cross-fades items in/out/through on every list change, which
            // on e-ink is a visible smear and is banned outright as animation, full stop. A view
            // toggle or a folder descent is one clean redraw, never an animated transition.
            itemAnimator = null
            stopScrollAnimations()
        }
        adapter = BookGridAdapter(lifecycleScope, ::openBook, ::openFolder)
        recyclerView.adapter = adapter

        toolbar = Toolbar(this).apply {
            title = titleFor(currentFolder, lastRoot)
            inflateMenu(R.menu.menu_library)
            // Every checkableBehavior="single" group (sort, view mode, filter) and the standalone
            // flatten toggle start unchecked on a freshly inflated menu (every rotation gets a new
            // one), regardless of the persisted/current state. Restore all four marks here so the
            // active choices stay visible after a restore, not just correct. statusFilter is
            // transient (always ALL on a fresh Activity), but this still has to run: a rotation
            // rebuilds the menu from scratch even mid-session.
            menu.findItem(menuItemIdForSortOrder(currentSort))?.isChecked = true
            menu.findItem(menuItemIdForViewMode(prefs.viewMode))?.isChecked = true
            menu.findItem(R.id.action_flatten)?.isChecked = prefs.flatten
            menu.findItem(menuItemIdForStatusFilter(statusFilter))?.isChecked = true
            setOnMenuItemClickListener(::onMenuItemClicked)

            // Title/author search: a live filter over latestBooks, not a new query. Collapsing the
            // action view (the user tapping away, or the Back handler below) clears the query the
            // same way an empty typed query would.
            val searchItem = menu.findItem(R.id.action_search)
            (searchItem.actionView as SearchView).setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    searchQuery = newText.orEmpty()
                    render()
                    return true
                }
            })
            searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    searchQuery = ""
                    render()
                    return true
                }
            })
        }

        // System Back walks up the folder tree one level at a time, clamped to the root; only at
        // the root does it fall through to the default finish. An OnBackPressedCallback, not the
        // deprecated onBackPressed override, per the AndroidX guidance.
        onBackPressedDispatcher.addCallback(this) {
            // An active search/filter is dismissed by Back before folder-ascend/finish even runs:
            // the user's mental model is "Back gets me out of what I just did", and what they just
            // did was narrow the grid, not navigate. Clearing returns to currentFolder exactly as
            // it was (untouched by the filtered branch), so this never touches folder state.
            if (isFilterActive(searchQuery, statusFilter)) {
                searchQuery = ""
                statusFilter = StatusFilter.ALL
                toolbar.menu.findItem(R.id.filter_all)?.isChecked = true
                toolbar.menu.findItem(R.id.action_search)
                    ?.takeIf { it.isActionViewExpanded }
                    ?.collapseActionView() // also fires onMenuItemActionCollapse -> render(); harmless if redundant
                render()
                return@addCallback
            }
            val root = clampToRoot(prefs.rootPath, prefs.rootPath)
            if (currentFolder != root) {
                val parent = File(currentFolder).parent ?: root
                currentFolder = clampToRoot(parent, prefs.rootPath)
                // At the root, lastFolderPath is null (its "the root" sentinel); below it, remember
                // exactly where we are so a later launch lands here again.
                prefs.lastFolderPath = if (currentFolder == root) null else currentFolder
                render()
            } else {
                // Nothing above the root to ascend into: perform the default Back (finish). Disable
                // this callback first so the dispatcher moves on to the framework's own handling.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        emptyStateView = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(48))
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
            emptyStateView.text = getString(R.string.library_permission_needed)
            emptyStateView.visibility = View.VISIBLE
            // The message says "tap here", so it has to be tappable. Naming the fix without
            // offering it is worse than saying nothing, especially on this ROM where the relevant
            // Settings screen is buried (see StorageAccess.requestAllFilesAccess's fallbacks).
            emptyStateView.isClickable = true
            emptyStateView.setOnClickListener { requestAllFilesAccess() }
            return
        }
        // Granted: drop the retry handler so the empty state is inert text again in its other roles
        // (a zero-match filter, below), where a tap must do nothing.
        emptyStateView.isClickable = false
        emptyStateView.setOnClickListener(null)
        // Reflect the current state instead of hardcoding GONE: render() is the second writer to
        // emptyStateView (a zero-match search/status filter shows the no-matches message — see its own
        // KDoc), and a stale library's sync() below does no DB writes when nothing changed, so
        // observeAllSorted never re-emits and render() never reruns on its own. Without this,
        // backgrounding and reopening the app with a zero-match filter active would wipe that
        // message to GONE here and leave a blank grid until the next unrelated interaction.
        render()

        // A root change made in Settings while this Activity stayed alive: Task 8a already nulled
        // lastFolderPath (a folder under the old root is meaningless under the new one), so reset
        // currentFolder from it before the sync below repopulates the library.
        val root = prefs.rootPath
        if (root != lastRoot) {
            lastRoot = root
            currentFolder = clampToRoot(prefs.lastFolderPath ?: root, root)
            render()
        }

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

    /**
     * The toolbar overflow's single non-sort action: open [SettingsActivity]. Sort items are
     * delegated to [onSortMenuItemClicked]; anything this menu doesn't own returns false so the
     * framework can fall back to its default handling.
     */
    private fun onMenuItemClicked(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            R.id.view_tiles -> {
                setViewMode(ViewMode.TILES, item)
                return true
            }
            R.id.view_list -> {
                setViewMode(ViewMode.LIST, item)
                return true
            }
            R.id.action_flatten -> {
                val flatten = !prefs.flatten
                prefs.flatten = flatten
                item.isChecked = flatten
                render()
                return true
            }
        }
        val status = statusFilterForMenuItemId(item.itemId)
        if (status != null) {
            statusFilter = status
            item.isChecked = true // radio group: also unchecks the sibling
            render()
            return true
        }
        return onSortMenuItemClicked(item)
    }

    private fun setViewMode(mode: ViewMode, item: MenuItem) {
        item.isChecked = true // radio group: also unchecks the sibling
        if (mode == prefs.viewMode) return
        // Persist immediately (deferred apply(), no I/O on this callback) and re-render from the
        // held book list — a toggle must not wait for a new Room emission. render() hands the mode
        // to the adapter, which forces the one clean rebind a view-type switch needs.
        prefs.viewMode = mode
        render()
    }

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
                Toast.makeText(this@LibraryActivity, getString(R.string.error_scan_library), Toast.LENGTH_LONG).show()
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
            // repeatOnLifecycle(STARTED), not a bare collect: a bare collect from onCreate keeps the
            // Room Flow subscribed for the Activity's whole life, including the entire time the user
            // is inside a book with this Activity stopped — which is most of the app's runtime.
            // Room's InvalidationTracker keeps a standing observer (and its periodic table check)
            // alive for as long as anything is collecting, so the process never actually settles to
            // zero while reading. Gating on STARTED drops the observer in onStop and re-subscribes
            // in onStart, which also re-emits current data, so the grid is never stale on return.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // `collect`, not `collectLatest`: ListAdapter.submitList doesn't suspend, so
                // collectLatest's cancel-the-previous-collector-body semantics never actually engage
                // here — there is nothing in flight for a new emission to cancel. Plain `collect`
                // says what's really happening: every emission is submitted in order.
                db.bookDao().observeAllSorted(order).collect { books ->
                    latestBooks = books
                    render()
                }
            }
        }
    }

    /**
     * Project [latestBooks] through [folderListing] for the current folder, view mode, and flatten
     * setting, and hand the rows to the adapter — the one render path every trigger (a Room
     * emission, a folder descent/ascent, a toggle, a root change, a search/filter edit) funnels
     * through. Pure and cheap at library scale: no new queries, no watchers. [currentFolder] is
     * re-clamped here so it stays an invariant even if the root shifted underneath it, keeping the
     * toolbar title and Back in step with what folderListing actually scopes to.
     *
     * When [isFilterActive] (a non-blank [searchQuery] or a non-ALL [statusFilter]), the folder
     * tree is bypassed entirely: [findBooks] runs flat over all of [latestBooks], regardless of
     * [currentFolder], and the result is every matching [LibraryRow.Book] with no folder rows.
     * [currentFolder] itself is never touched by this branch, so clearing the filter always
     * restores the same folder the user was in. A filtered-and-empty result shows "No books
     * match." in [emptyStateView] instead of a silent blank grid — the same reasoning [onStart]'s
     * permission-denied message already applies to a different empty case.
     */
    private fun render() {
        val root = prefs.rootPath
        currentFolder = clampToRoot(currentFolder, root)
        val filterActive = isFilterActive(searchQuery, statusFilter)
        val rows: List<LibraryRow> = if (filterActive) {
            findBooks(latestBooks, searchQuery, statusFilter).map { LibraryRow.Book(it) }
        } else {
            folderListing(latestBooks, root, currentFolder, prefs.flatten)
        }
        adapter.render(rows, prefs.viewMode)
        // While a filter is active the grid is flat results across the whole library, not a
        // listing of currentFolder — showing the folder's name in the toolbar would misleadingly
        // imply the results are scoped to it. Fall back to the root title (titleFor(root, root),
        // i.e. "Library") for as long as the filter stays active; clearing it restores the folder
        // name via the same call on the next render().
        toolbar.title = if (filterActive) titleFor(root, root) else titleFor(currentFolder, root)
        if (filterActive && rows.isEmpty()) {
            emptyStateView.text = getString(R.string.library_empty_no_matches)
            emptyStateView.visibility = View.VISIBLE
        } else {
            emptyStateView.visibility = View.GONE
        }
    }

    /** "Library" at the root, otherwise the current folder's own name. */
    private fun titleFor(folder: String, root: String): String =
        if (folder == clampToRoot(root, root)) getString(R.string.library_title) else File(folder).name

    /**
     * Descend into a tapped folder: make it the current scope, remember it so a later launch lands
     * here, and re-render from the already-held [latestBooks]. The path came from [folderListing]
     * so it is already a real descendant of the root; no clamp needed, though [render]'s own clamp
     * would catch it anyway. `protected`: [LibraryActivityNavigationTest] drives it directly.
     */
    protected fun openFolder(path: String) {
        currentFolder = path
        prefs.lastFolderPath = path
        render()
    }

    /**
     * The adapter's tap callback. `protected`, not `private`:
     * [LibraryActivityInteractionTest] drives it directly to exercise the debounce and the
     * unreadable-retry path without simulating RecyclerView touch dispatch.
     */
    protected fun openBook(book: BookEntity) {
        if (book.unreadable) {
            // The stored reason is a wrapped exception message; it goes to the log, not to the
            // reader. The shelf badge already follows this rule (see statusTextRes) and this path
            // has to agree with it, or the same book explains itself two different ways.
            Log.w(TAG, "openBook: unreadable ${book.path}: ${book.unreadableReason ?: "no reason recorded"}")
            Toast.makeText(this, getString(R.string.error_open_book), Toast.LENGTH_LONG).show()
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
