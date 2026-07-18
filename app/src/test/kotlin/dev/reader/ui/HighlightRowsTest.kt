package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.data.HighlightEntity
import dev.reader.engine.TocEntry
import org.junit.Test

class HighlightRowsTest {

    private val toc = listOf(
        TocEntry(title = "1. The Madness Years", spineIndex = 4, charOffset = 0),
        TocEntry(title = "The Frontiers of Science", spineIndex = 8, charOffset = 0),
    )

    private fun hl(id: Long, spine: Int, start: Int, text: String, frac: Float) = HighlightEntity(
        id = id, bookPath = "/a.epub", spineIndex = spine, startOffset = start, endOffset = start + text.length,
        text = text, progressFraction = frac, createdAtMs = 1,
    )

    @Test
    fun `a row carries the excerpt and a chapter-plus-percent meta line`() {
        val rows = highlightRows(listOf(hl(7, spine = 8, start = 120, text = "a memorable line", frac = 0.372f)), toc)
        assertThat(rows).hasSize(1)
        assertThat(rows[0].id).isEqualTo(7)
        assertThat(rows[0].spineIndex).isEqualTo(8)
        assertThat(rows[0].startOffset).isEqualTo(120)
        assertThat(rows[0].excerpt).isEqualTo("a memorable line")
        assertThat(rows[0].meta).isEqualTo("The Frontiers of Science · 37%")
    }

    @Test
    fun `a highlight with no resolvable chapter shows a neutral placeholder`() {
        val rows = highlightRows(listOf(hl(1, spine = 0, start = 0, text = "hi", frac = 0f)), toc)
        assertThat(rows[0].meta).isEqualTo("Highlight · 0%")
    }
}
