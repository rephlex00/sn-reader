package dev.reader.formats.render

import android.graphics.Paint
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import kotlin.math.roundToInt

/**
 * Applies the publisher's `letter-spacing` to the run.
 *
 * Android's [TextPaint.letterSpacing] is expressed in ems — the same unit CSS resolves to
 * in [dev.reader.engine.InlineStyle.letterSpacingEm] — so the value maps across directly.
 * It is metric-affecting so the layout re-measures with the added tracking rather than only
 * repainting.
 */
class LetterSpacingSpan(private val letterSpacingEm: Float) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.letterSpacing = letterSpacingEm
    }

    override fun updateMeasureState(tp: TextPaint) {
        tp.letterSpacing = letterSpacingEm
    }
}

/**
 * Sets a block's line height to `round(multiplier × the font's natural height)`.
 *
 * The height is derived from the font metrics (`descent − ascent`), never an absolute pixel
 * value, so the reader's chosen text size still governs — the publisher only scales relative
 * to it. The extra space is distributed by scaling descent and pushing ascent up so the
 * resulting `descent − ascent` equals the target exactly. A non-positive natural height (an
 * empty line) is left untouched.
 *
 * This composes with the whole-chapter line-spacing multiplier the layout applies; it does
 * not replace it. A block whose publisher line-height is null carries no such span and falls
 * back entirely to the reader's line spacing.
 */
class MultiplierLineHeightSpan(private val multiplier: Float) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence?,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        val originalHeight = fm.descent - fm.ascent
        if (originalHeight <= 0) return
        val target = (multiplier * originalHeight).roundToInt()
        val ratio = target.toFloat() / originalHeight
        fm.descent = (fm.descent * ratio).roundToInt()
        fm.ascent = fm.descent - target
    }
}
