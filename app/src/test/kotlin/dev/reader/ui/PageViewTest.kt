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
