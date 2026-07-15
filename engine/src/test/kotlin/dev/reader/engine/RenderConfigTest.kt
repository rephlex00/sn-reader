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
    }

    @Test
    fun `locators order by spine then offset`() {
        assertThat(Locator(0, 500)).isLessThan(Locator(1, 0))
        assertThat(Locator(1, 100)).isLessThan(Locator(1, 200))
    }
}
