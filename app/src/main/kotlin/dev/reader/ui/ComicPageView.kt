package dev.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/** Destination rect that fits a [srcW]x[srcH] image into a [viewW]x[viewH] view, aspect-preserved, centered. */
fun fitRect(srcW: Int, srcH: Int, viewW: Int, viewH: Int): Rect {
    if (srcW <= 0 || srcH <= 0 || viewW <= 0 || viewH <= 0) return Rect(0, 0, viewW, viewH)
    val scale = minOf(viewW.toFloat() / srcW, viewH.toFloat() / srcH)
    val w = (srcW * scale).toInt()
    val h = (srcH * scale).toInt()
    val left = (viewW - w) / 2
    val top = (viewH - h) / 2
    return Rect(left, top, left + w, top + h)
}

/**
 * Draws one decoded comic page, fit to the view. The whole-image source rect is v1's only case
 * (fit-page); a future panel mode supplies sub-rects here with nothing else changing. No animation,
 * no hardware layer — an e-ink still page.
 */
class ComicPageView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val wholeImage = Rect()

    var onTap: ((TapZone) -> Unit)? = null
    var epd: EpdRefresher = NoopRefresher

    /** Incremented on every [fullRefresh] call — a test's only way to prove the chrome's "one clean
     *  refresh on close" promise without a real e-ink panel, mirroring [PageView.fullRefreshCount]. */
    internal var fullRefreshCount = 0

    fun show(bitmap: Bitmap?) {
        this.bitmap = bitmap
        if (bitmap != null) wholeImage.set(0, 0, bitmap.width, bitmap.height)
        invalidate()
    }

    fun fullRefresh() {
        fullRefreshCount++
        if (!epd.cleanRefresh()) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, wholeImage, fitRect(bmp.width, bmp.height, width, height), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            onTap?.invoke(tapZoneFor(event.x, width))
            return true
        }
        return true
    }
}
