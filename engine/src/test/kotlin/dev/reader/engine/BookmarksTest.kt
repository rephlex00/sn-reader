package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookmarksTest {

    private val toc = listOf(
        TocEntry(title = "Part I", spineIndex = 3, charOffset = 0),
        TocEntry(title = "1. The Madness Years", spineIndex = 4, charOffset = 0),
        TocEntry(title = "2. Silent Spring", spineIndex = 5, charOffset = 0),
    )

    @Test
    fun `chapterTitleFor returns the entry at the exact spine index`() {
        assertThat(chapterTitleFor(toc, spineIndex = 5)).isEqualTo("2. Silent Spring")
    }

    @Test
    fun `chapterTitleFor falls back to the nearest entry before the index`() {
        // A spine item (e.g. a section with no TOC entry of its own) resolves to the chapter it sits in.
        assertThat(chapterTitleFor(toc, spineIndex = 6)).isEqualTo("2. Silent Spring")
    }

    @Test
    fun `chapterTitleFor is null when nothing is at or before the index`() {
        assertThat(chapterTitleFor(toc, spineIndex = 0)).isNull()
        assertThat(chapterTitleFor(emptyList(), spineIndex = 5)).isNull()
    }

    @Test
    fun `pageContainsOffset is a half-open range on the page's offsets`() {
        val page = Page(index = 2, startLine = 10, endLine = 20, startOffset = 100, endOffset = 250, topPx = 0)
        assertThat(pageContainsOffset(page, 100)).isTrue()  // start is inclusive
        assertThat(pageContainsOffset(page, 249)).isTrue()  // inside
        assertThat(pageContainsOffset(page, 250)).isFalse() // end is exclusive (next page's start)
        assertThat(pageContainsOffset(page, 99)).isFalse()  // before
    }
}
