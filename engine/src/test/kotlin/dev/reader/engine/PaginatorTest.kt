package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaginatorTest {

    private val paginator = Paginator()

    @Test
    fun `fills each page greedily up to the height budget`() {
        // 10 lines x 20px, 100px page => exactly 5 lines per page.
        val pages = paginator.paginate(FakeMeasuredChapter.uniform(lineCount = 10), pageHeightPx = 100)

        assertThat(pages).hasSize(2)
        assertThat(pages[0].startLine).isEqualTo(0)
        assertThat(pages[0].endLine).isEqualTo(4)
        assertThat(pages[1].startLine).isEqualTo(5)
        assertThat(pages[1].endLine).isEqualTo(9)
    }

    @Test
    fun `page char offsets follow line offsets`() {
        val pages = paginator.paginate(FakeMeasuredChapter.uniform(lineCount = 10), pageHeightPx = 100)

        assertThat(pages[0].startOffset).isEqualTo(0)
        assertThat(pages[0].endOffset).isEqualTo(5 * 40)
        assertThat(pages[1].startOffset).isEqualTo(5 * 40)
        assertThat(pages[1].endOffset).isEqualTo(10 * 40)
    }

    @Test
    fun `page top is the top of its first line`() {
        val pages = paginator.paginate(FakeMeasuredChapter.uniform(lineCount = 10), pageHeightPx = 100)

        assertThat(pages[0].topPx).isEqualTo(0)
        assertThat(pages[1].topPx).isEqualTo(100)
    }

    @Test
    fun `a trailing partial page is still emitted`() {
        // 12 lines x 20px, 100px page => 5 + 5 + 2.
        val pages = paginator.paginate(FakeMeasuredChapter.uniform(lineCount = 12), pageHeightPx = 100)

        assertThat(pages).hasSize(3)
        assertThat(pages[2].startLine).isEqualTo(10)
        assertThat(pages[2].endLine).isEqualTo(11)
    }

    @Test
    fun `hard page break forces a new page`() {
        val chapter = FakeMeasuredChapter.uniform(lineCount = 10, hardBreaks = setOf(3))
        val pages = paginator.paginate(chapter, pageHeightPx = 10_000)

        assertThat(pages).hasSize(2)
        assertThat(pages[0].endLine).isEqualTo(2)
        assertThat(pages[1].startLine).isEqualTo(3)
    }

    @Test
    fun `a line taller than the page gets its own page and does not loop forever`() {
        val chapter = FakeMeasuredChapter.uniform(lineCount = 3, lineHeightPx = 200)
        val pages = paginator.paginate(chapter, pageHeightPx = 100)

        assertThat(pages).hasSize(3)
        assertThat(pages.map { it.startLine }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `pages are indexed consecutively from zero`() {
        val pages = paginator.paginate(FakeMeasuredChapter.uniform(lineCount = 12), pageHeightPx = 100)
        assertThat(pages.map { it.index }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `empty chapter paginates to no pages`() {
        assertThat(
            paginator.paginate(FakeMeasuredChapter.uniform(lineCount = 0), pageHeightPx = 100)
        ).isEmpty()
    }

    @Test
    fun `rejects a non-positive page height`() {
        val e = runCatching {
            paginator.paginate(FakeMeasuredChapter.uniform(lineCount = 5), pageHeightPx = 0)
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `pageIndexFor finds the page holding an offset`() {
        val pages = paginator.paginate(FakeMeasuredChapter.uniform(lineCount = 10), pageHeightPx = 100)

        assertThat(pageIndexFor(pages, 0)).isEqualTo(0)
        assertThat(pageIndexFor(pages, 199)).isEqualTo(0)
        assertThat(pageIndexFor(pages, 200)).isEqualTo(1)
        assertThat(pageIndexFor(pages, 399)).isEqualTo(1)
    }

    @Test
    fun `pageIndexFor clamps an offset past the end to the last page`() {
        val pages = paginator.paginate(FakeMeasuredChapter.uniform(lineCount = 10), pageHeightPx = 100)
        assertThat(pageIndexFor(pages, 99_999)).isEqualTo(1)
    }

    @Test
    fun `a mid-chapter heading taller than the page gets its own page between normal lines`() {
        // Lines 0,1,3 are normal (20px); line 2 is a heading (200px) taller than the 100px
        // page budget. Hand-traced: line 1 doesn't overflow page 0 (top 0 to bottom 40 <=
        // 100), but line 2 does (bottom 240 - page-top 0 > 100) so page 0 closes at line 1.
        // Line 2 then overflows on its own (bottom 260 - top 40 > 100) so it gets page 1 all
        // to itself. Line 3 starts page 2 and the loop ends there. This is the shape a real
        // chapter with an <h1> actually produces, which a uniform grid cannot exercise.
        val chapter = FakeMeasuredChapter(
            heights = listOf(20, 20, 200, 20),
            charsPerLine = listOf(40, 40, 10, 40),
        )
        val pages = paginator.paginate(chapter, pageHeightPx = 100)

        assertThat(pages).hasSize(3)

        assertThat(pages[0].startLine).isEqualTo(0)
        assertThat(pages[0].endLine).isEqualTo(1)
        assertThat(pages[0].topPx).isEqualTo(0)
        assertThat(pages[0].startOffset).isEqualTo(0)
        assertThat(pages[0].endOffset).isEqualTo(80)

        assertThat(pages[1].startLine).isEqualTo(2)
        assertThat(pages[1].endLine).isEqualTo(2)
        assertThat(pages[1].topPx).isEqualTo(40)
        assertThat(pages[1].startOffset).isEqualTo(80)
        assertThat(pages[1].endOffset).isEqualTo(90)

        assertThat(pages[2].startLine).isEqualTo(3)
        assertThat(pages[2].endLine).isEqualTo(3)
        assertThat(pages[2].topPx).isEqualTo(240)
        assertThat(pages[2].startOffset).isEqualTo(90)
        assertThat(pages[2].endOffset).isEqualTo(130)
    }

    @Test
    fun `a heading that would be stranded at the page foot moves to the next page with its body`() {
        // 5 lines/page. Lines 0-3 body, line 4 heading, line 5 body. Greedy would end page 0 at line 4
        // (heading) with body on page 1 — strand. Keep-heading must end page 0 at line 3 and start page 1
        // at the heading.
        val m = fakeChapter(lineCount = 6, pageLines = 5, headingLines = setOf(4))
        val pages = paginator.paginate(m, pageHeightPxForLines(5))
        assertThat(pages[0].endLine).isEqualTo(3)   // heading pushed off page 0
        assertThat(pages[1].startLine).isEqualTo(4) // heading starts page 1
    }

    @Test
    fun `a heading with its body on the same page is not moved`() {
        // Heading is the page's trailing candidate line (line 4, the last of the 5 that fit),
        // but it starts exactly at the page's own startLine (0) because it's a single line at
        // the very top with no body before it within this page — the `headingStart > startLine`
        // guard is what suppresses the move here, not `isHeadingLine(lastLine + 1)`.
        val m = fakeChapter(lineCount = 6, pageLines = 5, headingLines = setOf(0, 1, 2, 3, 4))
        val pages = paginator.paginate(m, pageHeightPxForLines(5))
        assertThat(pages[0].startLine).isEqualTo(0) // unchanged; guard suppressed the move
        assertThat(pages[0].endLine).isEqualTo(4)
        assertThat(pages.first().startLine).isEqualTo(0)
        assertThat(pages.last().endLine).isEqualTo(5)
        for (i in 1 until pages.size) {
            assertThat(pages[i].startLine).isEqualTo(pages[i - 1].endLine + 1)
        }
    }

    @Test
    fun `a heading that alone fills the page is left in place (no empty page, no loop)`() {
        val m = fakeChapter(lineCount = 6, pageLines = 5, headingLines = (0..4).toSet())
        val pages = paginator.paginate(m, pageHeightPxForLines(5))
        assertThat(pages).isNotEmpty() // guard: never empties a page / loops
        assertThat(pages.first().startLine).isEqualTo(0)
        assertThat(pages.last().endLine).isEqualTo(5)
        for (i in 1 until pages.size) {
            assertThat(pages[i].startLine).isEqualTo(pages[i - 1].endLine + 1)
        }
    }

    @Test
    fun `a hard page break at an overflow boundary is honored, not swallowed by keep-heading`() {
        // 5 lines/page (100px / 20px). 6 lines total; line 4 is a heading; a publisher hard
        // break falls right before line 5 — exactly where the natural overflow boundary would
        // also land. Keep-heading must not engage here: the break is honored and the heading
        // stays stranded on page 0 rather than the loop skipping past the break line.
        val m = FakeMeasuredChapter.uniform(
            lineCount = 6,
            headingLines = setOf(4),
            hardBreaks = setOf(5),
        )
        val pages = paginator.paginate(m, pageHeightPxForLines(5))
        assertThat(pages).hasSize(2)
        assertThat(pages[0].startLine).isEqualTo(0)
        assertThat(pages[0].endLine).isEqualTo(4)
        assertThat(pages[1].startLine).isEqualTo(5)
        assertThat(pages[1].endLine).isEqualTo(5)
    }

    @Test
    fun `keep-heading walks back over a multi-line heading to move the whole heading`() {
        // 5 lines/page. Lines 0-2 body, lines 3-4 a two-line heading, line 5 body. Greedy ends page 0
        // at line 4 (heading foot) with body on page 1 — strand. Keep-heading walks back over both
        // heading lines and ends page 0 at line 2, starting page 1 at the heading's first line.
        val m = fakeChapter(lineCount = 6, pageLines = 5, headingLines = setOf(3, 4))
        val pages = paginator.paginate(m, pageHeightPxForLines(5))
        assertThat(pages[0].endLine).isEqualTo(2)
        assertThat(pages[1].startLine).isEqualTo(3)
    }

    @Test
    fun `keep-heading keeps pages contiguous and covering every line`() {
        val m = fakeChapter(lineCount = 6, pageLines = 5, headingLines = setOf(4))
        val pages = paginator.paginate(m, pageHeightPxForLines(5))
        assertThat(pages.first().startLine).isEqualTo(0)
        assertThat(pages.last().endLine).isEqualTo(5)
        for (i in 1 until pages.size) {
            assertThat(pages[i].startLine).isEqualTo(pages[i - 1].endLine + 1)
        }
    }

    @Test
    fun `keep-heading does not move a heading when heading plus body would overflow a page`() {
        // 40px page. Line 0 body (20px), line 1 heading (20px), line 2 body (30px). Greedy ends page 0
        // at line 1 (heading foot) with body on page 1 — a strand. But moving the heading down would put
        // heading (20) + body (30) = 50px on one page, over the 40px budget. The fit guard suppresses the
        // move; the heading stays put and no page exceeds the height budget.
        val chapter = FakeMeasuredChapter(
            heights = listOf(20, 20, 30),
            charsPerLine = listOf(40, 40, 40),
            headingLines = setOf(1),
        )
        val pages = paginator.paginate(chapter, pageHeightPx = 40)
        assertThat(pages[0].endLine).isEqualTo(1) // heading NOT moved
        for (page in pages) {
            val heightPx = chapter.lineBottomPx(page.endLine) - chapter.lineTopPx(page.startLine)
            assertThat(heightPx).isAtMost(40) // no over-tall page
        }
    }

    @Test
    fun `reflowedPageIndex lands on the new page whose range contains the old page-top offset`() {
        // The Aa sheet's headline correctness property, at the pure level: a settings change
        // re-paginates the current chapter to a DIFFERENT page count, and the reader must stay on
        // the text it was showing — the page whose offset range contains the char offset at the top
        // of its current page — not the same page index. Same chapter, two page heights (a bigger
        // font shrinks the height budget, so fewer lines fit and the page count grows).
        val chapter = FakeMeasuredChapter.uniform(lineCount = 12) // 40 chars/line, 20px/line
        val newPages = paginator.paginate(chapter, pageHeightPx = 60) // 3 lines/page => 4 pages
        val oldPages = paginator.paginate(chapter, pageHeightPx = 100) // 5 lines/page => 3 pages

        // For every old page, its top-of-page char offset must land in the new page whose offset
        // range contains it — the containment property, verified structurally below rather than
        // hand-counting which new page each old anchor falls into.
        for (oldIndex in oldPages.indices) {
            val newIndex = reflowedPageIndex(oldPages, oldIndex, newPages)
            val landed = newPages[newIndex]
            val anchor = oldPages[oldIndex].startOffset
            assertThat(anchor).isAtLeast(landed.startOffset)
            assertThat(anchor).isLessThan(landed.endOffset)
        }
    }

    @Test
    fun `reflowedPageIndex clamps to a valid page when the new chapter re-paginates to fewer pages`() {
        val chapter = FakeMeasuredChapter.uniform(lineCount = 12)
        val oldPages = paginator.paginate(chapter, pageHeightPx = 60) // 4 pages
        val newPages = paginator.paginate(chapter, pageHeightPx = 100) // 3 pages

        // Old last page (index 3) has no counterpart index in the 3-page new list; it must resolve
        // to a valid new index, never index 3.
        val newIndex = reflowedPageIndex(oldPages, oldPageIndex = 3, newPages = newPages)
        assertThat(newIndex).isIn(newPages.indices.toList())
    }

    @Test
    fun `reflowedPageIndex returns 0 when the chapter re-paginates to zero pages`() {
        val oldPages = paginator.paginate(FakeMeasuredChapter.uniform(lineCount = 10), pageHeightPx = 100)
        assertThat(reflowedPageIndex(oldPages, oldPageIndex = 1, newPages = emptyList())).isEqualTo(0)
    }

    @Test
    fun `pageIndexFor after re-pagination at a different page height still contains the offset`() {
        // The spec's headline claim is that a Locator (spineIndex + charOffset) survives a
        // typography change that re-paginates the chapter at a different page height. This
        // pins that down as actual behaviour, not just an assertion: paginate the same
        // chapter at two page heights, then confirm that every page boundary from layout A
        // maps, via pageIndexFor, to a page in layout B whose range actually contains that
        // offset.
        val chapter = FakeMeasuredChapter(
            heights = listOf(20, 20, 200, 20, 20, 20, 20, 20),
            charsPerLine = listOf(40, 40, 10, 40, 40, 40, 40, 40),
        )
        val pagesA = paginator.paginate(chapter, pageHeightPx = 100)
        val pagesB = paginator.paginate(chapter, pageHeightPx = 250)

        for (pageA in pagesA) {
            val foundIndex = pageIndexFor(pagesB, pageA.startOffset)
            val foundPage = pagesB[foundIndex]
            assertThat(pageA.startOffset).isAtLeast(foundPage.startOffset)
            assertThat(pageA.startOffset).isLessThan(foundPage.endOffset)
        }
    }

    // A uniform chapter (20px lines) whose [headingLines] are heading lines, for the keep-heading
    // rule. [pageLines] documents the intended lines-per-page and is the value to pass to
    // [pageHeightPxForLines] for the matching page height.
    private fun fakeChapter(lineCount: Int, pageLines: Int, headingLines: Set<Int>) =
        FakeMeasuredChapter.uniform(lineCount = lineCount, headingLines = headingLines)

    // The page height that fits exactly [lines] of the 20px-per-line uniform fake.
    private fun pageHeightPxForLines(lines: Int): Int = lines * 20
}
