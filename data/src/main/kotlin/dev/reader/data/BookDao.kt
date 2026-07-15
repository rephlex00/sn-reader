package dev.reader.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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
     * to detect new, changed, and vanished files without opening any of them.
     */
    @Query("SELECT path, sizeBytes, modifiedAtMs FROM books")
    suspend fun getAllStats(): List<BookStat>

    /** Written on page turn (debounced by the caller) — never touches any other column. */
    @Query("UPDATE books SET spineIndex = :spineIndex, charOffset = :charOffset WHERE path = :path")
    suspend fun updatePosition(path: String, spineIndex: Int, charOffset: Int)

    /** Marks a book broken with a reason so the indexer never re-cracks it while unchanged. */
    @Query("UPDATE books SET unreadable = 1, unreadableReason = :reason WHERE path = :path")
    suspend fun markUnreadable(path: String, reason: String)
}
