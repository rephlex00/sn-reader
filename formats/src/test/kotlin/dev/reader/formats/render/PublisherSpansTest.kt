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
}
