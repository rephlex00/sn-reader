package dev.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.text.Layout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.reader.R
import dev.reader.engine.Page
import kotlin.math.abs
import kotlin.math.ceil

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

/** The right-hand half of the running foot: 1-based page-in-chapter of the chapter's page count. */
fun runningFootLabel(pageInChapter: Int, pageCount: Int): String = "page $pageInChapter of $pageCount"

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
    private val progressTrackPaint = Paint().apply { color = context.getColor(R.color.reader_progress_track) }
    private val progressFillPaint = Paint().apply { color = Color.BLACK }

    /** The running foot's chapter title (null/blank draws the page label only). Set via [setRunningFoot]. */
    private var runningFootChapterTitle: String? = null

    /** 1-based page-in-chapter shown by the running foot's right-hand label. Set via [setRunningFoot]. */
    private var runningFootPageInChapter: Int = 0

    /** The chapter's page count shown by the running foot's right-hand label. Set via [setRunningFoot]. */
    private var runningFootPageCount: Int = 0

    /** Test-visible readout of what [setRunningFoot] last stored. */
    internal val chapterTitleForTest: String? get() = runningFootChapterTitle
    internal val pageInChapterForTest: Int get() = runningFootPageInChapter
    internal val pageCountForTest: Int get() = runningFootPageCount

    private val runningFootBottomInsetPx = progressBarBottomInsetPx + progressBarThicknessPx + 4f * density

    /**
     * Hoisted out of the [runningFootPaint] builder below, and it must stay out of it: inside a
     * `Paint().apply { }` block the name `density` resolves to the *Paint's* own `density` member,
     * not this view's. That silently made every scaled value in such a block a raw-pixel one — the
     * running foot asked for 11dp and drew at 11px, a third of its intended size on this panel, and
     * two rounds of enlarging it changed 11px to 16px while looking like nothing had happened.
     *
     * 13dp is @dimen/text_meta, the size the app already uses for metadata that sits beside content
     * without competing with it — which is exactly this line's job.
     */
    private val runningFootSizePx = 13f * density

    /**
     * Test-visible readout of the size the foot paint ACTUALLY carries — read off the paint, not
     * off [runningFootSizePx], because the paint builder is where the density bug lived and a test
     * that reads the hoisted value back would pass against the broken code too.
     */
    internal val runningFootSizePxForTest: Float get() = runningFootPaint.textSize

    private val runningFootPaint = TextPaint().apply {
        color = context.getColor(R.color.reader_text_faint) // fainter than body text; matches the progress bar's restraint
        // See [runningFootSizePx] for the size and why it is computed outside this block. Sizing
        // this freely is only safe because [bottomChromeHeightPx] is reserved out of the text area;
        // before that, a foot this size overdrew the last line at the narrow margin.
        textSize = runningFootSizePx
        isAntiAlias = true
    }

    /**
     * Height, up from the view's bottom edge, of the band the progress bar and running foot are
     * drawn into. Callers building a [dev.reader.engine.RenderConfig] pass this as `bottomChromePx`
     * so the paginator keeps the last line clear of it — this chrome sits OUTSIDE the text clip and
     * would otherwise overdraw text at any margin shallower than the band.
     *
     * Measured from the paint's own metrics rather than assumed from the point size, so it stays
     * correct if the foot's size or typeface changes.
     */
    internal val bottomChromeHeightPx: Int =
        ceil(runningFootBottomInsetPx + runningFootPaint.descent() - runningFootPaint.ascent()).toInt()

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

    private val washPaint = Paint().apply { color = context.getColor(R.color.reader_highlight_wash) } // tuned on device
    // anchorStrokePx is hoisted for the same reason runningFootSizePx is: `density` inside a
    // Paint builder block is the Paint's, not this view's. The caret was drawing 1.5px wide.
    private val anchorStrokePx = 1.5f * density
    private val anchorPaint = Paint().apply { color = Color.BLACK; strokeWidth = anchorStrokePx }
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

    /**
     * Sets the running foot's chapter title and 1-based page-in-chapter/page-count, and invalidates.
     * Display-only, computed once per page turn from [ReaderActivity.showPage] — never steady-state
     * work. A null or blank [chapterTitle] draws the page label alone (see [drawRunningFoot]).
     */
    fun setRunningFoot(chapterTitle: String?, pageInChapter: Int, pageCount: Int) {
        this.runningFootChapterTitle = chapterTitle
        this.runningFootPageInChapter = pageInChapter
        this.runningFootPageCount = pageCount
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

        // The progress bar lives OUTSIDE the text clip, pinned to the bottom edge of the view (not
        // the content box), so it sits at the very bottom. This draw code does NOT itself enforce
        // clearance from the text; the paginator does, by reserving [bottomChromeHeightPx] out of
        // the text area (see ReaderPrefs.renderConfig). Clearance therefore holds at every margin
        // preset, including one shallower than this band — which the narrow preset (40px) is.
        // One extra draw folded into this same per-turn redraw — no separate pass.
        progress?.let { drawProgressBar(canvas, it) }

        // Same band, same discipline: the running foot is also outside the text clip, drawn once
        // per turn from values ReaderActivity.showPage already computed, above the progress bar
        // (see runningFootBottomInsetPx) so the two never overlap.
        drawRunningFoot(canvas)
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

    /**
     * Faint running foot in the bottom margin band, above the progress bar: chapter title at the
     * left (ellipsized so it can never run into the label), [runningFootLabel] right-aligned. Drawn
     * unconditionally from whatever [setRunningFoot] last stored — like the progress bar, this is one
     * extra draw folded into the normal per-turn redraw, not a separate pass.
     */
    private fun drawRunningFoot(canvas: Canvas) {
        val left = marginPx.toFloat()
        val right = (width - marginPx).toFloat()
        if (right <= left) return

        val baseline = height - runningFootBottomInsetPx - runningFootPaint.descent()
        val label = runningFootLabel(runningFootPageInChapter, runningFootPageCount)
        val labelWidth = runningFootPaint.measureText(label)
        canvas.drawText(label, right - labelWidth, baseline, runningFootPaint)

        val title = runningFootChapterTitle
        if (title.isNullOrBlank()) return
        val gapPx = 12f * density // keeps the ellipsized title from crowding the label even when it fills its space
        val titleWidth = (right - left - labelWidth - gapPx).coerceAtLeast(0f)
        if (titleWidth <= 0f) return
        val ellipsized = TextUtils.ellipsize(title, runningFootPaint, titleWidth, TextUtils.TruncateAt.END)
        canvas.drawText(ellipsized, 0, ellipsized.length, left, baseline, runningFootPaint)
    }
}
