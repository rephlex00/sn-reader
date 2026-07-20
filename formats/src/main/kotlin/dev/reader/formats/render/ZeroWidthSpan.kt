package dev.reader.formats.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.text.style.ReplacementSpan

/**
 * Makes the covered character consume NO inline advance and draw nothing — used to hide the
 * chapter-opening initial that [DropCapSpan] enlarges and redraws itself, from the margin.
 *
 * A transparent [android.text.style.ForegroundColorSpan] (the earlier approach) only hides the
 * glyph's ink; the character still occupies its normal-size advance in the line, which is what
 * forced [DropCapSpan]'s margin to subtract that advance back out — correct on the paragraph's
 * literal first line (where the covered character sits), wrong on the drop cap's other band lines
 * (which don't contain that character at all, so nothing needs subtracting there). Owning the
 * character's rendering via [ReplacementSpan] instead — zero size, empty draw — removes the
 * character from line-width math entirely, so [DropCapSpan] can reserve one uniform margin for
 * the whole band. It also means a publisher color/gray span on the same character no longer
 * fights the hide: this span is the one Android calls to render the run, full stop.
 *
 * Applied over exactly the one covered character, inserting/removing nothing — same invariant
 * every other span in the drop-cap band relies on.
 */
class ZeroWidthSpan : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: FontMetricsInt?,
    ): Int = 0

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) = Unit
}
