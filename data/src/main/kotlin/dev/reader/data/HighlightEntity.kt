package dev.reader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One text-range highlight. Belongs to a book (`bookPath` → [BookEntity.path], `CASCADE` so removing
 * a book drops its highlights). [startOffset]/[endOffset] are character offsets into the chapter's
 * source text ([endOffset] exclusive, `startOffset < endOffset`), so they survive re-pagination.
 * [text] is the selected string, captured at save time (powers the panel excerpt offline and seeds
 * the deferred Digest/export). [progressFraction] is the whole-book fraction of [startOffset],
 * captured at save time (the value the progress bar uses), so the panel shows "%" with no recompute.
 * The chapter title is NOT stored — resolved from the live TOC at display time.
 */
@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["path"],
            childColumns = ["bookPath"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookPath")],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookPath: String,
    val spineIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val progressFraction: Float,
    val createdAtMs: Long,
)
