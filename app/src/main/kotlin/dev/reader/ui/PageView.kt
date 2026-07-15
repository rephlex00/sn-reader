package dev.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import dev.reader.engine.Page

enum class TapZone { PREVIOUS, NEXT, TOGGLE_OVERLAY }

/** Left 25% goes back, right 40% goes forward, the rest toggles the overlay. */
fun tapZoneFor(x: Float, width: Int): TapZone {
    if (width <= 0) return TapZone.TOGGLE_OVERLAY
    val fraction = x / width
    return when {
        fraction < 0.25f -> TapZone.PREVIOUS
        // >= not >: the right 40% is [0.60, 1.0), so x = 600 of width 1000 must land in NEXT,
        // not fall through to the else branch on the exact boundary.
        fraction >= 0.60f -> TapZone.NEXT
        else -> TapZone.TOGGLE_OVERLAY
    }
}

/**
 * Draws one page by translating a pre-laid-out chapter and clipping to the content box.
 * The Layout is never rebuilt here: a page turn is a translate, a clip and one draw.
 */
class PageView(context: Context) : View(context) {

    private var layout: Layout? = null
    private var page: Page? = null
    private var marginPx = 0

    var onTap: ((TapZone) -> Unit)? = null

    init {
        setBackgroundColor(Color.WHITE)
        // No hardware layer, no animation: e-ink wants one clean full redraw per turn.
        isClickable = true
    }

    fun show(layout: Layout, page: Page, marginPx: Int) {
        this.layout = layout
        this.page = page
        this.marginPx = marginPx
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout ?: return
        val page = page ?: return

        canvas.save()
        // Clip first: without it, the lines belonging to the next page would spill below.
        canvas.clipRect(marginPx, marginPx, width - marginPx, height - marginPx)
        canvas.translate(marginPx.toFloat(), (marginPx - page.topPx).toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            onTap?.invoke(tapZoneFor(event.x, width))
            return true
        }
        return super.onTouchEvent(event)
    }
}
