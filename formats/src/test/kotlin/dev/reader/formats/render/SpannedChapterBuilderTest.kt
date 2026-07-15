package dev.reader.formats.render

import android.graphics.Typeface
import android.text.Spanned
import com.google.common.truth.Truth.assertThat
import dev.reader.engine.Block
import dev.reader.engine.InlineStyle
import dev.reader.engine.RenderConfig
import dev.reader.engine.StyleSpan
import dev.reader.engine.StyledText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.text.style.StyleSpan as AndroidStyleSpan

@RunWith(RobolectricTestRunner::class)
class SpannedChapterBuilderTest {

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
    )

    private fun para(text: String, spans: List<StyleSpan> = emptyList()) =
        Block.Paragraph(StyledText(text, spans))

    @Test
    fun `joins blocks with a blank line between them`() {
        val chapter = builder.build(listOf(para("One."), para("Two.")), config)
        assertThat(chapter.text.toString()).isEqualTo("One.\n\nTwo.")
    }

    @Test
    fun `maps a bold run onto an android style span`() {
        val chapter = builder.build(
            listOf(para("A bold word.", listOf(StyleSpan(2, 6, InlineStyle.BOLD)))),
            config,
        )
        val spans = chapter.text.getSpans(0, chapter.text.length, AndroidStyleSpan::class.java)

        assertThat(spans).hasLength(1)
        assertThat(spans[0].style).isEqualTo(Typeface.BOLD)
        assertThat(chapter.text.getSpanStart(spans[0])).isEqualTo(2)
        assertThat(chapter.text.getSpanEnd(spans[0])).isEqualTo(6)
    }

    @Test
    fun `offsets spans of later blocks by the preceding text`() {
        val chapter = builder.build(
            listOf(para("One."), para("A bold word.", listOf(StyleSpan(2, 6, InlineStyle.BOLD)))),
            config,
        )
        val span = chapter.text.getSpans(0, chapter.text.length, AndroidStyleSpan::class.java).single()

        // "One." + "\n\n" = 6 characters precede the second block.
        assertThat(chapter.text.getSpanStart(span)).isEqualTo(8)
        assertThat(chapter.text.getSpanEnd(span)).isEqualTo(12)
    }

    @Test
    fun `records page break offsets rather than emitting text`() {
        val chapter = builder.build(listOf(para("One."), Block.PageBreak, para("Two.")), config)

        assertThat(chapter.text.toString()).isEqualTo("One.\n\nTwo.")
        assertThat(chapter.breakOffsets).containsExactly(6)
    }

    @Test
    fun `headings are scaled relative to body text`() {
        val chapter = builder.build(
            listOf(Block.Heading(1, StyledText("Title"))),
            config,
        )
        val spans = chapter.text.getSpans(0, chapter.text.length, android.text.style.RelativeSizeSpan::class.java)

        assertThat(spans).hasLength(1)
        assertThat(spans[0].sizeChange).isGreaterThan(1f)
    }

    @Test
    fun `ordered list items are prefixed with their ordinal`() {
        val chapter = builder.build(
            listOf(Block.ListItem(StyledText("First"), ordinal = 1)),
            config,
        )
        assertThat(chapter.text.toString()).isEqualTo("1. First")
    }

    @Test
    fun `unordered list items are prefixed with a bullet`() {
        val chapter = builder.build(
            listOf(Block.ListItem(StyledText("First"), ordinal = null)),
            config,
        )
        assertThat(chapter.text.toString()).isEqualTo("• First")
    }

    @Test
    fun `list ordinal prefix shifts the item's spans`() {
        val chapter = builder.build(
            listOf(Block.ListItem(StyledText("bold", listOf(StyleSpan(0, 4, InlineStyle.BOLD))), 1)),
            config,
        )
        val span = chapter.text.getSpans(0, chapter.text.length, AndroidStyleSpan::class.java).single()

        // "1. " is 3 characters.
        assertThat(chapter.text.getSpanStart(span)).isEqualTo(3)
    }

    @Test
    fun `quotes are indented with a leading margin`() {
        val chapter = builder.build(listOf(Block.Quote(StyledText("Quoted."))), config)
        val spans = chapter.text.getSpans(
            0, chapter.text.length, android.text.style.LeadingMarginSpan.Standard::class.java,
        )
        assertThat(spans).hasLength(1)
    }

    @Test
    fun `empty block list yields empty text`() {
        val chapter = builder.build(emptyList(), config)

        assertThat(chapter.text.toString()).isEmpty()
        assertThat(chapter.breakOffsets).isEmpty()
    }

    @Test
    fun `text is spanned`() {
        val chapter = builder.build(listOf(para("Hi.")), config)
        assertThat(chapter.text).isInstanceOf(Spanned::class.java)
    }
}
