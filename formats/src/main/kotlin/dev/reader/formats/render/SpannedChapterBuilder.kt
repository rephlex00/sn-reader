package dev.reader.formats.render

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import dev.reader.engine.Block
import dev.reader.engine.InlineStyle
import dev.reader.engine.RenderConfig
import dev.reader.engine.StyledText
import android.text.style.StyleSpan as AndroidStyleSpan

/** A chapter as a single styled string, plus the offsets where a hard page break falls. */
data class ChapterText(val text: Spanned, val breakOffsets: Set<Int>)

private const val BLOCK_SEPARATOR = "\n\n"
private val HEADING_SCALE = mapOf(1 to 1.6f, 2 to 1.4f, 3 to 1.25f, 4 to 1.15f, 5 to 1.1f, 6 to 1.05f)

/**
 * Flattens [Block]s into one Spanned per chapter. One string per chapter (rather than
 * per block) is what lets a single StaticLayout do all the shaping, and what makes a
 * character offset a meaningful locator.
 */
class SpannedChapterBuilder {

    fun build(blocks: List<Block>, config: RenderConfig): ChapterText {
        val sb = SpannableStringBuilder()
        val breaks = mutableSetOf<Int>()
        var pendingBreak = false

        for (block in blocks) {
            if (block is Block.PageBreak) {
                pendingBreak = true
                continue
            }
            if (sb.isNotEmpty()) sb.append(BLOCK_SEPARATOR)
            // Recorded after the separator (if any) so the offset lands on the
            // boundary right before the next block's own text, not before the
            // blank line that visually separates it from the previous block.
            if (pendingBreak) {
                breaks += sb.length
                pendingBreak = false
            }
            appendBlock(sb, block, config)
        }
        return ChapterText(sb, breaks)
    }

    private fun appendBlock(sb: SpannableStringBuilder, block: Block, config: RenderConfig) {
        when (block) {
            is Block.Paragraph -> appendStyled(sb, block.text)

            is Block.Heading -> {
                val start = sb.length
                appendStyled(sb, block.text)
                sb.setSpan(
                    RelativeSizeSpan(HEADING_SCALE.getValue(block.level)),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                sb.setSpan(
                    AndroidStyleSpan(Typeface.BOLD),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }

            is Block.Quote -> {
                val start = sb.length
                appendStyled(sb, block.text)
                sb.setSpan(
                    LeadingMarginSpan.Standard(config.textSizePx.toInt()),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                sb.setSpan(
                    AndroidStyleSpan(Typeface.ITALIC),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }

            is Block.ListItem -> {
                sb.append(block.ordinal?.let { "$it. " } ?: "• ")
                appendStyled(sb, block.text)
            }

            // Inline images arrive with the reading UI; a placeholder would be worse than nothing.
            is Block.Image -> Unit

            Block.PageBreak -> Unit
        }
    }

    private fun appendStyled(sb: SpannableStringBuilder, styled: StyledText) {
        val base = sb.length
        sb.append(styled.text)
        for (span in styled.spans) {
            val what = when (span.style) {
                InlineStyle.BOLD -> AndroidStyleSpan(Typeface.BOLD)
                InlineStyle.ITALIC -> AndroidStyleSpan(Typeface.ITALIC)
                InlineStyle.MONOSPACE -> TypefaceSpan("monospace")
            }
            sb.setSpan(what, base + span.start, base + span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
