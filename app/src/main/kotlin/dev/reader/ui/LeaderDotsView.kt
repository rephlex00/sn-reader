package dev.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import dev.reader.R

/**
 * How many leader dots fit across [widthPx] at [spacingPx] between dot centres.
 *
 * Pure, so the geometry is testable without inflating a View. A non-positive spacing or width
 * yields zero rather than dividing by zero or looping forever — this runs inside `onDraw`, where a
 * bad value would hang the reader rather than merely look wrong.
 */
internal fun dotCountFor(widthPx: Float, spacingPx: Float): Int {
    if (widthPx <= 0f || spacingPx <= 0f) return 0
    return (widthPx / spacingPx).toInt()
}

/**
 * The dotted leader between a Contents entry's title and its percentage — the run of dots a printed
 * contents page uses to carry the eye across the gap.
 *
 * Drawn rather than typed: a string of `.` characters cannot be made to align across rows of
 * differing title width, which is the entire visual point of a leader. This View takes whatever
 * width the row's layout leaves it and fills it.
 *
 * No state, no animation, no invalidation of its own — it draws once per bind, as everything on
 * this panel does.
 */
class LeaderDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Hoisted BEFORE the Paint below: inside a `Paint().apply { }` block the name `density`
    // resolves to Paint's own member (always 1.0), silently dropping dp scaling. That trap has
    // already cost this project three rounds of debugging on the running foot.
    private val density = resources.displayMetrics.density
    private val dotRadiusPx = 0.8f * density
    private val dotSpacingPx = 5f * density

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.reader_text_secondary)
    }

    override fun onDraw(canvas: Canvas) {
        val count = dotCountFor(width.toFloat(), dotSpacingPx)
        if (count == 0) return
        val y = height / 2f
        for (i in 0 until count) {
            canvas.drawCircle(i * dotSpacingPx + dotSpacingPx / 2f, y, dotRadiusPx, dotPaint)
        }
    }
}
