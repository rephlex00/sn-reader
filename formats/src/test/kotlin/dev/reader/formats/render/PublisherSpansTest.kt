package dev.reader.formats.render

import android.graphics.Paint
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import com.google.common.truth.Truth.assertThat
import dev.reader.engine.Block
import dev.reader.engine.BlockStyle
import dev.reader.engine.RenderConfig
import dev.reader.engine.StyledText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PublisherSpansTest {

    private val builder = SpannedChapterBuilder()

    private val config = RenderConfig(
        fontFamily = "serif",
        textSizePx = 32f,
        lineSpacingMultiplier = 1.4f,
        marginPx = 40,
        justified = true,
        hyphenated = true,
        viewportWidthPx = 1404,
        viewportHeightPx = 1872,
        publisherStyling = true,
    )

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
    fun `a negative text-indent never produces a negative leading margin`() {
        // The hanging-indent idiom: padding-left compensates in a real CSS engine, but this
        // renderer ignores padding, so an honoured negative indent clips the first line's
        // opening glyphs off the left edge of the content box.
        val style = BlockStyle(textIndentEm = -1.5f)
        val chapter = builder.build(listOf(Block.Paragraph(StyledText("A line."), style)), config)

        val spans = chapter.text.getSpans(0, chapter.text.length, LeadingMarginSpan.Standard::class.java)
        assertThat(spans.map { it.getLeadingMargin(true) }.filter { it < 0 }).isEmpty()
    }
}
