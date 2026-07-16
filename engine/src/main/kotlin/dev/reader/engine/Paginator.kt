package dev.reader.engine

/** One screenful of a chapter: a contiguous run of lines. */
data class Page(
    val index: Int,
    val startLine: Int,
    val endLine: Int,
    val startOffset: Int,
    val endOffset: Int,
    val topPx: Int,
)

/**
 * Greedily slices a measured chapter into pages at line boundaries.
 *
 * Pagination is pure: same metrics plus same page height always yields the same pages,
 * which is what lets a locator survive a typography change.
 */
class Paginator {

    fun paginate(measured: MeasuredChapter, pageHeightPx: Int): List<Page> {
        require(pageHeightPx > 0) { "pageHeightPx must be positive, was $pageHeightPx" }
        if (measured.lineCount == 0) return emptyList()

        val pages = mutableListOf<Page>()
        var startLine = 0

        for (line in 1..measured.lineCount) {
            val atEnd = line == measured.lineCount
            val hardBreak = !atEnd && measured.isBreakBefore(line)
            // A line taller than the whole page still lands on its own page: the check
            // only fires for lines after startLine, so startLine always advances.
            val overflows = !atEnd &&
                measured.lineBottomPx(line) - measured.lineTopPx(startLine) > pageHeightPx

            if (atEnd || hardBreak || overflows) {
                pages += pageOf(pages.size, measured, startLine, line - 1)
                startLine = line
            }
        }
        return pages
    }

    private fun pageOf(index: Int, m: MeasuredChapter, firstLine: Int, lastLine: Int) = Page(
        index = index,
        startLine = firstLine,
        endLine = lastLine,
        startOffset = m.lineStartOffset(firstLine),
        endOffset = m.lineEndOffset(lastLine),
        topPx = m.lineTopPx(firstLine),
    )
}

/**
 * The index of the page containing [charOffset]; the last page if the offset is past
 * the end, page 0 if there are no pages. This is how a stored [Locator] is restored
 * after a re-pagination.
 */
fun pageIndexFor(pages: List<Page>, charOffset: Int): Int {
    if (pages.isEmpty()) return 0
    val index = pages.indexOfFirst { charOffset < it.endOffset }
    return if (index == -1) pages.lastIndex else index
}

/**
 * The page in [newPages] to land on after a live typography change re-paginated the chapter the
 * reader was showing as [oldPages]. This is the pure core of the Aa sheet's locator preservation:
 * a settings change re-flows the current chapter to a *different* page count, and the reader must
 * stay on the text it was showing — the page whose offset range contains the char offset at the top
 * of the current page — NOT the same page index. A larger font that pushes that text from page 3 to
 * page 5 lands the reader on page 5.
 *
 * It captures [oldPages]`[oldPageIndex].startOffset` — the stable char anchor the open-path restore
 * uses too — and resolves it through [pageIndexFor] over [newPages]. The returned index is always a
 * valid index into [newPages] (0 when it is empty), so a chapter that re-paginates to zero pages, or
 * an [oldPageIndex] that has drifted out of range, clamps rather than throwing. Pure over its three
 * arguments, so the headline locator-preservation property is unit-testable without an Activity or a
 * real StaticLayout (see PaginatorTest).
 */
fun reflowedPageIndex(oldPages: List<Page>, oldPageIndex: Int, newPages: List<Page>): Int {
    if (newPages.isEmpty() || oldPages.isEmpty()) return 0
    val anchor = oldPages[oldPageIndex.coerceIn(0, oldPages.lastIndex)].startOffset
    return pageIndexFor(newPages, anchor).coerceIn(0, newPages.lastIndex)
}
