package dev.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BookmarkDao {
    /** Inserts a bookmark, returning its generated row id. */
    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * A book's bookmarks in reading order (spine, then offset) — the one-shot read the Bookmarks
     * panel does when it opens. No `Flow`: a standing observer would be steady-state work against
     * the idle promise, and the panel re-reads on each open anyway.
     */
    @Query("SELECT * FROM bookmarks WHERE bookPath = :bookPath ORDER BY spineIndex ASC, charOffset ASC")
    suspend fun bookmarksFor(bookPath: String): List<BookmarkEntity>
}
