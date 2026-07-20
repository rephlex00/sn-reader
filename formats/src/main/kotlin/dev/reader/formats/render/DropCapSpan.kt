package dev.reader.formats.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import kotlin.math.roundToInt

/**
 * Draws a chapter's opening [initial] as an enlarged drop cap spanning [linesSpanned] lines, with
 * the following lines flowed to its right — the "book-like" chapter opener.
 *
 * It is a [LeadingMarginSpan.LeadingMarginSpan2]: `LeadingMarginSpan2` is the one that reserves the
 * margin for the *first N lines* (plain [LeadingMarginSpan] only ever affects the first line), which
 * is exactly the drop-cap band. For the first [linesSpanned] lines [getLeadingMargin] reserves the
 * cap's width so the body text wraps to its right; afterwards it returns 0 and the text runs full
 * width.
 *
 * The initial STAYS a normal character in the chapter text — the caller applies this span over just
 * `[first, first + 1)` plus a transparent foreground so the normal-size glyph is invisible and only
 * the big one (drawn here) shows. Nothing is inserted or removed, so character offsets, locators,
 * highlights, and page-break math are all unchanged.
 *
 * The cap is painted from [capPaint] (the reader's [typeface] and [color]/gray), NOT from the `p`
 * handed to [drawLeadingMargin] — that paint belongs to the covered run, whose colour the caller's
 * transparent-foreground hide has already zeroed. Pixel placement (cap-height alignment to the first
 * line's top, baseline in the band) is device-tuned; this class pins the geometry the layout needs.
 */
class DropCapSpan(
    private val initial: Char,
    private val linesSpanned: Int,
    private val textSizePx: Float,
    private val lineHeightPx: Float,
    private val typeface: Typeface,
    private val color: Int = Color.BLACK,
) : LeadingMarginSpan.LeadingMarginSpan2 {

    /** The enlarged initial's paint: reader typeface + gray, sized to roughly fill the band. */
    private val capPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@DropCapSpan.typeface
        textSize = linesSpanned * lineHeightPx
        color = this@DropCapSpan.color
    }

    /** The initial's advance at the ordinary body size — the width the (transparent) covered char
     *  still consumes in the flowing text just past the reserved margin. */
    private val normalAdvance = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@DropCapSpan.typeface
        textSize = textSizePx
    }.measureText(initial.toString())

    private val capAdvance = capPaint.measureText(initial.toString())

    /** A small breathing gap between the cap and the text that wraps beside it. */
    private val gutterPx = textSizePx * GUTTER_EM

    /**
     * Width reserved on the first [linesSpanned] lines. Compensated by [normalAdvance]: the covered
     * char (drawn transparent) still takes its normal advance immediately after the margin, so
     * reserving `cap + gutter − normalAdvance` lands the wrapped text at `cap + gutter` from the
     * left overall — the text clears the cap with one gutter's gap and no double-width hole.
     */
    private val marginWidth = (capAdvance + gutterPx - normalAdvance).roundToInt().coerceAtLeast(0)

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
        // Only the first line of the paragraph carries the actual glyph; the margin is reserved on
        // the following band lines too, but the letter is drawn once, here.
        if (!first) return
        val glyph = initial.toString()
        val fm = capPaint.fontMetrics
        // Sit the glyph's top near the first text line's top (ascent is negative). Device-tuned.
        val capBaseline = top - fm.ascent
        // dir is +1 for LTR, -1 for RTL; anchor the glyph at the leading edge either way.
        val left = if (dir >= 0) x.toFloat() else x - capAdvance
        c.drawText(glyph, left, capBaseline, capPaint)
    }

    private companion object {
        /** Gutter between the drop cap and the wrapped body text, in ems of the body text size. */
        const val GUTTER_EM = 0.15f
    }
}
