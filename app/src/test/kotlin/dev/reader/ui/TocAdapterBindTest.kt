package dev.reader.ui

import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import dev.reader.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TocAdapterBindTest {

    private fun holderFor(row: TocRow, onClick: (TocRow) -> Unit = {}): TocAdapter.TocViewHolder {
        val context = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(context)
        val adapter = TocAdapter(onClick)
        adapter.submit(listOf(row))
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        return holder
    }

    private fun row(
        title: String = "Ashes",
        depth: Int = 0,
        isCurrent: Boolean = false,
        progressPercent: Int = 12,
    ) = TocRow(title, spineIndex = 0, charOffset = 0, depth = depth, isCurrent = isCurrent, progressPercent = progressPercent)

    @Test
    fun `binds the title and the percentage`() {
        val holder = holderFor(row(title = "Winter", progressPercent = 44))

        assertThat(holder.itemView.findViewById<TextView>(R.id.toc_title).text.toString()).isEqualTo("Winter")
        assertThat(holder.itemView.findViewById<TextView>(R.id.toc_percent).text.toString()).isEqualTo("44%")
    }

    @Test
    fun `the current chapter is bold and a nested entry is italic`() {
        assertThat(holderFor(row(isCurrent = true)).itemView.findViewById<TextView>(R.id.toc_title).typeface.style)
            .isEqualTo(Typeface.BOLD)
        assertThat(holderFor(row(depth = 1)).itemView.findViewById<TextView>(R.id.toc_title).typeface.style)
            .isEqualTo(Typeface.ITALIC)
        assertThat(holderFor(row()).itemView.findViewById<TextView>(R.id.toc_title).typeface.style)
            .isEqualTo(Typeface.NORMAL)
    }

    @Test
    fun `Literata is resolved once and reused across view holders`() {
        // Regression guard for the claim in TocAdapter's cachedTypeface doc: a change that moved
        // bookTypeface's ResourcesCompat.getFont call into onBindViewHolder would leave every
        // other test in this file green (they only check the RESULT is Literata, not that it is
        // the SAME instance every time) while re-doing real file work on every row bind.
        val context = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(context)
        val adapter = TocAdapter {}

        val first = adapter.onCreateViewHolder(parent, 0)
        val second = adapter.onCreateViewHolder(parent, 0)

        assertThat(second.bookFace).isSameInstanceAs(first.bookFace)
    }

    @Test
    fun `a deeper row is indented further than a top level row`() {
        val top = holderFor(row(depth = 0)).itemView.paddingLeft
        val nested = holderFor(row(depth = 2)).itemView.paddingLeft

        assertThat(nested).isGreaterThan(top)
    }

    // -- Row layout at a realistic width: a long title must not push toc_percent off-screen -----
    //
    // A flat LinearLayout of [title(wrap_content), leader(weight=1), percent(wrap_content)] hands
    // the title its ENTIRE first-measurement-pass budget before a later sibling (percent) has
    // reserved anything, so a long title claims the whole row and percent lands past the right
    // edge. These lay out the REAL inflated row (not just inflate it) and read actual child
    // positions, the way the bug was originally found.

    @Test
    fun `a long title leaves the percentage inside the row and ellipsizes the title`() {
        // Long enough that its full, unwrapped width is many times the row's — verified below
        // against Layout.getDesiredWidth, the same computation TextView's own onMeasure uses, so
        // the "did it actually get constrained" check holds regardless of the exact per-character
        // metrics a font shadow reports.
        val longTitle = "A Chapter Title So Long It Would, Left Unchecked, Swallow The Row's " +
            "Entire Width And Push Everything After It Off The Right Edge. ".repeat(40)
        val holder = holderFor(row(title = longTitle, progressPercent = 87))
        layoutRow(holder.itemView, ROW_WIDTH_PX)

        val itemView = holder.itemView
        val title = itemView.findViewById<TextView>(R.id.toc_title)
        val percent = itemView.findViewById<TextView>(R.id.toc_percent)

        // toc_percent is fully inside the row's content box, not laid out past its right edge.
        assertThat(percent.left).isAtLeast(0)
        assertThat(percent.right).isAtMost(itemView.width - itemView.paddingRight)
        assertThat(percent.text.toString()).isEqualTo("87%")

        // toc_title was constrained down from its full unwrapped width, not left to expand and
        // swallow the row (Layout.getEllipsisCount is unreliable under this Robolectric config —
        // it reports 0 even on a visibly truncated single-line TextView — so this checks the same
        // fact a different way: a wrap_content, maxLines=1 view can only end up narrower than its
        // own full text if something clipped/ellipsized it). It also stays clear of toc_percent's
        // reserved space, which is the whole point.
        val fullWidth = android.text.Layout.getDesiredWidth(longTitle, title.paint)
        assertThat(title.width.toFloat()).isLessThan(fullWidth)
        assertThat(title.layout?.lineCount).isEqualTo(1)
        assertThat(title.right).isLessThan(itemView.width - itemView.paddingRight)
    }

    @Test
    fun `a short title leaves the leader a nonzero run of dots and the percentage stays visible`() {
        val holder = holderFor(row(title = "One", progressPercent = 5))
        layoutRow(holder.itemView, ROW_WIDTH_PX)

        val itemView = holder.itemView
        val leader = itemView.findViewById<View>(R.id.toc_leader)
        val percent = itemView.findViewById<TextView>(R.id.toc_percent)

        assertThat(leader.width).isGreaterThan(0)
        assertThat(percent.left).isAtLeast(0)
        assertThat(percent.right).isAtMost(itemView.width - itemView.paddingRight)
        assertThat(percent.text.toString()).isEqualTo("5%")
    }

    /** Measures and lays out [itemView] at [widthPx], wrap_content height — the same top-down entry
     *  a RecyclerView drives, since a bare `inflate` never runs a measure/layout pass at all. */
    private fun layoutRow(itemView: View, widthPx: Int) {
        itemView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        itemView.layout(0, 0, itemView.measuredWidth, itemView.measuredHeight)
    }

    private companion object {
        /** A realistic row width — the reader's portrait test viewport is 600px wide. */
        const val ROW_WIDTH_PX = 600
    }
}
