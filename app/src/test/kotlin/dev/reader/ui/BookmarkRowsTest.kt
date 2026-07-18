package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.data.BookmarkEntity
import dev.reader.engine.Page
import dev.reader.engine.TocEntry
import org.junit.Test

class BookmarkRowsTest {

    private val toc = listOf(
        TocEntry(title = "1. The Madness Years", spineIndex = 4, charOffset = 0),
        TocEntry(title = "The Frontiers of Science", spineIndex = 8, charOffset = 0),
    )

    private fun mark(id: Long, spine: Int, off: Int, frac: Float) =
        BookmarkEntity(id = id, bookPath = "/a.epub", spineIndex = spine, charOffset = off, progressFraction = frac, createdAtMs = 1)

    @Test
    fun `a row labels the chapter and rounded whole-book percent`() {
        val rows = bookmarkRows(listOf(mark(7, spine = 8, off = 120, frac = 0.372f)), toc)
        assertThat(rows).hasSize(1)
        assertThat(rows[0].label).isEqualTo("The Frontiers of Science · 37%")
        assertThat(rows[0].id).isEqualTo(7)
        assertThat(rows[0].spineIndex).isEqualTo(8)
        assertThat(rows[0].charOffset).isEqualTo(120)
    }

    @Test
    fun `a bookmark with no resolvable chapter shows a neutral placeholder`() {
        val rows = bookmarkRows(listOf(mark(1, spine = 0, off = 0, frac = 0f)), toc)
        assertThat(rows[0].label).isEqualTo("Bookmark · 0%")
    }

    @Test
    fun `currentPageBookmark matches a bookmark whose offset falls on the page`() {
        val page = Page(index = 1, startLine = 5, endLine = 9, startOffset = 100, endOffset = 200, topPx = 0)
        val onPage = mark(1, spine = 3, off = 150, frac = 0.2f)
        val otherPage = mark(2, spine = 3, off = 400, frac = 0.3f)
        val otherChapter = mark(3, spine = 4, off = 150, frac = 0.4f)
        val found = currentPageBookmark(listOf(otherPage, onPage, otherChapter), spineIndex = 3, page = page)
        assertThat(found?.id).isEqualTo(1)
    }

    @Test
    fun `currentPageBookmark is null when nothing on the page is bookmarked`() {
        val page = Page(index = 1, startLine = 5, endLine = 9, startOffset = 100, endOffset = 200, topPx = 0)
        assertThat(currentPageBookmark(listOf(mark(2, 3, 400, 0.3f)), spineIndex = 3, page = page)).isNull()
    }

    @Test
    fun `currentPageBookmark ignores a same-offset bookmark in a different chapter`() {
        // The ONLY bookmark whose offset falls on the page range is in a different chapter (spine 4,
        // not the current spine 3). Detection must reject it — this pins the spine-index guard, which
        // a check on offset alone would drop silently.
        val page = Page(index = 1, startLine = 5, endLine = 9, startOffset = 100, endOffset = 200, topPx = 0)
        val otherChapterSameOffset = mark(9, spine = 4, off = 150, frac = 0.4f)
        assertThat(currentPageBookmark(listOf(otherChapterSameOffset), spineIndex = 3, page = page)).isNull()
    }
}
