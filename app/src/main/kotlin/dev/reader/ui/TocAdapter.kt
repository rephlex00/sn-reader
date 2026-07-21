package dev.reader.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R

/**
 * The overlay's table-of-contents list — a dumb, monochrome RecyclerView adapter over already-built
 * [TocRow]s (see [tocRows]): one row per entry, set as a printed contents page (title · leader dots
 * · whole-book percentage), indented by [TocRow.depth], the current chapter bolded, tapping one
 * invokes [onEntryClick].
 *
 * It holds no async work, no cache and no timer — beyond the one-time Literata resolve below — and
 * the whole list is submitted at once from the already-parsed `doc.toc`, so it costs nothing at
 * rest — the TOC panel is GONE and this adapter's rows are only ever touched on a deliberate tap.
 * [ReaderActivity] nulls the RecyclerView's `itemAnimator`, so [submit]'s rebind is one e-ink redraw
 * rather than an animated shuffle.
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
        return TocViewHolder(view, bookTypeface(parent.context))
    }

    override fun onBindViewHolder(holder: TocViewHolder, position: Int) {
        holder.bind(rows[position], onEntryClick)
    }

    /**
     * Literata, resolved once for the whole adapter and reused by every row.
     *
     * Never per-bind: `ResourcesCompat.getFont` is real file work, and a contents list rebinds every
     * row on each open. The Aa sheet defers its font loading for the same reason
     * (`SettingsSheet.loadFontPreviewsOnce`).
     */
    private var cachedTypeface: Typeface? = null

    private fun bookTypeface(context: Context): Typeface? {
        cachedTypeface?.let { return it }
        val resolved = ResourcesCompat.getFont(context, R.font.literata)
        cachedTypeface = resolved
        return resolved
    }

    class TocViewHolder(
        itemView: View,
        private val bookFace: Typeface?,
    ) : RecyclerView.ViewHolder(itemView) {

        private val title: TextView = itemView.findViewById(R.id.toc_title)
        private val percent: TextView = itemView.findViewById(R.id.toc_percent)

        fun bind(row: TocRow, onEntryClick: (TocRow) -> Unit) {
            title.text = row.title
            percent.text = itemView.context.getString(R.string.toc_percent, row.progressPercent)

            // A printed contents page sets its entries in the book's own face, not the UI's.
            title.typeface = bookFace
            percent.typeface = bookFace

            // Nesting is shown as extra left padding, one step per depth level, keeping the base
            // padding so a top-level entry still clears the edge. A flat indent, no tree glyphs.
            val dm = itemView.resources.displayMetrics
            val base = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, BASE_PADDING_DP, dm).toInt()
            val step = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, INDENT_STEP_DP, dm).toInt()
            itemView.setPadding(base + row.depth * step, itemView.paddingTop, base, itemView.paddingBottom)

            // Two markers, both typeface-based rather than background-based, because a background
            // selection state would need to animate or ghost: the current chapter is bold, and a
            // nested entry is italic the way a sub-section is set in a printed contents page.
            //
            // Resolved explicitly through Typeface.create rather than the two-arg
            // TextView.setTypeface(tf, style): that overload skips Typeface.create entirely when
            // style is NORMAL and just assigns the family typeface as-is, so a recycled row's title
            // would carry whatever style int the base Literata typeface reports rather than the
            // real normal cut.
            val style = when {
                row.isCurrent -> Typeface.BOLD
                row.depth > 0 -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            title.typeface = Typeface.create(bookFace, style)

            itemView.setOnClickListener { onEntryClick(row) }
        }
    }

    private companion object {
        const val BASE_PADDING_DP = 16f
        const val INDENT_STEP_DP = 20f
    }
}
