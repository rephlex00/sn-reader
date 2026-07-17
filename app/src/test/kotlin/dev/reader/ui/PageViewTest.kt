package dev.reader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextPaint
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
}
