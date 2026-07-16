package dev.reader

import android.app.Application
import androidx.room.Room
import dev.reader.data.LibraryDatabase
import kotlinx.coroutines.Job

/**
 * App-scoped singletons that must outlive any single Activity instance.
 *
 * **The database.** Room instances are documented (and expensive enough in practice) to be built
 * once per process, not once per Activity. Before this fix, [dev.reader.ui.LibraryActivity] built
 * a fresh `LibraryDatabase` in `onCreate` with no `onDestroy`/`close()` and no singleton: every
 * screen rotation destroyed and recreated the Activity, which built a *second* live Room instance
 * — its own connection pool — over the same `library.db` file while the first was still open,
 * released only whenever the GC got around to finalizing the old `SQLiteDatabase`. Hoisting the
 * instance here means it is built exactly once per process and lives exactly as long as the
 * process does — the correct lifetime for a handle that Task 6 and Task 8 also need.
 *
 * **The sync guard(s).** [librarySynced] and [librarySyncJob] together guard
 * `LibraryIndexer.sync()` to at most one *successful* run, and at most one *in-flight* run, per
 * process. They used to be a single plain field on `LibraryActivity` ("started"), set true right
 * before launching the sync, whose KDoc claimed it guarded `runLibrary` to "once per process"
 * while it was actually an *instance* field that resets on every Activity recreation — including
 * a rotation, which is not a fresh entry to the library screen per the plan's "indexing runs once
 * on library entry" constraint. Rotation destroys and recreates the Activity but not the
 * Application, so a flag that actually means "once per process" has to live here.
 *
 * Splitting the single flag into two fields (rather than restoring the original one-field design
 * at Application scope) closes two holes that a single "set before launching" flag reintroduces
 * the moment the work it guards is Activity-scoped instead of Application-scoped — see
 * [dev.reader.ui.LibraryActivity.runLibrary]'s KDoc for the full reasoning:
 *
 * 1. A rotation or Back press *during* the first-run sync cancels [dev.reader.data.LibraryIndexer.sync]
 *    (it runs on `lifecycleScope`, which dies with the Activity) partway through. If the flag were
 *    still set beforehand, the recreated Activity (same process) would see it as "already synced"
 *    and never retry — every book the aborted sync never reached would be permanently missing
 *    from the grid until the process dies. [librarySynced] is instead set only when `sync()`
 *    actually *returns*, so a cancelled or failed run is retried on the next entry — cheap, since
 *    the diff is incremental (unchanged files open nothing).
 * 2. Because the flag is no longer set up front, `onResume` re-checking the guard immediately
 *    after `onCreate` returns (the two fire back-to-back in the normal Activity lifecycle, before
 *    the just-launched sync's `withContext(Dispatchers.IO)` has had any chance to complete) would
 *    otherwise see the flag still false and launch a *second* concurrent sync racing the first
 *    one against the same DB. [librarySyncJob] tracks the in-flight [Job] itself so that race is
 *    closed too: a guard check only proceeds when neither "already succeeded" nor "already
 *    running" holds.
 */
class ReaderApplication : Application() {

    val database: LibraryDatabase by lazy {
        Room.databaseBuilder(applicationContext, LibraryDatabase::class.java, "library.db").build()
    }

    /**
     * True only once a sync has *completed successfully* in this process — never set merely
     * because one was started. See the class doc for why "started" was the wrong signal.
     */
    var librarySynced: Boolean = false

    /**
     * The currently in-flight (or most recently launched) sync [Job], if any. `null` before the
     * first sync of this process ever launches. Checked via `?.isActive` — a completed,
     * cancelled, or failed job reads `false` and does not block a retry.
     */
    var librarySyncJob: Job? = null
}
