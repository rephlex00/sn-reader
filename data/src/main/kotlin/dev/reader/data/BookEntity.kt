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
 * `addedAtMs` is when this path first entered the library, for "recently added" sort. It survives
 * a re-index of the same path unconditionally — even a genuine content replacement (changed
 * `sizeBytes`) is not a new acquisition, just a new version of the same library entry — so it is
 * set once, on first discovery, and carried forward by [LibraryIndexer] from then on.
 *
 * `lastOpenedAtMs` is null until the reader opens this book at least once; [BookDao.updatePosition]
 * is its intended writer (Plan 2 Task 6 — no production caller yet). It survives a re-index of the
 * same path for the same reason as `addedAtMs`: it isn't a coordinate into file content (unlike
 * `spineIndex`/`charOffset`), just a historical fact about this library entry. Mechanically that
 * happens two ways in [LibraryIndexer]: the full-row upsert for new/content-changed rows carries
 * it forward explicitly, and the partial [BookDao.updateMetadata] for content-unchanged rows
 * simply never touches it.
 *
 * No SQLite indices on the sort columns (`title`, `author`, `addedAtMs`, `lastOpenedAtMs`) —
 * considered and deliberately omitted: the library is hundreds of rows at most, read via
 * whole-table queries ([BookDao.observeAllSorted]) where a sort of that size is microseconds,
 * while every index taxes each upsert the sync performs. Revisit only if the library model ever
 * changes scale.
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
    val addedAtMs: Long,
    val lastOpenedAtMs: Long?,
    /**
     * Whole-book reading progress in `[0,1]` the last time the reader turned a page, or null for a
     * book never opened since this column shipped (schema v2). The reader computes it from the
     * byte-weighted [dev.reader.engine.bookProgress] — the same value the in-book progress bar
     * shows — and writes it alongside the position on every turn, so the library can show an
     * accurate percentage without opening the file. Nullable and defaulted so [upsertAll] on a
     * newly-indexed book, and the v1->v2 migration on an existing one, both leave it unset until
     * the reader fills it in.
     */
    val progressFraction: Float? = null,
)

/**
 * A lightweight projection of just the columns [LibraryIndexer][dev.reader.data] needs to diff the
 * filesystem against the index, without paying for the rest of the row (title, etc.).
 *
 * `addedAtMs` and `lastOpenedAtMs` ride along here (rather than requiring a second query) so the
 * indexer can carry both forward across a re-index without an extra per-row fetch beyond the one
 * [LibraryIndexer] already does for same-content position/title preservation.
 *
 * `coverPath` rides along for the same reason: it is the "old" thumbnail path the indexer diffs
 * against the freshly-produced one to know whether a cover file has gone stale and must be deleted
 * (a vanished book, or a replaced one whose new cover lives at a different path) — without it, that
 * would need a second per-row query.
 */
data class BookStat(
    val path: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val addedAtMs: Long,
    val lastOpenedAtMs: Long?,
    val coverPath: String?,
)
