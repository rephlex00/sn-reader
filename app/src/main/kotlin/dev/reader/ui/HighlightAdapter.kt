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

/** A ready-to-render highlight row: identity + jump target + the two display lines. */
data class HighlightRow(
    val id: Long,
    val spineIndex: Int,
    val startOffset: Int,
    val excerpt: String,
    val meta: String,
)

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
            meta = "$chapter · $percent%",
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
) : RecyclerView.Adapter<HighlightAdapter.HighlightViewHolder>() {

    private var rows: List<HighlightRow> = emptyList()

    /** Replaces the whole list. Plain [notifyDataSetChanged] — the list is small and fully rebuilt. */
    fun submit(newRows: List<HighlightRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HighlightViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_highlight, parent, false)
        return HighlightViewHolder(view)
    }

    override fun onBindViewHolder(holder: HighlightViewHolder, position: Int) {
        holder.bind(rows[position], onJump, onDelete)
    }

    class HighlightViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val excerpt: TextView = view.findViewById(R.id.highlight_excerpt)
        private val meta: TextView = view.findViewById(R.id.highlight_meta)
        private val body: View = view.findViewById(R.id.highlight_body)
        private val delete: TextView = view.findViewById(R.id.highlight_delete)

        fun bind(row: HighlightRow, onJump: (HighlightRow) -> Unit, onDelete: (HighlightRow) -> Unit) {
            excerpt.text = row.excerpt
            meta.text = row.meta
            body.setOnClickListener { onJump(row) }
            delete.setOnClickListener { onDelete(row) }
        }
    }
}
