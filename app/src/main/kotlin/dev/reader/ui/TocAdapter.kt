package dev.reader.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R

/**
 * The overlay's table-of-contents list — a dumb, monochrome RecyclerView adapter over already-built
 * [TocRow]s (see [tocRows]): one TextView per entry, indented by [TocRow.depth], the current chapter
 * bolded, tapping one invokes [onEntryClick].
 *
 * It holds no async work, no cache and no timer: the whole list is submitted at once from the
 * already-parsed `doc.toc`, so it costs nothing at rest — the TOC panel is GONE and this adapter's
 * rows are only ever touched on a deliberate tap. [ReaderActivity] nulls the RecyclerView's
 * `itemAnimator`, so [submit]'s rebind is one e-ink redraw rather than an animated shuffle.
 */
internal class TocAdapter(
    private val onEntryClick: (TocRow) -> Unit,
) : RecyclerView.Adapter<TocAdapter.TocViewHolder>() {

    private var rows: List<TocRow> = emptyList()

    /**
     * Replaces the whole list. A plain [notifyDataSetChanged], not DiffUtil: a book's TOC is static,
     * and the only thing that moves between openings is which row is the current-chapter marker, so
     * the minimal-diff machinery would earn nothing here.
     */
    fun submit(newRows: List<TocRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TocViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_toc_entry, parent, false)
        return TocViewHolder(view as TextView)
    }

    override fun onBindViewHolder(holder: TocViewHolder, position: Int) {
        holder.bind(rows[position], onEntryClick)
    }

    class TocViewHolder(private val label: TextView) : RecyclerView.ViewHolder(label) {
        fun bind(row: TocRow, onEntryClick: (TocRow) -> Unit) {
            label.text = row.title

            // Nesting is shown as extra left padding, one step per depth level, keeping the base
            // padding so a top-level entry still clears the edge. A flat indent, no tree glyphs.
            val dm = label.resources.displayMetrics
            val base = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, BASE_PADDING_DP, dm).toInt()
            val step = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, INDENT_STEP_DP, dm).toInt()
            label.setPadding(base + row.depth * step, label.paddingTop, base, label.paddingBottom)

            // The current chapter is the single marker: bold. No background/selection state to
            // animate — the e-ink constraint, the same choice the Aa sheet makes for its options.
            label.setTypeface(null, if (row.isCurrent) Typeface.BOLD else Typeface.NORMAL)

            label.setOnClickListener { onEntryClick(row) }
        }
    }

    private companion object {
        const val BASE_PADDING_DP = 16f
        const val INDENT_STEP_DP = 20f
    }
}
