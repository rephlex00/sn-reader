package dev.reader.engine

/**
 * Deterministic synthetic metrics: every line is [lineHeightPx] tall and holds exactly
 * [charsPerLine] characters. Lets pagination be asserted exactly, with no text shaping.
 */
class FakeMeasuredChapter(
    override val lineCount: Int,
    private val lineHeightPx: Int = 20,
    private val charsPerLine: Int = 40,
    private val hardBreaks: Set<Int> = emptySet(),
) : MeasuredChapter {
    override val totalHeightPx: Int get() = lineCount * lineHeightPx
    override fun lineTopPx(line: Int) = line * lineHeightPx
    override fun lineBottomPx(line: Int) = (line + 1) * lineHeightPx
    override fun lineStartOffset(line: Int) = line * charsPerLine
    override fun lineEndOffset(line: Int) = (line + 1) * charsPerLine
    override fun isBreakBefore(line: Int) = line in hardBreaks
}
