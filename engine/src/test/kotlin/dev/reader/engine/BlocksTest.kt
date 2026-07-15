package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlocksTest {

    // -- StyleSpan --

    @Test
    fun `rejects negative start`() {
        val e = runCatching { StyleSpan(-1, 3, InlineStyle.BOLD) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("-1")
    }

    @Test
    fun `rejects end equal to start`() {
        val e = runCatching { StyleSpan(2, 2, InlineStyle.ITALIC) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("2")
    }

    @Test
    fun `rejects end before start`() {
        val e = runCatching { StyleSpan(5, 2, InlineStyle.MONOSPACE) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("5")
    }

    @Test
    fun `accepts a valid span`() {
        val span = StyleSpan(0, 3, InlineStyle.BOLD)
        assertThat(span.start).isEqualTo(0)
        assertThat(span.end).isEqualTo(3)
    }

    // -- Block.Heading --

    @Test
    fun `accepts boundary heading levels 1 and 6`() {
        val h1 = Block.Heading(1, StyledText("a"))
        val h6 = Block.Heading(6, StyledText("b"))
        assertThat(h1.level).isEqualTo(1)
        assertThat(h6.level).isEqualTo(6)
    }

    @Test
    fun `rejects heading level 0`() {
        val e = runCatching { Block.Heading(0, StyledText("a")) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("0")
    }

    @Test
    fun `rejects heading level 7`() {
        val e = runCatching { Block.Heading(7, StyledText("a")) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("7")
    }

    // -- StyledText --

    @Test
    fun `rejects a span past the end of the text`() {
        val e = runCatching {
            StyledText("hi", listOf(StyleSpan(0, 900, InlineStyle.BOLD)))
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("900")
        assertThat(e).hasMessageThat().contains("2")
    }

    @Test
    fun `accepts a span exactly reaching text length`() {
        val text = StyledText("hi", listOf(StyleSpan(0, 2, InlineStyle.BOLD)))
        assertThat(text.spans.single().end).isEqualTo(2)
    }

    // -- Block.Image --

    @Test
    fun `rejects blank image href`() {
        val e = runCatching { Block.Image("   ") }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("href")
    }

    @Test
    fun `accepts a non-blank image href`() {
        val image = Block.Image("images/cover.jpg")
        assertThat(image.href).isEqualTo("images/cover.jpg")
    }

    // -- Locator --

    @Test
    fun `rejects negative spineIndex`() {
        val e = runCatching { Locator(-1, 0) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("-1")
    }

    @Test
    fun `rejects negative charOffset`() {
        val e = runCatching { Locator(0, -1) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("-1")
    }
}
