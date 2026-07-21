package dev.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A monochrome on/off switch drawn on a Canvas — a pill track with a thumb at one end. It flips
 * INSTANTLY on a single [invalidate], with no thumb-slide animation: the platform `Switch`
 * animates its thumb, which on this e-ink panel would ghost and cost partial refreshes, so this
 * reader draws its own static switch instead (the same reason [PageView] is hand-drawn).
 *
 * State is set programmatically via [checked]; the containing row handles the tap, which writes
 * the pref and re-renders the sheet ([ReaderActivity.refreshSheet]) — that sets [checked] back.
 * The view is therefore a pure display of a boolean and holds no logic of its own.
 */
class ToggleSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var checked: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Hoisted deliberately: inside a `Paint().apply { }` block the name `density` resolves to the
    // Paint's own member rather than this view's, so `2f * density` there silently means 2px. The
    // switch border was drawing at 2px instead of 2dp on a 1.875-density panel.
    private val strokeWidthPx = 2f * density
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = Color.BLACK
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (WIDTH_DP * density).toInt()
        val h = (HEIGHT_DP * density).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val inset = strokePaint.strokeWidth
        val track = RectF(inset, inset, width - inset, height - inset)
        val radius = track.height() / 2f

        // Track: filled black when on, white with a black border when off — maximal contrast so the
        // on/off state is unmistakable on a grayscale panel.
        fillPaint.color = if (checked) Color.BLACK else Color.WHITE
        canvas.drawRoundRect(track, radius, radius, fillPaint)
        canvas.drawRoundRect(track, radius, radius, strokePaint)

        // Thumb: an inverse-colour circle at the right (on) or left (off).
        val thumbRadius = radius - THUMB_INSET_DP * density
        val centerX = if (checked) track.right - radius else track.left + radius
        fillPaint.color = if (checked) Color.WHITE else Color.BLACK
        canvas.drawCircle(centerX, track.centerY(), thumbRadius, fillPaint)
    }

    private companion object {
        const val WIDTH_DP = 52f
        const val HEIGHT_DP = 30f
        const val THUMB_INSET_DP = 4f
    }
}
