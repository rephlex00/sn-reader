package dev.reader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View.MeasureSpec
import com.google.common.truth.Truth.assertThat
import dev.reader.engine.Page
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageViewTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun laidOutPageView(): PageView {
        val view = PageView(context)
        val text = "Hello world, this is a page of text."
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(), 300).build()
        val page = Page(index = 0, startLine = 0, endLine = layout.lineCount - 1, startOffset = 0, endOffset = text.length, topPx = 0)
        view.show(layout, page, marginPx = 20)
        val w = MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY)
        val h = MeasureSpec.makeMeasureSpec(600, MeasureSpec.EXACTLY)
        view.measure(w, h)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    @Test
    fun `the running foot page label is one-based N of M`() {
        assertThat(runningFootLabel(pageInChapter = 3, pageCount = 12)).isEqualTo("page 3 of 12")
    }

    @Test
    fun `setRunningFoot stores the values and invalidates`() {
        val view = PageView(context)
        view.setRunningFoot("Chapter One", pageInChapter = 2, pageCount = 5)
        assertThat(view.chapterTitleForTest).isEqualTo("Chapter One")
        assertThat(view.pageInChapterForTest).isEqualTo(2)
        assertThat(view.pageCountForTest).isEqualTo(5)
    }

    @Test
    fun `it draws a page with a running foot, with and without a chapter title, no error`() {
        // Screencap is black on e-ink, so a Canvas regression must surface here.
        val view = laidOutPageView()
        val canvas = Canvas(Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888))

        view.setRunningFoot("A Long Chapter Title That Should Get Ellipsized", pageInChapter = 3, pageCount = 12)
        view.draw(canvas)
        view.setRunningFoot(null, pageInChapter = 1, pageCount = 1)
        view.draw(canvas)
        view.setRunningFoot("", pageInChapter = 1, pageCount = 1)
        view.draw(canvas)
    }

    @Test
    fun `progress defaults to null and is settable`() {
        val view = PageView(context)
        assertThat(view.progress).isNull()
        view.setProgress(0.5f)
        assertThat(view.progress).isEqualTo(0.5f)
        view.setProgress(null)
        assertThat(view.progress).isNull()
    }

    @Test
    fun `setProgress stores the chapter end fraction`() {
        val view = PageView(context)

        view.setProgress(0.3f, 0.5f)
        assertThat(view.chapterEndForTest).isWithin(1e-6f).of(0.5f)

        view.setProgress(0.3f, null)
        assertThat(view.chapterEndForTest).isNull()
    }

    @Test
    fun `setProgress keeps its single argument form working`() {
        val view = PageView(context)

        view.setProgress(0.3f)

        assertThat(view.progress).isWithin(1e-6f).of(0.3f)
        assertThat(view.chapterEndForTest).isNull()
    }

    @Test
    fun `the clip stops at this page's last line, so the next page's first line cannot bleed in`() {
        // Regression: onDraw draws the WHOLE chapter's Layout and shows only a page-sized window.
        // A page breaks at a line boundary and rarely fills the content box to the pixel, so
        // clipping at the fixed box bottom (height - marginPx) left slack the NEXT line bled into —
        // a sliver of text clipped mid-glyph under the bottom margin. The clip must stop at THIS
        // page's last line's bottom instead.
        val view = PageView(context)
        val text = (1..60).joinToString(" ") { "word$it" } // wraps to many narrow lines
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, TextPaint().apply { textSize = 24f }, 180)
            .build()
        check(layout.lineCount > 5) { "need a multi-line layout for a bleed to be possible" }

        val marginPx = 30
        val endLine = 2 // a page that ends well before the layout's last line
        val page = Page(
            index = 0, startLine = 0, endLine = endLine,
            startOffset = 0, endOffset = 1, topPx = layout.getLineTop(0),
        )
        view.show(layout, page, marginPx)
        view.measure(
            MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(600, MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val clipBottom = view.pageClipBottom(layout, page)

        // Ends exactly at this page's last line's bottom, in screen space...
        assertThat(clipBottom).isEqualTo(marginPx + layout.getLineBottom(endLine) - page.topPx)
        // ...which is where the NEXT line begins, so that line is fully excluded (the bug drew it).
        val nextLineScreenTop = marginPx + layout.getLineTop(endLine + 1) - page.topPx
        assertThat(clipBottom).isAtMost(nextLineScreenTop)
        // ...and it stops well short of the fixed content-box bottom that caused the bleed.
        assertThat(clipBottom).isLessThan(view.height - marginPx)
    }

    @Test
    fun `a single line taller than the page clips to the content box, not past it`() {
        // The paginator places a line taller than the whole page alone on its own page. There the
        // page's span exceeds the content box, so the clip must fall back to the box bottom
        // (height - marginPx) — the minOf guard — rather than extending past the view.
        val view = PageView(context)
        val text = "TALL"
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, TextPaint().apply { textSize = 90f }, 400)
            .build()
        val page = Page(
            index = 0, startLine = 0, endLine = 0,
            startOffset = 0, endOffset = text.length, topPx = layout.getLineTop(0),
        )
        val marginPx = 20
        view.show(layout, page, marginPx)
        view.measure(
            MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY), // box (60px) shorter than the line
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        // Precondition: the line really is taller than the content box, so the guard is what fires.
        check(layout.getLineBottom(0) - page.topPx > view.height - 2 * marginPx) {
            "test needs a line taller than the content box to exercise the guard"
        }

        assertThat(view.pageClipBottom(layout, page)).isEqualTo(view.height - marginPx)
    }

    @Test
    fun `it draws a page both with a progress bar and without, no error`() {
        // Screencap is black on e-ink, so a Canvas regression must surface here. Exercise the
        // bar-present and bar-absent branches of onDraw.
        val view = laidOutPageView()
        val canvas = Canvas(Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888))

        view.setProgress(0.5f)
        view.draw(canvas)
        view.setProgress(null)
        view.draw(canvas)
    }

    @Test
    fun `a finger tap routes to navigation, not selection`() {
        val view = laidOutPageView()
        var navZone: TapZone? = null
        var stylusTapped = false
        view.onTap = { navZone = it }
        view.onStylusTap = { stylusTapped = true }

        dispatch(view, MotionEvent.TOOL_TYPE_FINGER, downX = 20f, downY = 40f, upX = 20f, upY = 40f)

        assertThat(stylusTapped).isFalse()
        assertThat(navZone).isEqualTo(TapZone.PREVIOUS) // left 25%
    }

    @Test
    fun `a stylus tap routes to onStylusTap, not navigation`() {
        val view = laidOutPageView()
        var navZone: TapZone? = null
        var tapOffset: Int? = null
        view.onTap = { navZone = it }
        view.onStylusTap = { tapOffset = it }

        dispatch(view, MotionEvent.TOOL_TYPE_STYLUS, downX = 40f, downY = 40f, upX = 40f, upY = 40f)

        assertThat(navZone).isNull()
        assertThat(tapOffset).isNotNull()
    }

    @Test
    fun `a stylus drag routes to onStylusDrag`() {
        val view = laidOutPageView()
        var dragged: Pair<Int, Int>? = null
        view.onStylusDrag = { s, e -> dragged = s to e }

        dispatch(view, MotionEvent.TOOL_TYPE_STYLUS, downX = 30f, downY = 40f, upX = 300f, upY = 40f, moveThrough = true)

        assertThat(dragged).isNotNull()
    }

    @Test
    fun `forceHighlightMode makes a finger event select instead of navigate`() {
        val view = laidOutPageView()
        view.forceHighlightMode = true
        var stylusTapped = false
        view.onStylusTap = { stylusTapped = true }

        dispatch(view, MotionEvent.TOOL_TYPE_FINGER, downX = 40f, downY = 40f, upX = 40f, upY = 40f)

        assertThat(stylusTapped).isTrue()
    }

    @Test
    fun `a stylus drag paints a live preview while the pen is down and clears it on lift`() {
        val view = laidOutPageView()
        fun ev(action: Int, x: Float, y: Float): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
            return MotionEvent.obtain(0, 0, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, 0, 0)
        }
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 40f))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 320f, 40f))
        assertThat(view.pendingSelectionForTest).isNotNull() // the forming highlight is shown mid-drag

        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 320f, 40f))
        assertThat(view.pendingSelectionForTest).isNull() // and removed on lift (committed wash replaces it)
    }

    @Test
    fun `a cancelled stylus drag clears the live preview so it cannot resurface at rest`() {
        val view = laidOutPageView()
        fun ev(action: Int, x: Float, y: Float): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
            return MotionEvent.obtain(0, 0, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, 0, 0)
        }
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 40f))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 320f, 40f))
        assertThat(view.pendingSelectionForTest).isNotNull()

        view.dispatchTouchEvent(ev(MotionEvent.ACTION_CANCEL, 320f, 40f))
        assertThat(view.pendingSelectionForTest).isNull()
    }

    @Test
    fun `fullRefresh uses the EPD clean refresh when available and still counts`() {
        val view = laidOutPageView()
        val calls = intArrayOf(0)
        view.epd = object : EpdRefresher {
            override val available = true
            override fun cleanRefresh(): Boolean { calls[0]++; return true }
        }
        val before = view.fullRefreshCount
        view.fullRefresh()
        assertThat(calls[0]).isEqualTo(1)
        assertThat(view.fullRefreshCount).isEqualTo(before + 1)
    }

    @Test
    fun `fullRefresh falls back without throwing when the EPD refresh is unavailable`() {
        val view = laidOutPageView()
        view.epd = object : EpdRefresher {
            override val available = false
            override fun cleanRefresh(): Boolean = false
        }
        val before = view.fullRefreshCount
        view.fullRefresh() // must not throw; falls back to invalidate()
        assertThat(view.fullRefreshCount).isEqualTo(before + 1)
    }

    private fun dispatch(
        view: PageView, toolType: Int,
        downX: Float, downY: Float, upX: Float, upY: Float,
        moveThrough: Boolean = false,
    ) {
        fun ev(action: Int, x: Float, y: Float): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
            return MotionEvent.obtain(
                0, 0, action, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, 0, 0,
            )
        }
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, downX, downY))
        if (moveThrough) view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, upX, upY))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, upX, upY))
    }

    @Test
    fun `the reserved bottom chrome band covers the foot's full ascent and the progress bar`() {
        // This number is what ReaderPrefs.renderConfig takes out of the text area, so an
        // under-measure means the foot overdraws the last line. It must exceed the foot's own
        // point size (the glyphs alone) plus the bar and its insets beneath them.
        // Stated in density-independent terms: the margin presets are raw px, so comparing this
        // to the 40px narrow preset only means anything at the device's 1.875 density, and
        // Robolectric runs at 1.0. What holds at any density is that the band covers the foot's
        // 13dp of glyphs plus the 12dp of bar and insets sitting under them.
        val view = PageView(context)
        val density = context.resources.displayMetrics.density
        assertThat(view.bottomChromeHeightPx).isAtLeast(((13f + 12f) * density).toInt())
    }

    @Test
    fun `the running foot is scaled by display density, not left in raw pixels`() {
        // Regression: `textSize = 16f * density` written INSIDE a `TextPaint().apply { }` block
        // resolves `density` to the Paint's own member (1.0), not the view's, so the foot silently
        // drew at 16px instead of 16dp. It survived two rounds of enlargement because the bug ate
        // the multiplier each time. Robolectric runs at density 1.0, where the correct and the
        // broken expression give the same number — so this asserts against a forced density
        // instead, which is the only way the mistake is visible in a test.
        val res = context.resources
        val original = res.displayMetrics.density
        try {
            res.displayMetrics.density = 3f
            val view = PageView(context)
            assertThat(view.runningFootSizePxForTest).isEqualTo(39f) // 13dp at the forced density
        } finally {
            res.displayMetrics.density = original
        }
    }

    // --- Landscape spreads -------------------------------------------------------------------

    private val spreadMargin = 20
    private val spreadGap = 40
    private val spreadWidth = 400

    /**
     * A view showing two columns of the same layout: the left page is the first two lines, the right
     * page the next two. Mirrors what ReaderActivity does — one chapter Layout, two consecutive
     * pages out of it, side by side.
     */
    private fun spreadPageView(secondPage: Boolean = true): Triple<PageView, StaticLayout, List<Page>> {
        val view = PageView(context)
        val text = (1..80).joinToString(" ") { "word$it" }
        val columnWidth = (spreadWidth - spreadMargin * 2 - spreadGap) / 2
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, TextPaint().apply { textSize = 12f }, columnWidth)
            .build()
        check(layout.lineCount >= 4) { "need at least four lines to make two pages" }
        val left = Page(
            index = 0, startLine = 0, endLine = 1,
            startOffset = 0, endOffset = layout.getLineEnd(1), topPx = layout.getLineTop(0),
        )
        val right = Page(
            index = 1, startLine = 2, endLine = 3,
            startOffset = layout.getLineStart(2), endOffset = layout.getLineEnd(3),
            topPx = layout.getLineTop(2),
        )
        view.show(layout, left, spreadMargin, secondPage = if (secondPage) right else null, columnGapPx = spreadGap)
        view.measure(
            MeasureSpec.makeMeasureSpec(spreadWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(600, MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return Triple(view, layout, listOf(left, right))
    }

    @Test
    fun `two columns share the content width with the gutter taken out of the middle`() {
        val (view, _, _) = spreadPageView()
        assertThat(view.columnWidth()).isEqualTo((spreadWidth - spreadMargin * 2 - spreadGap) / 2)
        // Both columns and the gutter fit between the margins — the invariant that keeps the right
        // column's text from running off the edge of the panel.
        assertThat(view.columnWidth() * 2 + spreadGap).isAtMost(spreadWidth - spreadMargin * 2)
    }

    @Test
    fun `one column keeps the full content width`() {
        val (view, _, _) = spreadPageView(secondPage = false)
        assertThat(view.columnWidth()).isEqualTo(spreadWidth - spreadMargin * 2)
    }

    @Test
    fun `a touch resolves to the column it landed in, not always the left one`() {
        // The defect this guards: with one shared origin, a pen anywhere on the right column
        // resolves against the LEFT page and highlights text the reader never touched.
        val (view, _, pages) = spreadPageView()
        val y = spreadMargin + 5f

        val leftOffset = view.offsetAt(spreadMargin + 5f, y)
        assertThat(leftOffset).isIn(pages[0].startOffset..pages[0].endOffset)

        val rightColumnX = spreadMargin + view.columnWidth() + spreadGap + 5f
        val rightOffset = view.offsetAt(rightColumnX, y)
        assertThat(rightOffset).isIn(pages[1].startOffset..pages[1].endOffset)
        assertThat(rightOffset).isGreaterThan(leftOffset)
    }

    @Test
    fun `the gutter splits down the middle so a touch in it picks the nearer column`() {
        val (view, _, _) = spreadPageView()
        val gutterLeft = spreadMargin + view.columnWidth()
        assertThat(view.columnIndexAt(gutterLeft + 1f)).isEqualTo(0)
        assertThat(view.columnIndexAt(gutterLeft + spreadGap - 1f)).isEqualTo(1)
    }

    @Test
    fun `with no second page every touch resolves to the only column`() {
        val (view, _, _) = spreadPageView(secondPage = false)
        assertThat(view.columnIndexAt(spreadWidth - 1f)).isEqualTo(0)
    }

    @Test
    fun `the delete chip follows a highlight into the right column`() {
        // caretPointFor has to resolve against whichever column holds the offset; anchoring by the
        // left column's origin would park the chip under the wrong text.
        val (view, _, pages) = spreadPageView()
        val leftPoint = view.caretPointFor(pages[0].startOffset)
        val rightPoint = view.caretPointFor(pages[1].startOffset + 1)
        assertThat(leftPoint).isNotNull()
        assertThat(rightPoint).isNotNull()
        assertThat(rightPoint!!.x).isAtLeast(spreadMargin + view.columnWidth() + spreadGap.toFloat())
    }

    @Test
    fun `an offset on the seam between the two columns anchors in the RIGHT column`() {
        // Page.endOffset is exclusive, so the left page's endOffset IS the right page's
        // startOffset. Resolving that shared offset left-first anchors a highlight starting on the
        // right column's first character under the foot of the LEFT column.
        val (view, _, pages) = spreadPageView()
        val seam = pages[1].startOffset
        check(seam == pages[0].endOffset) { "test needs the pages to actually share a boundary" }
        val point = view.caretPointFor(seam)
        assertThat(point).isNotNull()
        assertThat(point!!.x).isAtLeast(spreadMargin + view.columnWidth() + spreadGap.toFloat())
    }

    @Test
    fun `it draws a two-column spread without error`() {
        // Screencap reads black on e-ink, so a Canvas regression in the second column can only
        // surface here.
        val (view, _, _) = spreadPageView()
        view.setRunningFoot("Chapter One", pageInChapter = 3, pageCount = 12, lastPageInSpread = 4)
        view.draw(Canvas(Bitmap.createBitmap(spreadWidth, 600, Bitmap.Config.ARGB_8888)))
    }

    @Test
    fun `the running foot names both pages of a spread, and one when the right column is blank`() {
        assertThat(runningFootLabel(3, 12, lastPageInSpread = 4)).isEqualTo("pages 3–4 of 12")
        // The last page of an odd-length chapter: the right column is blank, so the foot must not
        // claim a page 13 that is not on screen.
        assertThat(runningFootLabel(12, 12, lastPageInSpread = 12)).isEqualTo("page 12 of 12")
    }
}
