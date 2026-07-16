package dev.reader

import android.app.Application
import androidx.room.Room
import dev.reader.data.LibraryDatabase

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
 * **The sync guard.** [librarySynced] is the once-per-process guard for
 * `LibraryIndexer.sync()`. It used to live as a plain field on `LibraryActivity` ("started"),
 * whose KDoc claimed it guarded `runLibrary` to "once per process" while it was actually an
 * *instance* field that resets on every Activity recreation — including a rotation, which is not
 * a fresh entry to the library screen per the plan's "indexing runs once on library entry"
 * constraint. Rotation destroys and recreates the Activity but not the Application, so this is
 * where a flag that actually means "once per process" has to live.
 */
class ReaderApplication : Application() {

    val database: LibraryDatabase by lazy {
        Room.databaseBuilder(applicationContext, LibraryDatabase::class.java, "library.db").build()
    }

    /** Flips true the first time [dev.reader.ui.LibraryActivity] starts a sync; never reset. */
    var librarySynced: Boolean = false
}
