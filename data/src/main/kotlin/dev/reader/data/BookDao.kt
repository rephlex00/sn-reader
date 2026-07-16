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
     * Written when the reader opens a book (setting [lastOpenedAtMs] to now) and on every
     * debounced page turn thereafter — never touches any other column.
     */
    @Query(
        "UPDATE books SET spineIndex = :spineIndex, charOffset = :charOffset, " +
            "lastOpenedAtMs = :lastOpenedAtMs WHERE path = :path",
    )
    suspend fun updatePosition(path: String, spineIndex: Int, charOffset: Int, lastOpenedAtMs: Long)

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
