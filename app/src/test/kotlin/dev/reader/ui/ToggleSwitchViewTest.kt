package dev.reader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View.MeasureSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToggleSwitchViewTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `checked is a plain settable display flag defaulting off`() {
        val view = ToggleSwitchView(context)
        assertThat(view.checked).isFalse()
        view.checked = true
        assertThat(view.checked).isTrue()
    }

    @Test
    fun `it measures to a fixed switch size under an unspecified spec`() {
        val view = ToggleSwitchView(context)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        // 52x30dp at the test density (1.0) — a real, non-zero switch, wider than tall.
        assertThat(view.measuredWidth).isGreaterThan(0)
        assertThat(view.measuredHeight).isGreaterThan(0)
        assertThat(view.measuredWidth).isGreaterThan(view.measuredHeight)
    }

    @Test
    fun `it draws without error in both states`() {
        // The on/off draw paths differ (track fill, thumb side/colour); exercise both so a Canvas
        // regression surfaces here rather than only on the panel, which screencaps black on e-ink.
        val view = ToggleSwitchView(context)
        val spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        view.measure(spec, spec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val canvas = Canvas(Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888))

        view.checked = false
        view.draw(canvas)
        view.checked = true
        view.draw(canvas)
    }
}
