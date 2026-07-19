package dev.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.reader.engine.Page
import kotlin.math.abs

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

    var onStylusTap: ((offset: Int) -> Unit)? = null
    var onStylusDrag: ((startOffset: Int, endOffset: Int) -> Unit)? = null

    /** The hardware e-ink refresher [fullRefresh] uses. Defaults to [NoopRefresher] (so construction and
     *  tests need no device); [ReaderActivity] sets the real [EinkController] after construction. */
    var epd: EpdRefresher = NoopRefresher

    /**
     * Fallback seam for the Task 1 pen probe: when true, ALL touches route to selection regardless of
     * tool type (used only if the Nomad's pen does not reach the app as [MotionEvent.TOOL_TYPE_STYLUS],
     * in which case an explicit highlight-mode toggle drives this). When false (the expected case),
     * only stylus events select and the finger navigates.
     */
    var forceHighlightMode = false

    /** Highlight spans for the current chapter as (startOffset, endOffset-exclusive). Drawn as a wash. */
    private var highlightSpans: List<Pair<Int, Int>> = emptyList()

    /** The armed bracket-start offset, if any — drawn as a caret so the reader sees the pending start. */
    private var bracketAnchor: Int? = null

    private val washPaint = Paint().apply { color = Color.parseColor("#A8A8A8") } // highlight wash gray; tuned on device
    private val anchorPaint = Paint().apply { color = Color.BLACK; strokeWidth = 1.5f * density }
    private val selectionPath = Path()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var penDownX = 0f
    private var penDownY = 0f
    private var penDownOffset = 0
    private var penMoved = false

    /**
     * The live selection (min, max offset) drawn while the pen is mid-drag, so the reader sees the
     * highlight forming under the pen instead of nothing. This deliberately relaxes the
     * no-repaint-during-a-gesture rule for the ACTIVE pen drag only — the reader at rest and page
     * turns keep the e-ink discipline. Cleared on UP (the committed wash then arrives via
     * [setHighlights]). [lastPreviewOffset] throttles the redraw to when the boundary crosses a glyph.
     */
    private var pendingSelection: Pair<Int, Int>? = null
    private var lastPreviewOffset = -1

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

    /** Sets the current chapter's highlight spans (start, end-exclusive) to wash, and invalidates. */
    fun setHighlights(spans: List<Pair<Int, Int>>) {
        highlightSpans = spans
        invalidate()
    }

    /** Sets (or clears, with null) the pending bracket-start marker, and invalidates. */
    fun setBracketAnchor(offset: Int?) {
        bracketAnchor = offset
        invalidate()
    }

    /** The live drag preview (min, max offset) or null — a test asserts a MOVE builds it and UP clears it. */
    internal val pendingSelectionForTest: Pair<Int, Int>?
        get() = pendingSelection

    /**
     * The character offset under a screen point. Inverts [onDraw]'s translate (text drawn at
     * `marginPx, marginPx - page.topPx`) and clamps the line into the visible page's own line range, so
     * a touch in the bottom margin cannot land on a hidden next-page line.
     */
    internal fun offsetAt(x: Float, y: Float): Int {
        val layout = layout ?: return 0
        val page = page ?: return 0
        val layoutY = (y - marginPx + page.topPx).toInt()
        val line = layout.getLineForVertical(layoutY).coerceIn(page.startLine, page.endLine)
        val layoutX = (x - marginPx).coerceAtLeast(0f)
        return layout.getOffsetForHorizontal(line, layoutX)
    }

    /** Test-visible count of [fullRefresh] calls — the one observable of a background nicety. */
    internal var fullRefreshCount = 0
        private set

    /**
     * Forces a full-panel redraw to clear accumulated e-ink ghosting, driven by [ReaderActivity] on
     * the prefs-driven refresh cadence (every turn by default; every Nth turn in Faster page turns
     * mode — see [shouldFullRefresh]). Drives the real hardware clean refresh via [EpdRefresher]
     * when available, falling back to a plain [invalidate] when the panel API is unavailable or has
     * degraded — the reader never depends on the hidden EinkManager. Counter-driven, never time-driven,
     * so it adds no steady-state cost.
     */
    fun fullRefresh() {
        fullRefreshCount++
        // Prefer the real hardware clean refresh (clears ghosting); fall back to a plain redraw when the
        // panel API is unavailable or has degraded — the reader never depends on the hidden EinkManager.
        if (!epd.cleanRefresh()) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout ?: return
        val page = page ?: return

        canvas.save()
        // Clip first: without it, the lines belonging to the next page would spill below. The
        // bottom edge is THIS page's own content bottom, not the fixed content box (height -
        // marginPx): onDraw draws the whole chapter's Layout and shows only a page-sized window of
        // it, but a page breaks at a line boundary and rarely fills the box to the pixel, so a
        // fixed height-marginPx clip leaves a gap into which the NEXT page's first line bleeds — a
        // sliver of text clipped mid-glyph under the bottom margin. See [pageClipBottom].
        canvas.clipRect(marginPx, marginPx, width - marginPx, pageClipBottom(layout, page))
        canvas.translate(marginPx.toFloat(), (marginPx - page.topPx).toFloat())
        drawHighlights(canvas, layout, page)
        layout.draw(canvas)
        drawBracketAnchor(canvas, layout, page)
        canvas.restore()

        // The progress bar lives OUTSIDE the text clip, pinned to the bottom edge of the view
        // (not the content box), so it sits at the very bottom. Text clears it only because the
        // shipped margin floor (MARGIN_NARROW_PX = 24px) leaves a band below the last line — the
        // draw code does NOT itself enforce clearance, so at marginPx 0 (unreachable via the Aa
        // sheet) the bar would sit under the final line. One extra draw folded into this same
        // per-turn redraw — no separate pass.
        progress?.let { drawProgressBar(canvas, it) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val selecting = forceHighlightMode || event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        return if (selecting) handleSelectionTouch(event) else handleNavigationTouch(event)
    }

    /** The original navigation gesture: consume DOWN, fire the tap-zone on UP. */
    private fun handleNavigationTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
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

    /**
     * Pen selection: as the pen drags, paint the growing selection live (see [pendingSelection]) so
     * the reader can see what they are highlighting; on UP, fire a tap or a drag by whether it moved
     * past [touchSlop]. Offsets are resolved via [offsetAt].
     */
    private fun handleSelectionTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            penDownX = event.x
            penDownY = event.y
            penDownOffset = offsetAt(event.x, event.y)
            penMoved = false
            pendingSelection = null
            lastPreviewOffset = -1
            true
        }
        MotionEvent.ACTION_MOVE -> {
            if (abs(event.x - penDownX) > touchSlop || abs(event.y - penDownY) > touchSlop) {
                penMoved = true
                val current = offsetAt(event.x, event.y)
                // Redraw only when the boundary actually crosses a glyph — bounds the e-ink refreshes
                // to meaningful changes over the course of the drag.
                if (current != lastPreviewOffset) {
                    lastPreviewOffset = current
                    pendingSelection = minOf(penDownOffset, current) to maxOf(penDownOffset, current)
                    invalidate()
                }
            }
            true
        }
        MotionEvent.ACTION_UP -> {
            performClick()
            val hadPreview = pendingSelection != null
            pendingSelection = null
            lastPreviewOffset = -1
            if (penMoved) onStylusDrag?.invoke(penDownOffset, offsetAt(event.x, event.y))
            else onStylusTap?.invoke(penDownOffset)
            // Erase the live preview. Only when there actually was one — a plain tap never draws it,
            // and a committed wash arrives separately via setHighlights.
            if (hadPreview) invalidate()
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            // The touch stream was interrupted mid-drag (focus/window loss, a system gesture). Drop the
            // live preview so a later unrelated redraw cannot resurrect its stale offsets on the page.
            if (pendingSelection != null) {
                pendingSelection = null
                lastPreviewOffset = -1
                invalidate()
            }
            true
        }
        else -> super.onTouchEvent(event)
    }

    /**
     * The y at which the text clip ends: this page's last line's bottom, translated to screen
     * space (`marginPx + lineBottom(endLine) - topPx`), not the fixed content-box bottom
     * `height - marginPx`. Because the whole chapter's [Layout] is drawn and only a page-sized
     * window shown, clipping at the box bottom lets the next page's first line bleed into the slack
     * a line-boundary page break leaves below the last line. `minOf` with the box bottom guards the
     * one case the paginator allows a page to exceed the box — a single line taller than the whole
     * page, which it places alone — so that page still clips to the box rather than past it.
     */
    internal fun pageClipBottom(layout: Layout, page: Page): Int =
        minOf(height - marginPx, marginPx + layout.getLineBottom(page.endLine) - page.topPx)

    private fun drawHighlights(canvas: Canvas, layout: Layout, page: Page) {
        for ((start, end) in highlightSpans) {
            if (end <= page.startOffset || start >= page.endOffset) continue // not on this page
            selectionPath.reset()
            layout.getSelectionPath(start, end, selectionPath)
            canvas.drawPath(selectionPath, washPaint)
        }
        // The live drag preview, drawn in the same wash so it reads exactly like the final highlight.
        pendingSelection?.let { (start, end) ->
            if (start < end && end > page.startOffset && start < page.endOffset) {
                selectionPath.reset()
                layout.getSelectionPath(start, end, selectionPath)
                canvas.drawPath(selectionPath, washPaint)
            }
        }
    }

    /**
     * The view-space point just below the glyph at [offset] — where an on-page control (the delete
     * chip) is anchored — or null if [offset] is not on the visible page. Inverts [onDraw]'s translate.
     */
    internal fun caretPointFor(offset: Int): PointF? {
        val layout = layout ?: return null
        val page = page ?: return null
        if (offset < page.startOffset || offset > page.endOffset) return null
        // Clamp into the visible page's own lines, matching [offsetAt], so an offset at the exclusive
        // page end can't resolve to a next-page line and drop the chip below the visible content.
        val line = layout.getLineForOffset(offset).coerceIn(page.startLine, page.endLine)
        val x = layout.getPrimaryHorizontal(offset) + marginPx
        val y = layout.getLineBottom(line).toFloat() + (marginPx - page.topPx)
        return PointF(x, y)
    }

    private fun drawBracketAnchor(canvas: Canvas, layout: Layout, page: Page) {
        val anchor = bracketAnchor ?: return
        if (anchor < page.startOffset || anchor >= page.endOffset) return
        val line = layout.getLineForOffset(anchor)
        val x = layout.getPrimaryHorizontal(anchor)
        canvas.drawLine(x, layout.getLineTop(line).toFloat(), x, layout.getLineBottom(line).toFloat(), anchorPaint)
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
