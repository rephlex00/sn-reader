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
