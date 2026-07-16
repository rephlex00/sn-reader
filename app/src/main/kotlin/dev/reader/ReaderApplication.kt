package dev.reader

import android.app.Application
import androidx.room.Room
import dev.reader.data.LibraryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

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
 * **The sync guard.** [librarySyncJob] prevents two concurrent [dev.reader.data.LibraryIndexer.sync]
 * calls from racing the same DAO — see [dev.reader.ui.LibraryActivity.onStart]'s KDoc for why
 * [dev.reader.ui.LibraryActivity] re-syncs on *every* entry rather than once per process. There used
 * to be a second field here, `librarySynced`, tracking whether a sync had ever completed
 * successfully in this process; it is gone. It was the owner-ruled-out design: a book side-loaded
 * while the process was alive (which on this e-ink device can mean days) never appeared until the
 * process died, because nothing re-ran the diff on a mere return to the library screen. That field
 * had also caused two separate stranded-library bugs in review (see git history on this file) —
 * both were a variant of "the flag says done, but the work it was set for got cancelled or never
 * covered this path." Deleting it removes the state that made those bugs possible: there is no
 * longer anything that can claim "already synced" out from under a sync that didn't actually run to
 * completion. [librarySyncJob] alone remains, because it guards a genuinely different property
 * (no two syncs in flight at once, ever) that re-syncing on every entry does not retire.
 */
class ReaderApplication : Application() {

    val database: LibraryDatabase by lazy {
        Room.databaseBuilder(applicationContext, LibraryDatabase::class.java, "library.db").build()
    }

    /**
     * The currently in-flight (or most recently launched) sync [Job], if any. `null` before the
     * first sync of this process ever launches.
     *
     * Lives here, not on [dev.reader.ui.LibraryActivity], for the same reason as [database]: a
     * rotation destroys and recreates the Activity but not the Application, and the guard has to
     * survive that recreation to close a real race. An Activity-scoped guard would be blind to
     * the previous instance's job entirely — a fresh instance means a fresh, `null` guard.
     *
     * **The Job semantics that shape how it is used** (see
     * [dev.reader.ui.LibraryActivity.runSync], the only reader and writer). The sync runs on the
     * Activity's `lifecycleScope`, so a rotation cancels it — and `Job.cancel()` moves the Job to
     * the *Cancelling* state synchronously, where `isActive` reads **false immediately**. The
     * coroutine body, meanwhile, only notices cancellation at its next suspension point, and
     * `LibraryIndexer`'s directory walk and per-file extract calls are fully blocking: on a
     * first scan the cancelled body can keep executing on Dispatchers.IO for seconds. So a bare
     * `isActive` check is NOT enough — the recreated Activity's `onStart` would read `false` and
     * happily launch a second sync racing the still-unwinding first one against the same DAO
     * (and, once position writes exist, against the same rows). That is why `runSync` does two
     * things: it checks `isActive` to skip re-entry while a live sync is in flight (the
     * same-instance case), and it makes every newly launched sync `join()` its predecessor
     * before running (the rotation case) — `join()` on a finished job returns immediately, and
     * on a cancelled-but-unwinding one it waits exactly until the body has actually unwound.
     * Together they hold the invariant: no two sync bodies in flight, ever.
     */
    var librarySyncJob: Job? = null

    /**
     * The scope that position writes ([dev.reader.data.BookDao.updatePosition]) are launched into —
     * on both open and the `onStop` flush. It exists to make a terminal position write **survive the
     * Activity's own destruction**: `onStop` is immediately followed by `onDestroy`, which cancels
     * the Activity's `lifecycleScope`, so a quick `onStop -> onDestroy` launched onto `lifecycleScope`
     * could cancel the UPDATE before it commits — the exact "the work got cancelled before it ran"
     * bug this project has hit before. Being application-scoped, this scope is not cancelled when any
     * Activity dies; it lives and dies with the process.
     *
     * It does **not** violate the idle promise. It is entirely dormant unless a coroutine is launched
     * into it — there is no timer, no `postDelayed`, no polling, no periodic work here. It never wakes
     * the process on its own; it only lets a write that the user's own action (leaving the screen)
     * already triggered run to completion. [SupervisorJob] so one failed write never cancels the
     * scope or a sibling write; [Dispatchers.IO] because these are blocking SQLite UPDATEs.
     */
    val positionWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
