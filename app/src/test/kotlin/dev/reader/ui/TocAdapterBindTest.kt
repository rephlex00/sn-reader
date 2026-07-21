package dev.reader.ui

import android.graphics.Typeface
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
    fun `a deeper row is indented further than a top level row`() {
        val top = holderFor(row(depth = 0)).itemView.paddingLeft
        val nested = holderFor(row(depth = 2)).itemView.paddingLeft

        assertThat(nested).isGreaterThan(top)
    }
}
