package dev.reader.formats.render

import android.text.Layout
import com.google.common.truth.Truth.assertThat
import dev.reader.engine.Block
import dev.reader.engine.InlineStyle
import dev.reader.engine.RenderConfig
import dev.reader.engine.StyleSpan
import dev.reader.engine.StyledText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidTextMeasurerTest {

    private val measurer = AndroidTextMeasurer(SpannedChapterBuilder(), TypefaceProvider.Platform)

    private val config = RenderConfig(
        fontFamily = "serif",
        textSizePx = 32f,
        lineSpacingMultiplier = 1.4f,
        marginPx = 40,
        justified = false,
        hyphenated = false,
        viewportWidthPx = 1404,
        viewportHeightPx = 1872,
    )

    private fun para(text: String) = Block.Paragraph(StyledText(text))

    @Test
    fun `wraps long text onto several lines`() {
        val measured = measurer.measure(listOf(para("word ".repeat(400).trim())), config)
        assertThat(measured.lineCount).isGreaterThan(1)
    }

    @Test
    fun `short text occupies a single line`() {
        val measured = measurer.measure(listOf(para("Short.")), config)
        assertThat(measured.lineCount).isEqualTo(1)
    }

    @Test
    fun `line metrics increase monotonically`() {
        val measured = measurer.measure(listOf(para("word ".repeat(400).trim())), config)

        for (line in 1 until measured.lineCount) {
            assertThat(measured.lineTopPx(line)).isAtLeast(measured.lineTopPx(line - 1))
            assertThat(measured.lineBottomPx(line)).isGreaterThan(measured.lineTopPx(line))
            assertThat(measured.lineStartOffset(line)).isAtLeast(measured.lineEndOffset(line - 1) - 1)
        }
    }

    @Test
    fun `total height covers the last line`() {
        val measured = measurer.measure(listOf(para("word ".repeat(400).trim())), config)
        assertThat(measured.totalHeightPx).isAtLeast(measured.lineBottomPx(measured.lineCount - 1))
    }

    @Test
    fun `a hard page break resolves to a line boundary`() {
        val measured = measurer.measure(
            listOf(para("Before the break."), Block.PageBreak, para("After the break.")),
            config,
        )
        val breakLines = (0 until measured.lineCount).filter { measured.isBreakBefore(it) }

        assertThat(breakLines).hasSize(1)
        assertThat(breakLines.single()).isGreaterThan(0)
    }

    @Test
    fun `a chapter without breaks reports none`() {
        val measured = measurer.measure(listOf(para("No breaks here.")), config)
        assertThat((0 until measured.lineCount).none { measured.isBreakBefore(it) }).isTrue()
    }

    @Test
    fun `larger text yields more lines for the same content`() {
        val text = listOf(para("word ".repeat(200).trim()))
        val small = measurer.measure(text, config)
        val large = measurer.measure(text, config.copy(textSizePx = 64f))

        assertThat(large.lineCount).isGreaterThan(small.lineCount)
    }

    @Test
    fun `a publisher size ratio enlarges the run only when styling is on`() {
        // A run scaled to 3x spans more lines when honored; when the toggle is off the
        // RelativeSizeSpan is never created, so the same content stays compact. This proves
        // the publisher span flows all the way through StaticLayout measurement.
        val blocks = listOf(
            Block.Paragraph(
                StyledText(
                    "word ".repeat(120).trim(),
                    listOf(StyleSpan(0, 200, InlineStyle(sizeRatio = 3f))),
                ),
            ),
        )
        val on = measurer.measure(blocks, config.copy(publisherStyling = true))
        val off = measurer.measure(blocks, config.copy(publisherStyling = false))

        assertThat(on.lineCount).isGreaterThan(off.lineCount)
    }

    @Test
    fun `an empty chapter measures to no lines of content`() {
        val measured = measurer.measure(emptyList(), config)
        assertThat(measured.lineCount).isEqualTo(1) // StaticLayout always reports one line
        assertThat(measured.lineEndOffset(0)).isEqualTo(0)
    }

    @Test
    fun `justified text uses the high-quality break strategy so it hyphenates and tightens`() {
        val measured = measurer.measure(
            listOf(para("word ".repeat(80).trim())),
            config.copy(justified = true, hyphenated = true),
        ) as AndroidMeasuredChapter
        assertThat(measured.layout.breakStrategy).isEqualTo(Layout.BREAK_STRATEGY_HIGH_QUALITY)
    }
}
