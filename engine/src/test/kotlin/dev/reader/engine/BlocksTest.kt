package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlocksTest {

    // -- StyleSpan --

    @Test
    fun `rejects negative start`() {
        val e = runCatching { StyleSpan(-1, 3, InlineStyle(bold = true)) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("-1")
    }

    @Test
    fun `rejects end equal to start`() {
        val e = runCatching { StyleSpan(2, 2, InlineStyle(italic = true)) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("2")
    }

    @Test
    fun `rejects end before start`() {
        val e = runCatching { StyleSpan(5, 2, InlineStyle(monospace = true)) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("5")
    }

    @Test
    fun `accepts a valid span`() {
        val span = StyleSpan(0, 3, InlineStyle(bold = true))
        assertThat(span.start).isEqualTo(0)
        assertThat(span.end).isEqualTo(3)
    }

    // -- InlineStyle / BlockStyle: null-means-unspecified defaults --

    @Test
    fun `InlineStyle defaults every field to null`() {
        val s = InlineStyle()
        assertThat(s.bold).isNull()
        assertThat(s.italic).isNull()
        assertThat(s.monospace).isNull()
        assertThat(s.sizeRatio).isNull()
        assertThat(s.underline).isNull()
        assertThat(s.strikethrough).isNull()
        assertThat(s.letterSpacingEm).isNull()
        assertThat(s.grayLevel).isNull()
    }

    @Test
    fun `InlineStyle setting one field leaves the rest null`() {
        val s = InlineStyle(italic = true)
        assertThat(s.italic).isTrue()
        assertThat(s.bold).isNull()
        assertThat(s.monospace).isNull()
    }

    @Test
    fun `BlockStyle defaults every field to null`() {
        val s = BlockStyle()
        assertThat(s.align).isNull()
        assertThat(s.marginTopEm).isNull()
        assertThat(s.marginBottomEm).isNull()
        assertThat(s.textIndentEm).isNull()
        assertThat(s.lineHeightMultiplier).isNull()
    }

    @Test
    fun `TextAlign has the four publisher alignments`() {
        assertThat(TextAlign.entries)
            .containsExactly(TextAlign.LEFT, TextAlign.RIGHT, TextAlign.CENTER, TextAlign.JUSTIFY)
    }

    // -- Block text-style default --

    @Test
    fun `text blocks default to an all-null BlockStyle`() {
        assertThat(Block.Paragraph(StyledText("a")).style).isEqualTo(BlockStyle())
        assertThat(Block.Heading(1, StyledText("a")).style).isEqualTo(BlockStyle())
        assertThat(Block.Quote(StyledText("a")).style).isEqualTo(BlockStyle())
        assertThat(Block.ListItem(StyledText("a")).style).isEqualTo(BlockStyle())
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
            StyledText("hi", listOf(StyleSpan(0, 900, InlineStyle(bold = true))))
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("900")
        assertThat(e).hasMessageThat().contains("2")
    }

    @Test
    fun `accepts a span exactly reaching text length`() {
        val text = StyledText("hi", listOf(StyleSpan(0, 2, InlineStyle(bold = true))))
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

    @Test
    fun `image equality and hashCode ignore the resolved bytes`() {
        // The bytes are a resolved attachment, not part of the image's identity — the href
        // names the image. A ByteArray in a data class would compare by array identity and
        // break this; the override compares by href only. Two Images with the same href but
        // different (or absent) bytes must be equal and hash alike, so existing Block-equality
        // tests that construct Images by href stay green once readBlocks attaches bytes.
        val a = Block.Image("images/x.png", bytes = byteArrayOf(1, 2, 3))
        val b = Block.Image("images/x.png", bytes = byteArrayOf(4, 5, 6))
        val c = Block.Image("images/x.png")

        assertThat(a).isEqualTo(b)
        assertThat(a).isEqualTo(c)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a.hashCode()).isEqualTo(c.hashCode())
        assertThat(Block.Image("images/y.png")).isNotEqualTo(a)
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
