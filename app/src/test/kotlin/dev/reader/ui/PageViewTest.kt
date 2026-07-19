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
    fun `progress defaults to null and is settable`() {
        val view = PageView(context)
        assertThat(view.progress).isNull()
        view.setProgress(0.5f)
        assertThat(view.progress).isEqualTo(0.5f)
        view.setProgress(null)
        assertThat(view.progress).isNull()
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
}
