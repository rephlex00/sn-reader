package dev.reader.engine

/**
 * The seam that keeps pagination free of Android. Implementations do text shaping;
 * [Paginator] consumes only the resulting metrics, so its logic is JVM-testable
 * against synthetic measurements.
 */
interface TextMeasurer {
    fun measure(blocks: List<Block>, config: RenderConfig): MeasuredChapter
}

/** Line metrics for one laid-out chapter. Line and offset indices are zero-based. */
interface MeasuredChapter {
    val lineCount: Int
    val totalHeightPx: Int

    fun lineTopPx(line: Int): Int
    fun lineBottomPx(line: Int): Int

    /** Inclusive character offset of the line's first character. */
    fun lineStartOffset(line: Int): Int

    /** Exclusive character offset one past the line's last character. */
    fun lineEndOffset(line: Int): Int

    /** True when a hard page break must occur immediately before [line]. */
    fun isBreakBefore(line: Int): Boolean

    /**
     * True when [line] renders part of a heading block. Lets [Paginator] avoid stranding a
     * heading alone at a page foot, keeping it with the text it introduces. Defaults to false
     * so a measurer that carries no heading metadata paginates exactly as before.
     */
    fun isHeadingLine(line: Int): Boolean = false
}
