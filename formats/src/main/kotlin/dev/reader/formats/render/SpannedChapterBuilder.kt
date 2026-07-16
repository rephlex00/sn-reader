package dev.reader.formats.render

import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import dev.reader.engine.Block
import dev.reader.engine.BlockStyle
import dev.reader.engine.RenderConfig
import dev.reader.engine.StyledText
import dev.reader.engine.TextAlign
import kotlin.math.roundToInt
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
            is Block.Paragraph -> {
                val start = sb.length
                appendStyled(sb, block.text, config)
                applyBlockStyle(sb, start, sb.length, block.style, config)
            }

            is Block.Heading -> {
                val start = sb.length
                appendStyled(sb, block.text, config)
                // A Heading may now also carry the publisher's own size on individual runs.
                // Applying both the semantic HEADING_SCALE and a run's RelativeSizeSpan would
                // double-enlarge that run, so the semantic scale is applied only to the sub-ranges
                // the publisher did NOT size — the scale is the fallback for text the publisher
                // said nothing about. A run the publisher sized keeps exactly its size; the rest
                // of the heading still reads as a heading. The whole heading stays bold either way.
                val scale = HEADING_SCALE.getValue(block.level)
                val sizedRuns = if (config.publisherStyling) {
                    block.text.spans.filter { it.style.sizeRatio != null }
                        .map { start + it.start to start + it.end }
                } else {
                    emptyList()
                }
                for ((from, to) in unsizedRanges(start, sb.length, sizedRuns)) {
                    // A fresh span per range: one RelativeSizeSpan instance can only occupy one range.
                    sb.setSpan(RelativeSizeSpan(scale), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                sb.setSpan(
                    AndroidStyleSpan(Typeface.BOLD),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                applyBlockStyle(sb, start, sb.length, block.style, config)
            }

            is Block.Quote -> {
                val start = sb.length
                appendStyled(sb, block.text, config)
                sb.setSpan(
                    LeadingMarginSpan.Standard(config.textSizePx.toInt()),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                sb.setSpan(
                    AndroidStyleSpan(Typeface.ITALIC),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                applyBlockStyle(sb, start, sb.length, block.style, config)
            }

            is Block.ListItem -> {
                val start = sb.length
                sb.append(block.ordinal?.let { "$it. " } ?: "• ")
                appendStyled(sb, block.text, config)
                applyBlockStyle(sb, start, sb.length, block.style, config)
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

    /**
     * The maximal sub-ranges of `[from, to)` NOT covered by any of the [covered] ranges — the gaps
     * between publisher-sized heading runs, where the semantic heading scale still applies. [covered]
     * ranges are clamped to the heading and merged; the returned gaps are in order and non-empty.
     * With no covered ranges this is just `[from, to)` (the ordinary whole-heading scale).
     */
    private fun unsizedRanges(from: Int, to: Int, covered: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (covered.isEmpty()) return if (to > from) listOf(from to to) else emptyList()
        val merged = covered
            .map { (s, e) -> maxOf(s, from) to minOf(e, to) }
            .filter { (s, e) -> e > s }
            .sortedBy { it.first }
        val gaps = mutableListOf<Pair<Int, Int>>()
        var cursor = from
        for ((s, e) in merged) {
            if (s > cursor) gaps += cursor to s
            cursor = maxOf(cursor, e)
        }
        if (cursor < to) gaps += cursor to to
        return gaps
    }

    private fun appendStyled(sb: SpannableStringBuilder, styled: StyledText, config: RenderConfig) {
        val base = sb.length
        sb.append(styled.text)
        for (span in styled.spans) {
            val style = span.style
            val from = base + span.start
            val to = base + span.end
            fun set(what: Any) = sb.setSpan(what, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            // Emphasis is honored regardless of the toggle — it is the reader's baseline,
            // not publisher decoration. A run may now carry several fields at once (a Task 3
            // multi-property element emits one single-field span per property over the same
            // range), so each field is applied independently rather than as one exclusive arm.
            if (style.bold == true) set(AndroidStyleSpan(Typeface.BOLD))
            if (style.italic == true) set(AndroidStyleSpan(Typeface.ITALIC))
            if (style.monospace == true) set(TypefaceSpan("monospace"))

            // The remaining publisher decoration only applies when styling is on; when off
            // these fields are ignored entirely, leaving today's emphasis-only rendering.
            if (config.publisherStyling) {
                // Relative so the reader's base text size still governs — never absolute.
                style.sizeRatio?.let { set(RelativeSizeSpan(it)) }
                if (style.underline == true) set(UnderlineSpan())
                if (style.strikethrough == true) set(StrikethroughSpan())
                style.letterSpacingEm?.let { set(LetterSpacingSpan(it)) }
                style.grayLevel?.let {
                    val g = (it * 255f).roundToInt().coerceIn(0, 255)
                    set(ForegroundColorSpan(Color.rgb(g, g, g)))
                }
            }
        }
    }

    /**
     * Applies the publisher's block-level style over a block's text range [start, end).
     * A no-op when publisher styling is off (the reader's whole-chapter alignment,
     * line-spacing and margins govern instead). Never overrides the reader's text SIZE.
     *
     * Alignment: LEFT/RIGHT/CENTER map to an [AlignmentSpan]; JUSTIFY has no per-span
     * equivalent, so a publisher `text-align: justify` (and the `align == null` fallback
     * to the reader's default) is left to the whole-chapter justification set on the
     * StaticLayout in AndroidTextMeasurer. Consequence: whether a paragraph is justified
     * remains a whole-chapter setting; LEFT/RIGHT/CENTER overrides are honored per-paragraph.
     *
     * Line-height ([BlockStyle.lineHeightMultiplier]) is deliberately NOT applied — by owner
     * decision the reader's own whole-chapter line spacing always governs, so a publisher's
     * per-block line-height is never honored (unlike font-size, which scales relative to the
     * reader). It is still resolved into the model; the renderer simply ignores it.
     *
     * Margins ([BlockStyle.marginTopEm]/[marginBottomEm]) are deliberately NOT applied here:
     * inter-block spacing would mean a style-driven separator, and the page-break offset
     * logic in [build] is pinned to the fixed "\n\n" separator length — that invariant was
     * hardware-tuned and is not worth risking for margin spacing. Deferred; see the plan.
     */
    private fun applyBlockStyle(
        sb: SpannableStringBuilder,
        start: Int,
        end: Int,
        style: BlockStyle,
        config: RenderConfig,
    ) {
        if (!config.publisherStyling || end <= start) return
        fun set(what: Any) = sb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        style.align?.let { align ->
            when (align) {
                TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
                TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlign.JUSTIFY -> null // whole-chapter setting; see KDoc
            }?.let { set(AlignmentSpan.Standard(it)) }
        }
        style.textIndentEm?.let { indent ->
            set(LeadingMarginSpan.Standard((indent * config.textSizePx).roundToInt(), 0))
        }
        // lineHeightMultiplier: intentionally ignored — the reader's line spacing always wins.
        // marginTopEm / marginBottomEm: deferred — see KDoc.
    }
}
