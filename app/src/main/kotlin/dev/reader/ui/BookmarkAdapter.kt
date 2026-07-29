package dev.reader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R
import dev.reader.data.BookmarkEntity
import dev.reader.engine.Page
import dev.reader.engine.TocEntry
import dev.reader.engine.chapterTitleFor
import dev.reader.engine.pageContainsOffset
import kotlin.math.roundToInt

/**
 * A ready-to-render mark row: identity, jump target, and the two things the row shows.
 *
 * [chapter] and [figure] are separate rather than one pre-joined "chapter · N%" label, because the
 * row sets them in different voices and different columns — the name at 20sp INK on the left, the
 * figure at 15sp tabular SLATE hard against the right margin. A single string could not be split
 * across a weighted layout without the row guessing where the "·" was.
 */
data class BookmarkRow(
    val id: Long,
    val spineIndex: Int,
    val charOffset: Int,
    val chapter: String,
    val figure: String,
    /** The page's opening words, captured at save time — null for comic marks and pre-excerpt
     *  marks, whose rows show the chapter line alone. */
    val excerpt: String? = null,
) {
    /** The two parts joined, for anything that wants one string (accessibility, tests). */
    val label: String get() = "$chapter · $figure"
}

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
        BookmarkRow(
            id = b.id,
            spineIndex = b.spineIndex,
            charOffset = b.charOffset,
            chapter = chapter,
            figure = "$percent%",
            excerpt = b.excerpt?.takeIf { it.isNotBlank() },
        )
    }

/**
 * The bookmark (if any) that falls on the current page: same chapter, and its offset within the
 * page's half-open range ([pageContainsOffset]). Range-based, not exact-offset, so the "this page is
 * bookmarked" state stays correct after a re-pagination moved the page's boundaries. Pure.
 */
fun currentPageBookmark(bookmarks: List<BookmarkEntity>, spineIndex: Int, page: Page): BookmarkEntity? =
    bookmarks.firstOrNull { it.spineIndex == spineIndex && pageContainsOffset(page, it.charOffset) }

/**
 * The marks list — a monochrome RecyclerView adapter over [BookmarkRow]s. A row is a chapter and a
 * figure: tap to jump, press and hold to remove.
 *
 * The trailing ✕ is gone. One on every row put a column of pictograms on a surface built to avoid
 * them, and it sat a finger's width from the panel's own dismiss, so a mis-tap destroyed a mark with
 * no confirmation and no undo. A long press cannot be mis-tapped, and the "Remove" sidehead at the
 * foot of the list explains it once rather than forty times.
 *
 * The "Remove" explainer rides as the list's own FOOTER (second view type, the HighlightAdapter
 * precedent) rather than a fixed block at the panel's foot — fixed, it sat pinned to the bezel with
 * the unused list height as a void above it. Present only when there is at least one mark.
 *
 * No async work, cache, or timer: the whole
 * list is submitted at once on panel-open, so it costs nothing at rest. [ReaderActivity] nulls the
 * RecyclerView's itemAnimator, so [submit]'s rebind is one e-ink redraw.
 */
class BookmarkAdapter(
    private val onJump: (BookmarkRow) -> Unit,
    private val onDelete: (BookmarkRow) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<BookmarkRow> = emptyList()

    /** Replaces the whole list. Plain [notifyDataSetChanged] — the list is small and fully rebuilt. */
    fun submit(newRows: List<BookmarkRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (rows.isEmpty()) 0 else rows.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position == rows.size) VIEW_TYPE_FOOTER else VIEW_TYPE_MARK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_FOOTER) {
            FooterViewHolder(inflater.inflate(R.layout.item_marks_footer, parent, false))
        } else {
            BookmarkViewHolder(inflater.inflate(R.layout.item_bookmark, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is BookmarkViewHolder) holder.bind(rows[position], onJump, onDelete)
    }

    class BookmarkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.bookmark_label)
        private val percent: TextView = view.findViewById(R.id.bookmark_percent)
        private val excerpt: TextView = view.findViewById(R.id.bookmark_excerpt)
        private val oneLineMinHeight = view.resources.getDimensionPixelSize(R.dimen.row_height)
        private val twoLineMinHeight = view.resources.getDimensionPixelSize(R.dimen.row_height_two_line)

        fun bind(row: BookmarkRow, onJump: (BookmarkRow) -> Unit, onDelete: (BookmarkRow) -> Unit) {
            label.text = row.chapter
            percent.text = row.figure
            excerpt.text = row.excerpt.orEmpty()
            excerpt.visibility = if (row.excerpt != null) View.VISIBLE else View.GONE
            // The design system's two heights (dimens.xml): a bare row and "a mark with its
            // opening words". Set per bind — recycled holders swap between the two shapes.
            itemView.minimumHeight = if (row.excerpt != null) twoLineMinHeight else oneLineMinHeight
            itemView.setOnClickListener { onJump(row) }
            itemView.setOnLongClickListener {
                onDelete(row)
                true
            }
        }
    }

    /** The removal explainer. Static but for its sidehead label, which only code can set. */
    class FooterViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        init {
            view.findViewById<SideheadView>(R.id.marks_footer_sidehead).apply {
                label = view.context.getString(R.string.remove_sidehead)
                form = SideheadView.Form.RULED
            }
        }
    }

    private companion object {
        const val VIEW_TYPE_MARK = 0
        const val VIEW_TYPE_FOOTER = 1
    }
}
