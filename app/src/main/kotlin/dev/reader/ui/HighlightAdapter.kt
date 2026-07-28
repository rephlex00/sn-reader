package dev.reader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R
import dev.reader.data.HighlightEntity
import dev.reader.engine.TocEntry
import dev.reader.engine.chapterTitleFor
import dev.reader.engine.highlightExcerpt
import kotlin.math.roundToInt

/** A ready-to-render note row: identity, jump target, the passage, and its whole-book figure. */
data class HighlightRow(
    val id: Long,
    val spineIndex: Int,
    val startOffset: Int,
    val excerpt: String,
    val chapter: String,
    val figure: String,
) {
    /** The chapter and figure joined, for anything that wants one string (tests, accessibility). */
    val meta: String get() = "$chapter · $figure"
}

/**
 * What the notes list actually renders: the passages, with a sidehead naming the chapter above each
 * group of them.
 *
 * The chapter used to be repeated on every single row. Grouping says it once — which is what a
 * printed index does, and what frees the right-hand column to carry the figure alone.
 */
sealed interface HighlightItem {
    data class Head(val chapter: String) : HighlightItem
    data class Note(val row: HighlightRow) : HighlightItem
}

/**
 * Inserts a [HighlightItem.Head] wherever the chapter changes.
 *
 * Runs off the DAO's own ordering rather than re-sorting: the rows already arrive in reading order,
 * so a chapter's notes are always contiguous and a change of chapter is always a new section. Pure.
 */
fun highlightItems(rows: List<HighlightRow>): List<HighlightItem> {
    val items = mutableListOf<HighlightItem>()
    var lastChapter: String? = null
    for (row in rows) {
        if (row.chapter != lastChapter) {
            items += HighlightItem.Head(row.chapter)
            lastChapter = row.chapter
        }
        items += HighlightItem.Note(row)
    }
    return items
}

/**
 * Builds display rows from a book's [highlights] (already ordered by the DAO) and its live [toc].
 * Each row is an excerpt line ([highlightExcerpt]) plus "chapter · N%" — chapter from the TOC
 * ([chapterTitleFor], falling back to "Highlight"), percentage from the fraction stored at save time.
 * Pure.
 */
fun highlightRows(highlights: List<HighlightEntity>, toc: List<TocEntry>): List<HighlightRow> =
    highlights.map { h ->
        val chapter = chapterTitleFor(toc, h.spineIndex) ?: "Highlight"
        val percent = (h.progressFraction.coerceIn(0f, 1f) * 100).roundToInt()
        HighlightRow(
            id = h.id,
            spineIndex = h.spineIndex,
            startOffset = h.startOffset,
            excerpt = highlightExcerpt(h.text),
            chapter = chapter,
            figure = "$percent%",
        )
    }

/**
 * The overlay's highlights list — a monochrome RecyclerView adapter over [HighlightRow]s. Each row is
 * a two-line label (excerpt + meta; tap to jump) and a trailing ✕ (tap to delete). No async work,
 * cache, or timer: the whole list is submitted at once on panel-open, so it costs nothing at rest.
 * [ReaderActivity] nulls the RecyclerView's itemAnimator, so [submit]'s rebind is one e-ink redraw.
 */
class HighlightAdapter(
    private val onJump: (HighlightRow) -> Unit,
    private val onDelete: (HighlightRow) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<HighlightItem> = emptyList()

    /** Replaces the whole list, grouping it under chapter sideheads on the way in. */
    fun submit(newRows: List<HighlightRow>) {
        items = highlightItems(newRows)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HighlightItem.Head -> VIEW_TYPE_HEAD
        is HighlightItem.Note -> VIEW_TYPE_NOTE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEAD) {
            HeadViewHolder(inflater.inflate(R.layout.item_sidehead, parent, false))
        } else {
            HighlightViewHolder(inflater.inflate(R.layout.item_highlight, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HighlightItem.Head -> (holder as HeadViewHolder).bind(item)
            is HighlightItem.Note -> (holder as HighlightViewHolder).bind(item.row, onJump, onDelete)
        }
    }

    class HeadViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val sidehead = view as SideheadView

        fun bind(item: HighlightItem.Head) {
            sidehead.label = item.chapter
            sidehead.form = SideheadView.Form.RULED
        }
    }

    class HighlightViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val excerpt: TextView = view.findViewById(R.id.highlight_excerpt)
        private val meta: TextView = view.findViewById(R.id.highlight_meta)

        fun bind(row: HighlightRow, onJump: (HighlightRow) -> Unit, onDelete: (HighlightRow) -> Unit) {
            excerpt.text = row.excerpt
            // The figure alone: the chapter is already stated by the sidehead above this group.
            meta.text = row.figure
            itemView.setOnClickListener { onJump(row) }
            // Removing a note is a tap on the page, where you can see what you are removing — this
            // is the fallback for a note whose page you are not on.
            itemView.setOnLongClickListener {
                onDelete(row)
                true
            }
        }
    }

    private companion object {
        const val VIEW_TYPE_HEAD = 0
        const val VIEW_TYPE_NOTE = 1
    }
}
