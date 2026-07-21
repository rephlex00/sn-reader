package dev.reader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R
import dev.reader.data.BookmarkEntity
import dev.reader.engine.Page
import dev.reader.engine.TocEntry
import dev.reader.engine.chapterTitleFor
import dev.reader.engine.pageContainsOffset
import kotlin.math.roundToInt

/** A ready-to-render bookmark row: identity + jump target + the display label. */
data class BookmarkRow(val id: Long, val spineIndex: Int, val charOffset: Int, val label: String)

/**
 * Builds display rows from a book's [bookmarks] (already ordered by the DAO) and its live [toc].
 * Each label is "chapter · N%" — the chapter resolved from the TOC ([chapterTitleFor]) and the
 * percentage from the fraction stored at save time. A bookmark whose spine index has no TOC entry
 * at or before it (a degenerate book) shows "Bookmark" rather than a blank chapter. Pure.
 */
fun bookmarkRows(bookmarks: List<BookmarkEntity>, toc: List<TocEntry>): List<BookmarkRow> =
    bookmarks.map { b ->
        val chapter = chapterTitleFor(toc, b.spineIndex) ?: "Bookmark"
        val percent = (b.progressFraction.coerceIn(0f, 1f) * 100).roundToInt()
        BookmarkRow(id = b.id, spineIndex = b.spineIndex, charOffset = b.charOffset, label = "$chapter · $percent%")
    }

/**
 * The bookmark (if any) that falls on the current page: same chapter, and its offset within the
 * page's half-open range ([pageContainsOffset]). Range-based, not exact-offset, so the "this page is
 * bookmarked" state stays correct after a re-pagination moved the page's boundaries. Pure.
 */
fun currentPageBookmark(bookmarks: List<BookmarkEntity>, spineIndex: Int, page: Page): BookmarkEntity? =
    bookmarks.firstOrNull { it.spineIndex == spineIndex && pageContainsOffset(page, it.charOffset) }

/**
 * The overlay's bookmarks list — a monochrome RecyclerView adapter over [BookmarkRow]s. Each row is
 * a label (tap to jump) and a trailing ✕ (tap to delete). No async work, cache, or timer: the whole
 * list is submitted at once on panel-open, so it costs nothing at rest. [ReaderActivity] nulls the
 * RecyclerView's itemAnimator, so [submit]'s rebind is one e-ink redraw.
 */
class BookmarkAdapter(
    private val onJump: (BookmarkRow) -> Unit,
    private val onDelete: (BookmarkRow) -> Unit,
) : RecyclerView.Adapter<BookmarkAdapter.BookmarkViewHolder>() {

    private var rows: List<BookmarkRow> = emptyList()

    /** Replaces the whole list. Plain [notifyDataSetChanged] — the list is small and fully rebuilt. */
    fun submit(newRows: List<BookmarkRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
        return BookmarkViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(rows[position], onJump, onDelete)
    }

    class BookmarkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.bookmark_label)
        private val delete: ImageView = view.findViewById(R.id.bookmark_delete)

        fun bind(row: BookmarkRow, onJump: (BookmarkRow) -> Unit, onDelete: (BookmarkRow) -> Unit) {
            label.text = row.label
            label.setOnClickListener { onJump(row) }
            delete.setOnClickListener { onDelete(row) }
        }
    }
}
