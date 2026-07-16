package dev.reader.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * How the library grid orders books (Task 5). Sorting happens in SQL — see the per-order queries
 * backing [BookDao.observeAllSorted] — never by re-sorting a list in Kotlin on every emission.
 */
enum class SortOrder {
    TITLE,
    AUTHOR,
    RECENTLY_ADDED,
    RECENTLY_OPENED,
}

@Dao
interface BookDao {

    @Query("SELECT * FROM books")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE path = :path")
    suspend fun getByPath(path: String): BookEntity?

    /** Insert new rows or update existing ones (keyed on `path`) in a single statement. */
    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("DELETE FROM books WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    /**
     * The cheap projection the incremental indexer diffs against a directory walk — just enough
     * to detect new, changed, and vanished files without opening any of them. `addedAtMs`,
     * `lastOpenedAtMs`, and `coverPath` ride along so [LibraryIndexer] can carry them forward (or
     * diff a stale cover file away) across a re-index without a second query (see [BookStat]).
     */
    @Query("SELECT path, sizeBytes, modifiedAtMs, addedAtMs, lastOpenedAtMs, coverPath FROM books")
    suspend fun getAllStats(): List<BookStat>

    /**
     * Written by the reader: on open (setting [BookEntity.lastOpenedAtMs] to now) and on every
     * page turn thereafter. Never touches any other column.
     *
     * Deliberately NOT `suspend`: a suspend `@Query` hops to Room's own multi-threaded query
     * executor, so two writes launched in order could still acquire SQLite's write lock out of
     * order. As a blocking call it executes on the caller's thread — and the only production
     * caller is `ReaderApplication.positionWriteScope`, a single-threaded dispatcher, which is
     * what makes "writes commit in launch order" actually true rather than merely intended.
     * Callers must not invoke this on the main thread.
     */
    @Query(
        "UPDATE books SET spineIndex = :spineIndex, charOffset = :charOffset, " +
            "lastOpenedAtMs = :lastOpenedAtMs WHERE path = :path",
    )
    fun updatePosition(path: String, spineIndex: Int, charOffset: Int, lastOpenedAtMs: Long)

    /**
     * [LibraryIndexer]'s write path for a re-indexed row whose CONTENT is unchanged (same
     * `sizeBytes`: an mtime-only bump, or a [clearStat] retry): refreshes everything a re-crack
     * of the same bytes can legitimately change, and deliberately never touches
     * `spineIndex`/`charOffset`/`lastOpenedAtMs`/`addedAtMs`. That omission is the point — the
     * indexer reads its stats snapshot potentially seconds before it flushes, and round-tripping
     * the position columns through that snapshot (as a whole-row upsert would) reverts any
     * [updatePosition] that committed in between: the every-return-from-reader race once Task 6
     * wires position writes. Genuinely new or content-changed rows (where a position reset is
     * intended) still go through [upsertAll].
     */
    @Query(
        "UPDATE books SET title = :title, author = :author, coverPath = :coverPath, " +
            "sizeBytes = :sizeBytes, modifiedAtMs = :modifiedAtMs, unreadable = :unreadable, " +
            "unreadableReason = :unreadableReason WHERE path = :path",
    )
    suspend fun updateMetadata(
        path: String,
        title: String,
        author: String?,
        coverPath: String?,
        sizeBytes: Long,
        modifiedAtMs: Long,
        unreadable: Boolean,
        unreadableReason: String?,
    )

    /**
     * Invalidates a row's stored stat so the next [LibraryIndexer] sync re-cracks the file even
     * though its on-disk `(size, mtime)` is unchanged. -1 can never equal a real mtime
     * ([java.io.File.lastModified] returns 0 on error and a positive epoch millis otherwise), so
     * the stat diff is guaranteed to see this row as changed exactly once — the sync then stores
     * the real mtime back. User-triggered only (tapping an unreadable book buys one immediate
     * retry); nothing polls or schedules behind this.
     */
    @Query("UPDATE books SET modifiedAtMs = -1 WHERE path = :path")
    suspend fun clearStat(path: String)

    /** Marks a book broken with a reason so the indexer never re-cracks it while unchanged. */
    @Query("UPDATE books SET unreadable = 1, unreadableReason = :reason WHERE path = :path")
    suspend fun markUnreadable(path: String, reason: String)

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE ASC")
    fun observeAllByTitle(): Flow<List<BookEntity>>

    /**
     * Null authors (never extracted, or the extractor found none) sort after every named author.
     * Within the same author, title is the tie-break — that's how a shelf reads.
     */
    @Query(
        "SELECT * FROM books ORDER BY author IS NULL ASC, author COLLATE NOCASE ASC, " +
            "title COLLATE NOCASE ASC",
    )
    fun observeAllByAuthor(): Flow<List<BookEntity>>

    /**
     * Tie-broken by title: a first import can stamp an entire shelf with the same
     * `addedAtMs` (one directory walk, one `clock()` per file, millisecond resolution), which
     * would otherwise leave the primary "recently added" view in unspecified order exactly on
     * the run where the user first sees it.
     */
    @Query("SELECT * FROM books ORDER BY addedAtMs DESC, title COLLATE NOCASE ASC")
    fun observeAllByRecentlyAdded(): Flow<List<BookEntity>>

    /**
     * Never-opened books (`lastOpenedAtMs IS NULL`) sort last, not first — SQLite's default NULL
     * ordering would otherwise put them at the very top of "recently opened".
     */
    @Query("SELECT * FROM books ORDER BY lastOpenedAtMs IS NULL ASC, lastOpenedAtMs DESC")
    fun observeAllByRecentlyOpened(): Flow<List<BookEntity>>

    /** Dispatches to the per-order query above. Sorting always happens in SQL, not in Kotlin. */
    fun observeAllSorted(order: SortOrder): Flow<List<BookEntity>> = when (order) {
        SortOrder.TITLE -> observeAllByTitle()
        SortOrder.AUTHOR -> observeAllByAuthor()
        SortOrder.RECENTLY_ADDED -> observeAllByRecentlyAdded()
        SortOrder.RECENTLY_OPENED -> observeAllByRecentlyOpened()
    }
}
