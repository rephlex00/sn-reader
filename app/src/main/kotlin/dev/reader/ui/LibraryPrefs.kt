package dev.reader.ui

import android.content.Context
import android.os.Environment
import dev.reader.data.SortOrder
import java.io.File

/** How the unified library renders its rows. Consumed by Task 8c; persisted now so the choice
 * survives process death from the moment it can be set. */
enum class ViewMode { TILES, LIST }

/**
 * The library's persisted preferences, a thin typed wrapper over one
 * `SharedPreferences("library_prefs")`. It is the single source of truth for the settings that
 * used to survive only a rotation (the sort order rode a saved-instance Bundle and silently reset
 * to Title on every cold launch) or not at all (the book root was hardcoded).
 *
 * Every getter is total: an unknown or corrupt stored value falls back to the default rather than
 * throwing (the same posture as [sortOrderFromSavedValue]), so a hand-edited or partially-written
 * prefs file can never crash the launcher. Writes go through `edit().put….apply()`, so the only
 * I/O they do off the caller's thread is whatever `apply()` already defers.
 *
 * [viewMode], [flatten], and [lastFolderPath] are written now but not consumed until Task 8c
 * builds the folder-aware view — they are stored here so that view inherits a value that has been
 * persisting all along, rather than starting blind.
 */
class LibraryPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

    /**
     * The one directory the library indexes and shows. Default `/Document` — where Supernote keeps
     * user books. Re-pointing this is safe for reading positions: [dev.reader.data.LibraryIndexer]
     * scopes its deletions to the current root (see its KDoc), so books outside the new root are
     * hidden, never deleted.
     */
    var rootPath: String
        get() = prefs.getString(KEY_ROOT, null) ?: defaultRootPath()
        set(value) = prefs.edit().putString(KEY_ROOT, value).apply()

    var viewMode: ViewMode
        get() = ViewMode.entries.firstOrNull { it.name == prefs.getString(KEY_VIEW_MODE, null) }
            ?: ViewMode.TILES
        set(value) = prefs.edit().putString(KEY_VIEW_MODE, value.name).apply()

    var flatten: Boolean
        get() = prefs.getBoolean(KEY_FLATTEN, false)
        set(value) = prefs.edit().putBoolean(KEY_FLATTEN, value).apply()

    var sortOrder: SortOrder
        get() = sortOrderFromSavedValue(prefs.getString(KEY_SORT_ORDER, null))
        set(value) = prefs.edit().putString(KEY_SORT_ORDER, value.name).apply()

    /**
     * The folder the library was last showing, so a later launch lands where the user left off
     * rather than back at the root every time. Null means "the root" — the first-ever launch, or a
     * root change (which clears it, since a remembered folder under the old root is meaningless
     * under the new one). Consumed by Task 8c.
     */
    var lastFolderPath: String?
        get() = prefs.getString(KEY_LAST_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_LAST_FOLDER, value).apply()

    private companion object {
        const val KEY_ROOT = "root_path"
        const val KEY_VIEW_MODE = "view_mode"
        const val KEY_FLATTEN = "flatten"
        const val KEY_SORT_ORDER = "sort_order"
        const val KEY_LAST_FOLDER = "last_folder_path"
    }
}

/** The default book root: `/Document` under external storage, resolved at read time (not a
 * constant) because [Environment.getExternalStorageDirectory] is only meaningful on a device. */
private fun defaultRootPath(): String =
    File(Environment.getExternalStorageDirectory(), "Document").path
