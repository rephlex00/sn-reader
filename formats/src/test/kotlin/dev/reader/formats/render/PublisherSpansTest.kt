package dev.reader.formats.render

import android.graphics.Paint
import android.text.TextPaint
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PublisherSpansTest {

    @Test
    fun `letter spacing span sets the paint letter spacing in em`() {
        val paint = TextPaint()
        LetterSpacingSpan(0.25f).updateMeasureState(paint)
        assertThat(paint.letterSpacing).isEqualTo(0.25f)
    }

    @Test
    fun `letter spacing span mutates the draw state too`() {
        val paint = TextPaint()
        LetterSpacingSpan(0.1f).updateDrawState(paint)
        assertThat(paint.letterSpacing).isEqualTo(0.1f)
    }

    @Test
    fun `line height span scales the height to round(multiplier times font height)`() {
        val fm = Paint.FontMetricsInt().apply { ascent = -80; descent = 20 } // natural height 100
        MultiplierLineHeightSpan(1.5f).chooseHeight("x", 0, 1, 0, 0, fm)
        assertThat(fm.descent - fm.ascent).isEqualTo(150) // round(1.5 * 100)
    }

    @Test
    fun `line height span rounds a fractional target`() {
        val fm = Paint.FontMetricsInt().apply { ascent = -70; descent = 30 } // natural height 100
        MultiplierLineHeightSpan(1.234f).chooseHeight("x", 0, 1, 0, 0, fm)
        assertThat(fm.descent - fm.ascent).isEqualTo(123) // round(1.234 * 100)
    }

    @Test
    fun `line height span leaves an empty line untouched`() {
        val fm = Paint.FontMetricsInt().apply { ascent = 0; descent = 0 }
        MultiplierLineHeightSpan(2f).chooseHeight("", 0, 0, 0, 0, fm)
        assertThat(fm.descent - fm.ascent).isEqualTo(0)
    }
}
