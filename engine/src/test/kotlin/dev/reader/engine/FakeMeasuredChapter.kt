package dev.reader.engine

/**
 * Synthetic metrics for [Paginator] tests: line [i] is [heights]\[i] px tall and holds
 * [charsPerLine]\[i] characters, both accumulated to give each line its own top and start
 * offset. Deliberately non-uniform by default — a paginator that conflates line height with
 * character offset (e.g. computing an offset from height instead of from `charsPerLine`), or
 * one that mis-derives a page boundary from the wrong line, produces a different result than
 * the real line-by-line math and a test built on this fake will catch it. A uniform grid
 * (`line * 20`, `line * 40`) cannot: every line answers identically, so a paginator using the
 * wrong line entirely is indistinguishable from a correct one.
 *
 * Every accessor requires its line index to be in range, so a caller that walks past
 * `lineCount` (e.g. [Paginator] with its `atEnd` guard removed) fails loudly instead of
 * silently getting a plausible-looking answer for an out-of-range line.
 */
class FakeMeasuredChapter(
    private val heights: List<Int>,
    private val charsPerLine: List<Int>,
    private val hardBreaks: Set<Int> = emptySet(),
    private val headingLines: Set<Int> = emptySet(),
) : MeasuredChapter {

    init {
        require(heights.size == charsPerLine.size) {
            "heights (${heights.size}) and charsPerLine (${charsPerLine.size}) must be the same length"
        }
    }

    override val lineCount: Int get() = heights.size
    override val totalHeightPx: Int get() = heights.sum()

    // Running totals: lineTops[i] / lineStarts[i] is the top-px / char-offset of line i.
    private val lineTops: List<Int> = heights.runningFold(0, Int::plus).dropLast(1)
    private val lineStarts: List<Int> = charsPerLine.runningFold(0, Int::plus).dropLast(1)

    override fun lineTopPx(line: Int): Int = lineTops[checked(line)]
    override fun lineBottomPx(line: Int): Int = checked(line).let { lineTops[it] + heights[it] }
    override fun lineStartOffset(line: Int): Int = lineStarts[checked(line)]
    override fun lineEndOffset(line: Int): Int = checked(line).let { lineStarts[it] + charsPerLine[it] }
    override fun isBreakBefore(line: Int): Boolean = checked(line) in hardBreaks
    override fun isHeadingLine(line: Int): Boolean = checked(line) in headingLines

    private fun checked(line: Int): Int {
        require(line in 0 until lineCount) { "line $line out of range 0 until $lineCount" }
        return line
    }

    companion object {
        /**
         * A uniform grid: every line is [lineHeightPx] tall and holds exactly [charsPerLine]
         * characters. Still the clearest way to express a test about page *count* or *hard
         * breaks* where per-line variation would only add noise — but prefer the non-uniform
         * primary constructor whenever a test is actually asserting a height- or
         * offset-dependent boundary.
         */
        fun uniform(
            lineCount: Int,
            lineHeightPx: Int = 20,
            charsPerLine: Int = 40,
            hardBreaks: Set<Int> = emptySet(),
            headingLines: Set<Int> = emptySet(),
        ) = FakeMeasuredChapter(
            heights = List(lineCount) { lineHeightPx },
            charsPerLine = List(lineCount) { charsPerLine },
            hardBreaks = hardBreaks,
            headingLines = headingLines,
        )
    }
}
