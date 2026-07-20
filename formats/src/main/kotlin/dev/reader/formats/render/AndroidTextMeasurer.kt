package dev.reader.formats.render

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.reader.engine.Block
import dev.reader.engine.MeasuredChapter
import dev.reader.engine.RenderConfig
import dev.reader.engine.TextMeasurer

/**
 * The StaticLayout side of the measurement seam. Text shaping runs in native
 * Minikin/HarfBuzz, so this is the cheapest correct way to lay out text on Android —
 * and keeping it behind [TextMeasurer] is what lets Paginator stay JVM-testable.
 */
class AndroidTextMeasurer(
    private val builder: SpannedChapterBuilder,
    private val typefaces: TypefaceProvider,
) : TextMeasurer {

    override fun measure(blocks: List<Block>, config: RenderConfig): MeasuredChapter {
        val chapter = builder.build(blocks, config)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = config.textSizePx
            typeface = typefaces.get(config.fontFamily)
        }

        val layout = StaticLayout.Builder
            .obtain(chapter.text, 0, chapter.text.length, paint, config.contentWidthPx)
            .setLineSpacing(0f, config.lineSpacingMultiplier)
            .setJustificationMode(
                if (config.justified) Layout.JUSTIFICATION_MODE_INTER_WORD
                else Layout.JUSTIFICATION_MODE_NONE
            )
            .setHyphenationFrequency(
                if (config.hyphenated) Layout.HYPHENATION_FREQUENCY_NORMAL
                else Layout.HYPHENATION_FREQUENCY_NONE
            )
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setIncludePad(false)
            .build()

        return AndroidMeasuredChapter(layout, chapter.breakOffsets, chapter.headingRanges)
    }
}

/**
 * Wraps a laid-out chapter. [layout] is exposed because the reading view draws directly
 * from it — re-laying out text just to paint it would defeat the whole design.
 */
class AndroidMeasuredChapter(
    val layout: Layout,
    breakOffsets: Set<Int>,
    headingRanges: List<IntRange> = emptyList(),
) : MeasuredChapter {

    // Resolved once: getLineForOffset is a binary search, and pagination asks per line.
    private val breakLines: Set<Int> = breakOffsets
        .map { layout.getLineForOffset(it) }
        .filter { it > 0 }
        .toSet()

    // Each heading's char range mapped to the layout lines it spans — its first char's line
    // through its last char's line (end is exclusive, so end - 1). Resolved once for the same
    // reason as breakLines.
    private val headingLines: Set<Int> = headingRanges
        .flatMap { range -> layout.getLineForOffset(range.first)..layout.getLineForOffset(range.last) }
        .toSet()

    override val lineCount: Int get() = layout.lineCount
    override val totalHeightPx: Int get() = layout.height

    override fun lineTopPx(line: Int) = layout.getLineTop(line)
    override fun lineBottomPx(line: Int) = layout.getLineBottom(line)
    override fun lineStartOffset(line: Int) = layout.getLineStart(line)
    override fun lineEndOffset(line: Int) = layout.getLineEnd(line)
    override fun isBreakBefore(line: Int) = line in breakLines
    override fun isHeadingLine(line: Int) = line in headingLines
}
