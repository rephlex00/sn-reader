package dev.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface HighlightDao {
    /** Inserts a highlight, returning its generated row id. */
    @Insert
    suspend fun insert(highlight: HighlightEntity): Long

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Atomically replaces a set of highlights with one merged highlight: deletes [removedIds] and inserts
     * [merged] in a single transaction, so an overlap-merge can never leave the subsumed highlights
     * deleted with no replacement (a failed insert rolls the deletes back). Returns the merged row's id.
     */
    @Transaction
    suspend fun replaceWithMerged(removedIds: List<Long>, merged: HighlightEntity): Long {
        removedIds.forEach { deleteById(it) }
        return insert(merged)
    }

    /**
     * One chapter's highlights, ordered by start offset — the one-shot read the reader does when a
     * chapter is shown, to draw the washes. No `Flow`: a standing observer would be steady-state work
     * against the idle promise; the reader re-reads on each chapter change and after each edit.
     */
    @Query("SELECT * FROM highlights WHERE bookPath = :bookPath AND spineIndex = :spineIndex ORDER BY startOffset ASC")
    suspend fun highlightsForChapter(bookPath: String, spineIndex: Int): List<HighlightEntity>

    /** A whole book's highlights in reading order — the one-shot read the Highlights panel does on open. */
    @Query("SELECT * FROM highlights WHERE bookPath = :bookPath ORDER BY spineIndex ASC, startOffset ASC")
    suspend fun highlightsForBook(bookPath: String): List<HighlightEntity>
}
