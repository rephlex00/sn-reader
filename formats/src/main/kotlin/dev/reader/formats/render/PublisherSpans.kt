package dev.reader.formats.render

import android.text.TextPaint
import android.text.style.MetricAffectingSpan

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
