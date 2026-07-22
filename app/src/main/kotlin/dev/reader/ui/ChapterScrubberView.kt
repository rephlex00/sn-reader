package dev.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import dev.reader.R

/**
 * The overlay's chapter scrubber: a track spanning the whole book, a tick per chapter, and a thumb
 * at the current position. Dragging it moves through the book.
 *
 * Deliberately NOT a `SeekBar` — SeekBar brings a ripple and an animated thumb, both disqualifying
 * on e-ink. This is a plain View with an `onDraw` and a touch handler, holding no animation, no
 * timer and no state beyond what it draws.
 *
 * It reports positions as whole-book fractions and knows nothing about chapters, pages or
 * pagination. Crucially, it renders no page: a drag emits [onScrubMove] (for the Activity to update
 * a text readout and move the thumb) but the book page does not repaint until the finger lifts and
 * [onScrubCommit] fires. That "no page render until release" rule was the owner's on-hardware
 * decision — live preview repaints far too often for e-ink.
 */
class ChapterScrubberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Fires once per gesture, on ACTION_DOWN, before the first [onScrubMove]. */
    var onScrubStart: (() -> Unit)? = null

    /** The fraction under the finger, on every DOWN and MOVE. The Activity uses it to update the
     *  readout only — never to render a page. */
    var onScrubMove: ((Float) -> Unit)? = null

    /** The final fraction, once, on ACTION_UP. The only signal that renders a page. Never fires for
     *  a cancelled gesture. */
    var onScrubCommit: ((Float) -> Unit)? = null

    /** Fires once on ACTION_CANCEL. The Activity uses this to abandon the scrub and restore the
     *  page it started from — without it a cancelled drag leaves the scrubber's origin state stuck. */
    var onScrubCancel: (() -> Unit)? = null

    private var chapterStarts: List<Float> = emptyList()
    private var progress: Float = 0f

    // Hoisted BEFORE the Paints: inside a `Paint().apply { }` block the name `density` resolves to
    // Paint's own member (always 1.0), silently dropping dp scaling.
    private val density = resources.displayMetrics.density
    private val trackThicknessPx = 2f * density
    private val tickHeightPx = 7f * density
    private val thumbRadiusPx = 7f * density

    private val trackPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.reader_progress_track)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    /**
     * Sets the book's shape and the current position, then invalidates. [chapterStartFractions] is
     * one whole-book fraction per chapter start, which becomes the tick marks.
     */
    fun setBook(chapterStartFractions: List<Float>, progress: Float) {
        this.chapterStarts = chapterStartFractions
        this.progress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    /** Moves the thumb without re-stating the book — used while a drag is in flight. */
    fun setProgress(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    private fun fractionAt(x: Float): Float {
        val usable = width - thumbRadiusPx * 2f
        if (usable <= 0f) return 0f
        return ((x - thumbRadiusPx) / usable).coerceIn(0f, 1f)
    }

    private fun xFor(fraction: Float): Float =
        thumbRadiusPx + (width - thumbRadiusPx * 2f) * fraction.coerceIn(0f, 1f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Claim the gesture: the overlay's root would otherwise let it fall through to the
                // PageView below, which reads a tap as a page turn.
                parent?.requestDisallowInterceptTouchEvent(true)
                onScrubStart?.invoke()
                val fraction = fractionAt(event.x)
                setProgress(fraction)
                onScrubMove?.invoke(fraction)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val fraction = fractionAt(event.x)
                setProgress(fraction)
                onScrubMove?.invoke(fraction)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val fraction = fractionAt(event.x)
                setProgress(fraction)
                onScrubCommit?.invoke(fraction)
                return true
            }

            // A cancelled gesture commits nothing: the Activity restores the position the scrub
            // started from, so an interrupted drag leaves the reader where it was.
            MotionEvent.ACTION_CANCEL -> {
                onScrubCancel?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val left = thumbRadiusPx
        val right = width - thumbRadiusPx
        if (right <= left) return
        val centreY = height / 2f

        canvas.drawRect(left, centreY - trackThicknessPx / 2f, right, centreY + trackThicknessPx / 2f, trackPaint)

        for (start in chapterStarts) {
            val x = xFor(start)
            canvas.drawRect(x, centreY - tickHeightPx / 2f, x + density, centreY + tickHeightPx / 2f, markPaint)
        }

        canvas.drawCircle(xFor(progress), centreY, thumbRadiusPx, markPaint)
    }
}
