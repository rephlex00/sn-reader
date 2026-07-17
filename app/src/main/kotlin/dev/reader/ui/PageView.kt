package dev.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    /** Whole-book progress in `[0,1]`, or null to draw no bar. Set via [setProgress]. */
    internal var progress: Float? = null
        private set

    private val density = resources.displayMetrics.density
    private val progressBarThicknessPx = 2f * density
    private val progressBarBottomInsetPx = 6f * density
    private val progressTrackPaint = Paint().apply { color = Color.parseColor("#CCCCCC") }
    private val progressFillPaint = Paint().apply { color = Color.BLACK }

    var onTap: ((TapZone) -> Unit)? = null

    init {
        setBackgroundColor(Color.WHITE)
        // No hardware layer, no animation: e-ink wants one clean full redraw per turn.
        //
        // Deliberately NOT isClickable: View's clickable path posts a CheckForTap and a
        // CheckForLongClick on every ACTION_DOWN, sets a pressed state, and posts a further
        // delayed runnable to unset it. This view wants none of that — there is no long-press
        // gesture, a pressed highlight would be a wasted e-ink refresh, and per-touch postDelayed
        // work is exactly what this reader avoids. Leaving it false means those callbacks are
        // never posted at all, so there is no pressed state to leak (see onTouchEvent).
    }

    fun show(layout: Layout, page: Page, marginPx: Int) {
        this.layout = layout
        this.page = page
        this.marginPx = marginPx
        invalidate()
    }

    /**
     * Sets the whole-book progress fraction the bottom bar shows (null hides it) and invalidates.
     * A display-only change — it never re-paginates. Called once per page turn from
     * [ReaderActivity.showPage], and directly when the progress-bar toggle flips.
     */
    fun setProgress(fraction: Float?) {
        this.progress = fraction
        invalidate()
    }

    /** Test-visible count of [fullRefresh] calls — the one observable of a background nicety. */
    internal var fullRefreshCount = 0
        private set

    /**
     * Forces a full-panel redraw to clear accumulated e-ink ghosting, driven by [ReaderActivity]
     * every `REFRESH_CADENCE` page turns. On stock Android this is a plain [invalidate] — the same
     * full redraw a normal page turn already issues — so its ghost-clearing effect is
     * vendor-dependent: it is the seam where a Supernote EPD full-refresh-mode hint would be applied
     * (a research item; the app is fully functional without it). Counter-driven, never time-driven,
     * so it adds no steady-state cost.
     */
    fun fullRefresh() {
        fullRefreshCount++
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

        // The progress bar lives OUTSIDE the text clip, pinned to the bottom edge of the view
        // (not the content box), so it sits at the very bottom and never overlaps text even at
        // marginPx 0. One extra draw folded into this same per-turn redraw — no separate pass.
        progress?.let { drawProgressBar(canvas, it) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        // With isClickable false, View would not consume ACTION_DOWN and we would never be sent
        // the matching ACTION_UP. Consume it here instead: this is the whole reason View's
        // clickable machinery is not needed. Nothing is posted and no pressed state is set, so
        // there is nothing for ACTION_UP/ACTION_CANCEL to have to clean up.
        MotionEvent.ACTION_DOWN -> true
        MotionEvent.ACTION_UP -> {
            val zone = tapZoneFor(event.x, width)
            // performClick() is what the old @SuppressLint("ClickableViewAccessibility") was
            // papering over. It still fires TYPE_VIEW_CLICKED for accessibility services when the
            // view is not clickable, and posts nothing.
            performClick()
            onTap?.invoke(zone)
            true
        }
        else -> super.onTouchEvent(event)
    }

    private fun drawProgressBar(canvas: Canvas, fraction: Float) {
        val left = marginPx.toFloat()
        val right = (width - marginPx).toFloat()
        if (right <= left) return
        val bottom = height - progressBarBottomInsetPx
        val top = bottom - progressBarThicknessPx
        canvas.drawRect(left, top, right, bottom, progressTrackPaint)
        val fillRight = left + (right - left) * fraction.coerceIn(0f, 1f)
        canvas.drawRect(left, top, fillRight, bottom, progressFillPaint)
    }
}
