package dev.reader.formats.render

import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import com.google.common.truth.Truth.assertThat
import dev.reader.engine.Block
import dev.reader.engine.BlockStyle
import dev.reader.engine.InlineStyle
import dev.reader.engine.RenderConfig
import dev.reader.engine.StyleSpan
import dev.reader.engine.StyledText
import dev.reader.engine.TextAlign
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
            listOf(para("A bold word.", listOf(StyleSpan(2, 6, InlineStyle(bold = true))))),
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
            listOf(para("One."), para("A bold word.", listOf(StyleSpan(2, 6, InlineStyle(bold = true))))),
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

    // --- Fix wave A, M4: a PageBreak followed by a text-free block (an Image, which
    // appends no text) must pin the break to the start of the NEXT text-bearing block,
    // not to the blank separator line — and a trailing break with no following text
    // must contribute no break at all. ---

    @Test
    fun `a page break followed by an image pins to the next text-bearing block`() {
        val chapter = builder.build(
            listOf(para("One."), Block.PageBreak, Block.Image("img/x.png"), para("Two.")),
            config,
        )

        // "One." + "\n\n" (image's separator) + "\n\n" (Two's separator) = offset 8.
        assertThat(chapter.breakOffsets).containsExactly(8)
        val offset = chapter.breakOffsets.single()
        assertThat(chapter.text.subSequence(offset, chapter.text.length).toString()).isEqualTo("Two.")
    }

    @Test
    fun `a trailing page break followed only by an image contributes no break`() {
        val chapter = builder.build(
            listOf(para("One."), Block.PageBreak, Block.Image("img/x.png")),
            config,
        )

        assertThat(chapter.breakOffsets).isEmpty()
    }

    @Test
    fun `a trailing page break with nothing after it contributes no break`() {
        val chapter = builder.build(listOf(para("One."), Block.PageBreak), config)

        assertThat(chapter.breakOffsets).isEmpty()
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
            listOf(Block.ListItem(StyledText("bold", listOf(StyleSpan(0, 4, InlineStyle(bold = true)))), 1)),
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

    // --- Publisher styling: the toggle-off no-op regression proof (the gate) ---

    /** A full, order-insensitive description of a Spanned: text plus every span's type,
     *  range, flags and identifying value. Two Spanneds with identical descriptions render
     *  identically. */
    private fun describe(text: Spanned): List<String> =
        text.getSpans(0, text.length, Any::class.java).map { s ->
            val detail = when (s) {
                is RelativeSizeSpan -> "size=${s.sizeChange}"
                is AndroidStyleSpan -> "emphasis=${s.style}"
                is android.text.style.TypefaceSpan -> "family=${s.family}"
                is ForegroundColorSpan -> "color=${s.foregroundColor}"
                is LeadingMarginSpan.Standard -> "margin=${s.getLeadingMargin(true)}"
                is AlignmentSpan.Standard -> "align=${s.alignment}"
                is LetterSpacingSpan -> "letterSpacing"
                is MultiplierLineHeightSpan -> "lineHeight"
                is UnderlineSpan -> "underline"
                is StrikethroughSpan -> "strike"
                else -> s.javaClass.simpleName
            }
            "${s.javaClass.simpleName}|${text.getSpanStart(s)}|${text.getSpanEnd(s)}|" +
                "${text.getSpanFlags(s)}|$detail"
        }.sorted()

    @Test
    fun `publisher styling off reproduces the reader-only rendering exactly`() {
        // Blocks that carry populated publisher fields on every axis: inline sizeRatio,
        // underline, gray, letter-spacing, strikethrough; block align, indent, line-height,
        // margins; a heading with its own publisher size; a page break.
        val rich = listOf(
            Block.Heading(
                1,
                StyledText("Title", listOf(StyleSpan(0, 5, InlineStyle(sizeRatio = 1.29f)))),
                style = BlockStyle(align = TextAlign.CENTER, marginTopEm = 2f, lineHeightMultiplier = 1.5f),
            ),
            Block.PageBreak,
            Block.Paragraph(
                StyledText(
                    "A mix of bold and gray now.",
                    listOf(
                        StyleSpan(2, 6, InlineStyle(bold = true)),
                        StyleSpan(10, 14, InlineStyle(underline = true)),
                        StyleSpan(10, 14, InlineStyle(grayLevel = 0.5f)),
                        StyleSpan(19, 23, InlineStyle(letterSpacingEm = 0.1f)),
                    ),
                ),
                style = BlockStyle(align = TextAlign.RIGHT, textIndentEm = 1.5f, marginBottomEm = 1f),
            ),
            Block.Quote(
                StyledText("Quoted italics.", listOf(StyleSpan(0, 6, InlineStyle(italic = true)))),
                style = BlockStyle(align = TextAlign.LEFT, lineHeightMultiplier = 2f),
            ),
            Block.ListItem(
                StyledText("Item", listOf(StyleSpan(0, 4, InlineStyle(strikethrough = true)))),
                ordinal = 1,
                style = BlockStyle(align = TextAlign.CENTER),
            ),
        )
        // Golden: the same structure with every publisher-only field stripped, keeping only
        // the bold/italic emphasis the reader has always honored. It carries no publisher
        // fields, so it renders identically whether styling is on or off — it is today's output.
        val golden = listOf(
            Block.Heading(1, StyledText("Title")),
            Block.PageBreak,
            Block.Paragraph(
                StyledText("A mix of bold and gray now.", listOf(StyleSpan(2, 6, InlineStyle(bold = true)))),
            ),
            Block.Quote(StyledText("Quoted italics.", listOf(StyleSpan(0, 6, InlineStyle(italic = true))))),
            Block.ListItem(StyledText("Item"), ordinal = 1),
        )

        val off = builder.build(rich, config.copy(publisherStyling = false))
        val today = builder.build(golden, config.copy(publisherStyling = false))

        assertThat(off.text.toString()).isEqualTo(today.text.toString())
        assertThat(describe(off.text)).isEqualTo(describe(today.text))
        assertThat(off.breakOffsets).isEqualTo(today.breakOffsets)
    }

    @Test
    fun `populated publisher fields produce no spans when styling is off`() {
        val chapter = builder.build(
            listOf(
                Block.Paragraph(
                    StyledText(
                        "Word here now.",
                        listOf(
                            StyleSpan(
                                0, 4,
                                InlineStyle(
                                    sizeRatio = 2f, underline = true, strikethrough = true,
                                    letterSpacingEm = 0.2f, grayLevel = 0.3f,
                                ),
                            ),
                        ),
                    ),
                    style = BlockStyle(align = TextAlign.CENTER, textIndentEm = 2f, lineHeightMultiplier = 1.5f),
                ),
            ),
            config.copy(publisherStyling = false),
        )
        assertThat(chapter.text.getSpans(0, chapter.text.length, Any::class.java)).isEmpty()
    }

    // --- Publisher styling on: inline fields each map to their span ---

    private fun paraSpan(style: InlineStyle) =
        builder.build(listOf(para("Word.", listOf(StyleSpan(0, 4, style)))), config).text

    @Test
    fun `sizeRatio maps to a relative size span when on`() {
        val text = paraSpan(InlineStyle(sizeRatio = 1.5f))
        val span = text.getSpans(0, text.length, RelativeSizeSpan::class.java).single()
        assertThat(span.sizeChange).isEqualTo(1.5f)
    }

    @Test
    fun `underline maps to an underline span when on`() {
        val text = paraSpan(InlineStyle(underline = true))
        assertThat(text.getSpans(0, text.length, UnderlineSpan::class.java)).hasLength(1)
    }

    @Test
    fun `strikethrough maps to a strikethrough span when on`() {
        val text = paraSpan(InlineStyle(strikethrough = true))
        assertThat(text.getSpans(0, text.length, StrikethroughSpan::class.java)).hasLength(1)
    }

    @Test
    fun `letter spacing maps to a letter-spacing span when on`() {
        val text = paraSpan(InlineStyle(letterSpacingEm = 0.15f))
        assertThat(text.getSpans(0, text.length, LetterSpacingSpan::class.java)).hasLength(1)
    }

    @Test
    fun `gray level maps to a gray foreground color when on`() {
        val text = paraSpan(InlineStyle(grayLevel = 0.5f))
        val span = text.getSpans(0, text.length, ForegroundColorSpan::class.java).single()
        val g = Math.round(0.5f * 255f)
        assertThat(span.foregroundColor).isEqualTo(android.graphics.Color.rgb(g, g, g))
    }

    @Test
    fun `a multi-property run yields one span per property over the same range`() {
        // Task 3 emits one single-field span per property; all three must apply.
        val text = builder.build(
            listOf(
                para(
                    "Word.",
                    listOf(
                        StyleSpan(0, 4, InlineStyle(bold = true)),
                        StyleSpan(0, 4, InlineStyle(underline = true)),
                        StyleSpan(0, 4, InlineStyle(grayLevel = 0.2f)),
                    ),
                ),
            ),
            config,
        ).text
        assertThat(text.getSpans(0, text.length, AndroidStyleSpan::class.java)).hasLength(1)
        assertThat(text.getSpans(0, text.length, UnderlineSpan::class.java)).hasLength(1)
        assertThat(text.getSpans(0, text.length, ForegroundColorSpan::class.java)).hasLength(1)
    }

    // --- Publisher styling on: block fields ---

    private fun paraBlock(style: BlockStyle) =
        builder.build(listOf(Block.Paragraph(StyledText("Body text."), style = style)), config).text

    @Test
    fun `right alignment maps to an opposite alignment span when on`() {
        val text = paraBlock(BlockStyle(align = TextAlign.RIGHT))
        val span = text.getSpans(0, text.length, AlignmentSpan.Standard::class.java).single()
        assertThat(span.alignment).isEqualTo(Layout.Alignment.ALIGN_OPPOSITE)
    }

    @Test
    fun `center alignment maps to a center alignment span when on`() {
        val text = paraBlock(BlockStyle(align = TextAlign.CENTER))
        assertThat(text.getSpans(0, text.length, AlignmentSpan.Standard::class.java).single().alignment)
            .isEqualTo(Layout.Alignment.ALIGN_CENTER)
    }

    @Test
    fun `justify alignment emits no alignment span - it is a whole-chapter setting`() {
        val text = paraBlock(BlockStyle(align = TextAlign.JUSTIFY))
        assertThat(text.getSpans(0, text.length, AlignmentSpan.Standard::class.java)).isEmpty()
    }

    @Test
    fun `null alignment falls back to the reader default with no alignment span`() {
        // config.justified is true here; the reader's whole-chapter justification governs.
        val text = paraBlock(BlockStyle(align = null))
        assertThat(text.getSpans(0, text.length, AlignmentSpan.Standard::class.java)).isEmpty()
    }

    @Test
    fun `text indent maps to a first-line leading margin when on`() {
        val text = paraBlock(BlockStyle(textIndentEm = 2f))
        val span = text.getSpans(0, text.length, LeadingMarginSpan.Standard::class.java).single()
        // first = round(2 * textSizePx=32) = 64; rest = 0.
        assertThat(span.getLeadingMargin(true)).isEqualTo(64)
        assertThat(span.getLeadingMargin(false)).isEqualTo(0)
    }

    @Test
    fun `line height multiplier maps to a line-height span when on`() {
        val text = paraBlock(BlockStyle(lineHeightMultiplier = 1.8f))
        assertThat(text.getSpans(0, text.length, MultiplierLineHeightSpan::class.java)).hasLength(1)
    }

    @Test
    fun `null line height leaves the block to the reader line spacing`() {
        val text = paraBlock(BlockStyle(lineHeightMultiplier = null))
        assertThat(text.getSpans(0, text.length, MultiplierLineHeightSpan::class.java)).isEmpty()
    }

    // --- Heading double-enlarge: publisher size wins over the semantic scale ---

    @Test
    fun `a heading with a publisher size renders at that size, not size times scale`() {
        val text = builder.build(
            listOf(Block.Heading(1, StyledText("Title", listOf(StyleSpan(0, 5, InlineStyle(sizeRatio = 1.29f)))))),
            config,
        ).text
        val sizes = text.getSpans(0, text.length, RelativeSizeSpan::class.java)
        // Exactly one size span — the publisher's — and the semantic HEADING_SCALE is skipped.
        assertThat(sizes).hasLength(1)
        assertThat(sizes.single().sizeChange).isEqualTo(1.29f)
        // The heading stays bold.
        assertThat(text.getSpans(0, text.length, AndroidStyleSpan::class.java).single().style)
            .isEqualTo(Typeface.BOLD)
    }

    @Test
    fun `a heading with a publisher size still uses the semantic scale when styling is off`() {
        val text = builder.build(
            listOf(Block.Heading(1, StyledText("Title", listOf(StyleSpan(0, 5, InlineStyle(sizeRatio = 1.29f)))))),
            config.copy(publisherStyling = false),
        ).text
        val size = text.getSpans(0, text.length, RelativeSizeSpan::class.java).single()
        // The semantic level-1 scale (1.6), not the ignored publisher 1.29.
        assertThat(size.sizeChange).isEqualTo(1.6f)
    }
}
