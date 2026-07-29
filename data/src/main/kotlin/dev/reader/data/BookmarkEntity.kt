package dev.reader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One page-level bookmark. Belongs to a book (`bookPath` → [BookEntity.path], `CASCADE` so removing
 * a book — the indexer's `deleteByPaths`, or a since-deleted file — drops its bookmarks). The
 * position is a [dev.reader.engine.Locator] flattened to `spineIndex`/`charOffset`, and
 * `progressFraction` is the whole-book fraction of the bookmarked page captured at save time (the
 * same value the progress bar and library percentage use), so the list shows "%" with no
 * re-pagination. The chapter title is NOT stored — it is resolved from the live TOC at display time.
 *
 * [excerpt] is the page's opening words, captured at save time exactly as a highlight's text is
 * (the [HighlightEntity.text] precedent): the saving code is already standing on the page, where
 * the text costs nothing, while deriving it at display time would paginate a chapter per row —
 * the eager-work pattern the panels forbid. Null for comic marks (a comic has no text) and for
 * marks made before this column existed; the row then shows its chapter line alone.
 */
@Entity(
    tableName = "bookmarks",
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
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookPath: String,
    val spineIndex: Int,
    val charOffset: Int,
    val progressFraction: Float,
    val createdAtMs: Long,
    val excerpt: String? = null,
)
