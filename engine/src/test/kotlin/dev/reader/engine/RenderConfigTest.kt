package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RenderConfigTest {

    private fun config(margin: Int = 40) = RenderConfig(
        fontFamily = "serif",
        textSizePx = 32f,
        lineSpacingMultiplier = 1.4f,
        marginPx = margin,
        justified = true,
        hyphenated = true,
        viewportWidthPx = 1404,
        viewportHeightPx = 1872,
    )

    @Test
    fun `content box subtracts margins from both edges`() {
        val c = config(margin = 40)
        assertThat(c.contentWidthPx).isEqualTo(1404 - 80)
        assertThat(c.contentHeightPx).isEqualTo(1872 - 80)
    }

    @Test
    fun `rejects margins that leave no content`() {
        val e = runCatching { config(margin = 800) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("margins leave no content width")
    }

    @Test
    fun `rejects zero or negative line spacing multiplier`() {
        val e = runCatching {
            RenderConfig(
                fontFamily = "serif",
                textSizePx = 32f,
                lineSpacingMultiplier = 0f,
                marginPx = 40,
                justified = true,
                hyphenated = true,
                viewportWidthPx = 1404,
                viewportHeightPx = 1872,
            )
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("lineSpacingMultiplier")
        assertThat(e).hasMessageThat().contains("0.0")
    }

    @Test
    fun `rejects blank fontFamily`() {
        val e = runCatching {
            RenderConfig(
                fontFamily = "  ",
                textSizePx = 32f,
                lineSpacingMultiplier = 1.4f,
                marginPx = 40,
                justified = true,
                hyphenated = true,
                viewportWidthPx = 1404,
                viewportHeightPx = 1872,
            )
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("fontFamily")
    }

    @Test
    fun `publisher styling defaults to on`() {
        assertThat(config().publisherStyling).isTrue()
    }

    @Test
    fun `publisher styling can be turned off without touching other fields`() {
        val off = config().copy(publisherStyling = false)
        assertThat(off.publisherStyling).isFalse()
        assertThat(off.inferHeadings).isTrue()
    }

    @Test
    fun `one column is the default and reports the full content width`() {
        assertThat(config().columnCount).isEqualTo(1)
        assertThat(config().contentWidthPx).isEqualTo(1404 - 80)
    }

    @Test
    fun `two columns split the content width and lose the gap between them`() {
        // The landscape Nomad viewport: 1872 wide, 1404 tall.
        val c = config().copy(viewportWidthPx = 1872, viewportHeightPx = 1404, columnCount = 2)
        assertThat(c.contentWidthPx).isEqualTo((1872 - 80 - COLUMN_GAP_PX) / 2)
        // Two columns plus the gap must fit between the margins with nothing to spare but the
        // truncated remainder — this is the arithmetic PageView has to agree with to place column 2.
        val used = c.contentWidthPx * 2 + COLUMN_GAP_PX
        assertThat(used).isAtMost(1872 - 80)
        assertThat(1872 - 80 - used).isLessThan(2)
    }

    @Test
    fun `columns stay equal when the width does not divide evenly`() {
        // An odd inner width truncates; the pixel is dropped rather than widening one column, which
        // would be visible where an unused pixel at the right margin is not.
        val c = config(margin = 0).copy(viewportWidthPx = 1001, columnCount = 2, columnGapPx = 0)
        assertThat(c.contentWidthPx).isEqualTo(500)
    }

    @Test
    fun `column count follows the viewport's shape`() {
        assertThat(columnCountFor(1404, 1872)).isEqualTo(1) // portrait
        assertThat(columnCountFor(1872, 1404)).isEqualTo(2) // landscape
        assertThat(columnCountFor(1000, 1000)).isEqualTo(1) // square falls back to one
    }

    @Test
    fun `rejects a gap that leaves no room for the columns`() {
        val e = runCatching {
            config().copy(viewportWidthPx = 1872, columnCount = 2, columnGapPx = 1872)
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("margins leave no content width")
    }

    @Test
    fun `column count keys the pagination cache`() {
        // RenderConfig identity is what EpubDocument caches paginated chapters against, so a
        // rotation has to make an unequal config or the reader would draw portrait pages in columns.
        assertThat(config().copy(columnCount = 2)).isNotEqualTo(config())
    }

    @Test
    fun `locators order by spine then offset`() {
        assertThat(Locator(0, 500)).isLessThan(Locator(1, 0))
        assertThat(Locator(1, 100)).isLessThan(Locator(1, 200))
    }
}
