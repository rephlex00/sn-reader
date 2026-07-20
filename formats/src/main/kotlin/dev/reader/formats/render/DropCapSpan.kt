package dev.reader.formats.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import kotlin.math.roundToInt

/**
 * Draws a chapter's opening [initial] as a two-line drop cap: the glyph's top aligns with the top of
 * the FIRST line's letters and its baseline sits on the SECOND line's baseline, with the first two
 * lines of body text flowed to its right — the classic book chapter opener.
 *
 * It is a [LeadingMarginSpan.LeadingMarginSpan2]: that is the variant that reserves the margin for
 * the *first N lines* (plain [LeadingMarginSpan] only ever affects the first line), which is exactly
 * the drop-cap band. For the first [linesSpanned] lines [getLeadingMargin] reserves the cap's width
 * so body text wraps to its right; afterwards it returns 0 and text runs full width.
 *
 * The initial STAYS a normal character in the chapter text — the caller applies this span over just
 * `[first, first + 1)` plus a [ZeroWidthSpan] so the covered character consumes no inline advance and
 * draws nothing; only the big glyph (drawn here) shows. Nothing is inserted or removed, so character
 * offsets, locators, highlights, and page-break math are all unchanged. Because the covered character
 * has ZERO advance, the reserved margin is the FULL `capAdvance + gutter` uniformly on every band line.
 *
 * **Geometry (why it aligns to the letters, not to line boxes).** The cap's point size is derived from
 * real font metrics rather than a line-count × line-height guess: the target height (glyph top → glyph
 * baseline) is [`actualLineSpacingPx`][actualLineSpacingPx] (line-1 baseline → line-2 baseline) plus
 * the body font's cap-height (line-1 letter-top → line-1 baseline). [capPaint]'s size is then scaled
 * so the initial's measured cap-height equals that target. Drawn with its baseline on the second
 * line's baseline, the glyph's top therefore lands on the first line's letter-tops and its bottom on
 * the second line's baseline — both edges aligned to the text, independent of the font's internal
 * leading. Line spacing uses the paint's own `fontSpacing` (× the reader's multiplier), not the
 * nominal text size, so the two baselines it assumes match the ones [Layout] actually produces.
 */
class DropCapSpan(
    private val initial: Char,
    private val linesSpanned: Int,
    private val textSizePx: Float,
    private val lineHeightPx: Float,
    private val typeface: Typeface,
    private val color: Int = Color.BLACK,
) : LeadingMarginSpan.LeadingMarginSpan2 {

    private val glyph = initial.toString()

    /** The enlarged initial's paint: reader typeface + gray, sized in [init] to the two-line height. */
    private val capPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@DropCapSpan.typeface
        color = this@DropCapSpan.color
    }

    /** Baseline-to-baseline distance the [Layout] will use: the font's recommended line spacing at the
     *  body size × the reader's line-spacing multiplier ([lineHeightPx] / [textSizePx]). Using the real
     *  `fontSpacing` (not the nominal text size) is what makes the cap's assumed line-2 baseline match
     *  where the layout actually puts it, so the cap bottom lands on the letters, not near them. */
    private val actualLineSpacingPx: Float

    /** The enlarged glyph's advance — also the minimum leading margin so wrapped body text clears it. */
    internal val capAdvance: Float

    private val gutterPx = textSizePx * GUTTER_EM

    /** Width reserved on every band line, uniformly (the covered initial is zero-advance). */
    private val marginWidth: Int

    init {
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@DropCapSpan.typeface
            textSize = textSizePx
        }
        val multiplier = if (textSizePx > 0f) lineHeightPx / textSizePx else 1f
        actualLineSpacingPx = bodyPaint.fontSpacing * multiplier

        val bounds = Rect()
        bodyPaint.getTextBounds(glyph, 0, 1, bounds)
        val bodyCapHeight = (-bounds.top).toFloat().coerceAtLeast(1f) // top is negative (above baseline)

        // Target cap-height = (line-1 letter-top → line-1 baseline) + (line-1 baseline → line-2 baseline).
        val targetCapHeight = bodyCapHeight + (linesSpanned - 1) * actualLineSpacingPx

        // Scale the cap point size so the initial's own measured cap-height equals the target.
        capPaint.textSize = textSizePx
        capPaint.getTextBounds(glyph, 0, 1, bounds)
        val refCapHeight = (-bounds.top).toFloat().coerceAtLeast(1f)
        capPaint.textSize = textSizePx * (targetCapHeight / refCapHeight)

        capAdvance = capPaint.measureText(glyph)
        marginWidth = (capAdvance + gutterPx).roundToInt().coerceAtLeast(0)
    }

    override fun getLeadingMarginLineCount(): Int = linesSpanned

    override fun getLeadingMargin(first: Boolean): Int = if (first) marginWidth else 0

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout,
    ) {
        // The margin is reserved on every band line, but the glyph is drawn once, on the first line.
        if (!first) return
        val line = layout.getLineForOffset(start)
        // Sit the glyph's baseline on the LAST band line's baseline (line-2 for a two-line cap), so its
        // sized cap-height carries the top up to the first line's letter-tops. Fall back to the metric
        // estimate if the paragraph is shorter than the band (a rare one-line opening paragraph).
        val lastBandLine = line + linesSpanned - 1
        val capBaselineY = if (lastBandLine < layout.lineCount) {
            layout.getLineBaseline(lastBandLine).toFloat()
        } else {
            baseline + (linesSpanned - 1) * actualLineSpacingPx
        }
        // dir is +1 for LTR, -1 for RTL; anchor the glyph at the leading edge either way.
        val left = if (dir >= 0) x.toFloat() else x - capAdvance
        c.drawText(glyph, left, capBaselineY, capPaint)
    }

    private companion object {
        /** Gutter between the drop cap and the wrapped body text, in ems of the body text size. */
        const val GUTTER_EM = 0.15f
    }
}
