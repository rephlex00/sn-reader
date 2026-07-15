package dev.reader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per book on the filesystem. `path` is the primary key: it is the identity the rest of
 * the app (indexer, grid, reader) keys off of.
 *
 * `sizeBytes` + `modifiedAtMs` let [LibraryIndexer][dev.reader.data] (Task 3) diff the directory
 * against the index without opening a single file — that cheap diff is the entire basis of
 * "incremental and opportunistic" indexing.
 *
 * `coverPath` points at a pre-scaled grayscale thumbnail file under `context.filesDir`; it is
 * never a blob column, so Room never has to decode image bytes.
 *
 * `spineIndex` + `charOffset` are a [dev.reader.engine.Locator] flattened to two plain columns.
 * `:data` does not depend on `:engine`, so there is no shared type here — the conversion happens
 * at the DAO boundary in the module that owns both (see Task 6). There is exactly one position per
 * book: it lives on this row, not a separate table.
 *
 * `unreadable` + `unreadableReason` let a corrupt book be shown as broken, with a reason, and never
 * re-cracked on a later scan as long as its `(sizeBytes, modifiedAtMs)` is unchanged.
 *
 * Deliberately absent: `addedAtMs` / `lastOpenedAtMs`. Only sorting would read them, and sorting is
 * deferred — add the column with the feature that needs it, not before.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val path: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val spineIndex: Int,
    val charOffset: Int,
    val unreadable: Boolean,
    val unreadableReason: String?,
)

/**
 * A lightweight projection of just the columns [LibraryIndexer][dev.reader.data] needs to diff the
 * filesystem against the index, without paying for the rest of the row (title, cover path, etc.).
 */
data class BookStat(
    val path: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
)
