package dev.reader.formats.render

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import dev.reader.engine.Block
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
            val start = sb.length
            appendBlock(sb, block, config)
            // The break offset is recorded only once a block actually contributes text,
            // and pins to that block's own first character — after the separator, and
            // past any text-free block (a Block.Image appends nothing) sitting between
            // the break and the text. Recording eagerly on the next block regardless
            // would land the break on the blank separator line, opening the new page
            // with an empty line (or, for a trailing break, a blank final page). A
            // pending break that never meets another text-bearing block contributes
            // no break at all.
            if (pendingBreak && sb.length > start) {
                breaks += start
                pendingBreak = false
            }
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

            // Inline images arrive with the reading UI; a placeholder would be worse than
            // nothing. Known, deliberate asymmetry until the image-rendering plan: an
            // image-ONLY chapter (cover-image first chapters, commonly) yields one blank
            // page, while a missing chapter yields zero pages. The reader's first-open
            // behavior on real books was tuned on hardware around exactly that blank
            // page — do not "fix" the page count blind; it changes with image rendering.
            is Block.Image -> Unit

            Block.PageBreak -> Unit
        }
    }

    private fun appendStyled(sb: SpannableStringBuilder, styled: StyledText) {
        val base = sb.length
        sb.append(styled.text)
        for (span in styled.spans) {
            // The parser emits one span per semantic emphasis, each with exactly one of
            // bold/italic/monospace set true, so at most one arm applies. The remaining
            // publisher fields on InlineStyle are ignored here for now; a later task wires
            // them to spans behind the publisher-styling toggle.
            val what = when {
                span.style.bold == true -> AndroidStyleSpan(Typeface.BOLD)
                span.style.italic == true -> AndroidStyleSpan(Typeface.ITALIC)
                span.style.monospace == true -> TypefaceSpan("monospace")
                else -> null
            }
            if (what != null) {
                sb.setSpan(what, base + span.start, base + span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
