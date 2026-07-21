package dev.reader.formats.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
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
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import android.text.style.StyleSpan as AndroidStyleSpan

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
    fun `joins two consecutive body paragraphs with a single newline`() {
        // Fix 1: two flowing body paragraphs are joined by a single newline (paragraph gap
        // equals line spacing) rather than a blank line.
        val chapter = builder.build(listOf(para("One."), para("Two.")), config)
        assertThat(chapter.text.toString()).isEqualTo("One.\nTwo.")
    }

    @Test
    fun `a heading followed by a paragraph keeps the blank line`() {
        val chapter = builder.build(listOf(Block.Heading(1, StyledText("Title")), para("Body.")), config)
        // Task 4: the chapter opens with a heading (the chapter title), so it now gets a
        // prepended blank line of headroom ("\n\n") before "Title" in addition to the
        // existing blank line before "Body.".
        assertThat(chapter.text.toString()).isEqualTo("\n\nTitle\n\nBody.")
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

        // "One." + "\n" (two consecutive body paragraphs join with a single newline) = 5
        // characters precede the second block.
        assertThat(chapter.text.getSpanStart(span)).isEqualTo(7)
        assertThat(chapter.text.getSpanEnd(span)).isEqualTo(11)
    }

    @Test
    fun `records page break offsets rather than emitting text`() {
        val chapter = builder.build(listOf(para("One."), Block.PageBreak, para("Two.")), config)

        // Both are flowing body paragraphs, so the separator is a single newline even across
        // the page break — the break offset still pins to "Two."'s own first character.
        assertThat(chapter.text.toString()).isEqualTo("One.\nTwo.")
        assertThat(chapter.breakOffsets).containsExactly(5)
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

        // "One." + "\n\n" (image's separator; the image is not a paragraph) + "" (the
        // image's null bytes append no text) + "\n" (both "One." and "Two." are flowing
        // body paragraphs, so they still join with a single newline across the text-free
        // image) = offset 7.
        assertThat(chapter.breakOffsets).containsExactly(7)
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
                        "“Word here now.",
                        listOf(
                            StyleSpan(
                                1, 5,
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
        builder.build(listOf(para("“Word", listOf(StyleSpan(1, 5, style)))), config).text

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
                    "“Word",
                    listOf(
                        StyleSpan(1, 5, InlineStyle(bold = true)),
                        StyleSpan(1, 5, InlineStyle(underline = true)),
                        StyleSpan(1, 5, InlineStyle(grayLevel = 0.2f)),
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
    fun `publisher line-height is never applied - the reader line spacing always governs`() {
        // Owner decision: unlike font-size, a publisher's per-block line-height is ignored
        // entirely; the reader's whole-chapter line spacing always wins. No span, on or off.
        val on = paraBlock(BlockStyle(lineHeightMultiplier = 1.8f))
        val off = builder.build(
            listOf(Block.Paragraph(StyledText("Body"), BlockStyle(lineHeightMultiplier = 1.8f))),
            config.copy(publisherStyling = false),
        ).text
        assertThat(on.getSpans(0, on.length, LineHeightSpan::class.java)).isEmpty()
        assertThat(off.getSpans(0, off.length, LineHeightSpan::class.java)).isEmpty()
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
    fun `a heading sizes only its publisher-sized run, scaling the rest`() {
        // "Chapter One" where only "One" carries a publisher size: "One" keeps 1.29, and the rest
        // ("Chapter ") still gets the semantic heading scale rather than dropping to body size.
        val text = builder.build(
            listOf(Block.Heading(1, StyledText("Chapter One", listOf(StyleSpan(8, 11, InlineStyle(sizeRatio = 1.29f)))))),
            config,
        ).text
        val sizes = text.getSpans(0, text.length, RelativeSizeSpan::class.java)
        assertThat(sizes).hasLength(2)
        // Task 4: this heading opens the chapter, so a prepended "\n\n" (2 chars) shifts the
        // heading's own text to start at offset 2 rather than 0.
        // The publisher-sized run "One" [8,11) within the heading -> [10,13) in the chapter text, at 1.29.
        val onOne = text.getSpans(10, 13, RelativeSizeSpan::class.java).map { it.sizeChange }
        assertThat(onOne).containsExactly(1.29f)
        // "Chapter " [0,8) within the heading -> [2,10) in the chapter text, at the level-1 semantic scale.
        val onChapter = text.getSpans(2, 10, RelativeSizeSpan::class.java).map { it.sizeChange }
        assertThat(onChapter).containsExactly(1.6f)
    }

    @Test
    fun `break offsets are identical whether publisher styling is on or off`() {
        // The toggle only gates setSpan calls, never text appends, so a chapter's page-break
        // offsets must not move with it — the reader can flip styling mid-book without shifting
        // where pages break. Pins the invariant §5 depends on, with styling actually ON.
        val blocks = listOf(
            Block.Paragraph(StyledText("First")),
            Block.PageBreak,
            Block.Heading(1, StyledText("Two", listOf(StyleSpan(0, 3, InlineStyle(sizeRatio = 2f))))),
            Block.Paragraph(StyledText("Body", listOf(StyleSpan(0, 4, InlineStyle(underline = true))))),
        )
        val on = builder.build(blocks, config).breakOffsets
        val off = builder.build(blocks, config.copy(publisherStyling = false)).breakOffsets
        assertThat(on).isEqualTo(off)
        assertThat(on).isNotEmpty()
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

    // --- Inline images (Task 5): decodable bytes -> a downsampled grayscale ImageSpan;
    // null/undecodable bytes -> render nothing (today's degrade), never throw. ---

    /** A real, decodable PNG of the given size — solid red, so a grayscale filter is checkable. */
    private fun imageBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.RED)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test
    fun `a decodable image produces one grayscale ImageSpan bounded by the content width`() {
        // Wider than the content box (1404 - 2*40 = 1324) so it must be downsampled/scaled down.
        val chapter = builder.build(
            listOf(Block.Image("img/fig.png", bytes = imageBytes(2000, 1000))),
            config,
        )
        val spans = chapter.text.getSpans(0, chapter.text.length, ImageSpan::class.java)
        assertThat(spans).hasLength(1)

        val bounds = spans.single().drawable.bounds
        assertThat(bounds.width()).isAtMost(config.contentWidthPx)
        assertThat(bounds.height()).isAtMost(config.contentHeightPx)
        // Aspect ratio preserved (2:1), and actually shrunk from the 2000px original.
        assertThat(bounds.width()).isLessThan(2000)
        assertThat(bounds.width().toFloat() / bounds.height()).isWithin(0.05f).of(2f)
        // Grayscale: the drawable carries a colour filter (saturation 0), the panel has no colour.
        assertThat(spans.single().drawable.colorFilter).isNotNull()
    }

    @Test
    fun `a decodable image carries exactly one placeholder character`() {
        val chapter = builder.build(
            listOf(Block.Image("img/fig.png", bytes = imageBytes(100, 100))),
            config,
        )
        // One placeholder char carries the span; the image is otherwise text-free.
        assertThat(chapter.text.length).isEqualTo(1)
        val span = chapter.text.getSpans(0, chapter.text.length, ImageSpan::class.java).single()
        assertThat(chapter.text.getSpanStart(span)).isEqualTo(0)
        assertThat(chapter.text.getSpanEnd(span)).isEqualTo(1)
    }

    @Test
    fun `null image bytes render nothing and append no text`() {
        val chapter = builder.build(listOf(Block.Image("img/fig.png", bytes = null)), config)
        assertThat(chapter.text.getSpans(0, chapter.text.length, ImageSpan::class.java)).isEmpty()
        assertThat(chapter.text.toString()).isEmpty()
    }

    @Test
    fun `undecodable image bytes render nothing and do not throw`() {
        val chapter = builder.build(
            listOf(Block.Image("img/fig.png", bytes = byteArrayOf(1, 2, 3, 4, 5))),
            config,
        )
        assertThat(chapter.text.getSpans(0, chapter.text.length, ImageSpan::class.java)).isEmpty()
        assertThat(chapter.text.toString()).isEmpty()
    }

    @Test
    fun `text around a decodable image keeps correct offsets`() {
        val chapter = builder.build(
            listOf(para("One."), Block.Image("img/fig.png", bytes = imageBytes(100, 100)), para("Two.")),
            config,
        )
        // "One." + "\n\n" + placeholder + "\n\n" + "Two." — the image occupies one character.
        assertThat(chapter.text.toString()).isEqualTo("One.\n\n￼\n\nTwo.")
        val span = chapter.text.getSpans(0, chapter.text.length, ImageSpan::class.java).single()
        assertThat(chapter.text.getSpanStart(span)).isEqualTo(6)
        assertThat(chapter.text.getSpanEnd(span)).isEqualTo(7)
    }

    @Test
    fun `a page break before a decodable image pins the break to the image`() {
        // A rendered image is now text-bearing, so a preceding page break pins to its
        // placeholder char — unlike a null-bytes image, which still appends nothing.
        val chapter = builder.build(
            listOf(para("One."), Block.PageBreak, Block.Image("img/fig.png", bytes = imageBytes(80, 80))),
            config,
        )
        // "One." (4) + "\n\n" (2) = 6, where the image's placeholder begins.
        assertThat(chapter.breakOffsets).containsExactly(6)
    }

    // --- Reading typography fixes: reader first-line indent (skipping the first
    // paragraph after a heading/scene-break/image/page-break, and never doubling a
    // publisher indent), scene-break centering, and image centering. All reader-baseline:
    // applied regardless of config.publisherStyling. ---

    @Test
    fun `a body paragraph not first after a break gets the reader's first-line indent`() {
        val chapter = builder.build(listOf(para("One."), para("Two.")), config)
        val span = chapter.text.getSpans(0, chapter.text.length, LeadingMarginSpan.Standard::class.java)
            .single()

        // "One." + "\n" = 5 precedes "Two.", the second (non-first-after-break) paragraph.
        assertThat(chapter.text.getSpanStart(span)).isEqualTo(5)
        assertThat(chapter.text.getSpanEnd(span)).isEqualTo(9)
        assertThat(span.getLeadingMargin(true)).isGreaterThan(0)
        assertThat(span.getLeadingMargin(false)).isEqualTo(0)
    }

    @Test
    fun `the first paragraph after a heading has no reader indent`() {
        val chapter = builder.build(listOf(Block.Heading(1, StyledText("Title")), para("Body.")), config)
        assertThat(chapter.text.getSpans(0, chapter.text.length, LeadingMarginSpan.Standard::class.java))
            .isEmpty()
    }

    @Test
    fun `the first paragraph after an image has no reader indent`() {
        val chapter = builder.build(
            listOf(Block.Image("img/fig.png", bytes = imageBytes(10, 10)), para("Body.")),
            config,
        )
        assertThat(chapter.text.getSpans(0, chapter.text.length, LeadingMarginSpan.Standard::class.java))
            .isEmpty()
    }

    @Test
    fun `the first paragraph after a page break has no reader indent`() {
        val chapter = builder.build(listOf(para("One."), Block.PageBreak, para("Two.")), config)
        assertThat(chapter.text.getSpans(0, chapter.text.length, LeadingMarginSpan.Standard::class.java))
            .isEmpty()
    }

    @Test
    fun `a scene break renders as a centered mark on its own line`() {
        val ct = builder.build(
            listOf(
                Block.Paragraph(StyledText("Before.")),
                Block.Paragraph(StyledText("***")),
                Block.Paragraph(StyledText("After.")),
            ),
            config,
        )
        assertThat(ct.text.toString()).isEqualTo("Before.\n\n* * *\n\nAfter.")

        val markStart = ct.text.toString().indexOf("* * *")
        val aligns = ct.text.getSpans(markStart, markStart + 5, AlignmentSpan::class.java)
        assertThat(aligns).hasLength(1)
        assertThat(aligns.single().alignment).isEqualTo(Layout.Alignment.ALIGN_CENTER)
    }

    @Test
    fun `every separator style normalises to the same mark`() {
        // The publisher's own glyphs are discarded: a row of forty hyphens must not become a rule.
        for (source in listOf("***", "* * *", "---", "· · ·", "~~~", "-".repeat(40))) {
            val ct = builder.build(listOf(para("One."), para(source), para("Two.")), config)
            assertThat(ct.text.toString()).isEqualTo("One.\n\n* * *\n\nTwo.")
        }
    }

    @Test
    fun `a paragraph after a scene break has no reader indent`() {
        val chapter = builder.build(listOf(para("One."), para("***"), para("Two.")), config)
        val twoStart = chapter.text.toString().lastIndexOf("Two.")
        assertThat(
            chapter.text.getSpans(twoStart, chapter.text.length, LeadingMarginSpan.Standard::class.java),
        ).isEmpty()
    }

    @Test
    fun `a publisher indent is not doubled by the reader's own indent`() {
        val chapter = builder.build(
            listOf(para("One."), Block.Paragraph(StyledText("Two."), style = BlockStyle(textIndentEm = 2f))),
            config, // publisherStyling defaults to true
        )
        val spans = chapter.text.getSpans(0, chapter.text.length, LeadingMarginSpan.Standard::class.java)
        // Exactly one indent span over "Two." — the publisher's, not doubled by the reader's.
        assertThat(spans).hasLength(1)
        assertThat(spans.single().getLeadingMargin(true)).isEqualTo(64) // round(2 * 32)
    }

    @Test
    fun `an image placeholder is centered`() {
        val chapter = builder.build(
            listOf(Block.Image("img/fig.png", bytes = imageBytes(10, 10))),
            config,
        )
        val alignments = chapter.text.getSpans(0, chapter.text.length, AlignmentSpan.Standard::class.java)
        assertThat(alignments).hasLength(1)
        assertThat(alignments.single().alignment).isEqualTo(Layout.Alignment.ALIGN_CENTER)
    }

    // --- Chapter opening (Task 4): a chapter whose first EMITTED block is a heading (the
    // chapter title) gets extra headroom above it and a forced center AlignmentSpan —
    // unconditionally, regardless of config.publisherStyling. A chapter opening with a
    // paragraph (or any other block) gets neither. ---

    @Test
    fun `a chapter that opens with a heading centers the title and gives it space above`() {
        val ct = builder.build(
            listOf(
                Block.Heading(level = 1, text = StyledText("Chapter One")),
                Block.Paragraph(StyledText("Body.")),
            ),
            config.copy(publisherStyling = false),
        )
        // Space above: the text begins with blank line(s) before the title.
        assertThat(ct.text.toString()).startsWith("\n")
        // The title's range carries a center AlignmentSpan.
        val titleStart = ct.text.toString().indexOf("Chapter One")
        val aligns = ct.text.getSpans(titleStart, titleStart + "Chapter One".length, AlignmentSpan::class.java)
        assertThat(aligns).hasLength(1)
        assertThat(aligns.single().alignment).isEqualTo(Layout.Alignment.ALIGN_CENTER)
    }

    @Test
    fun `a page break before the opening heading still centers it as the chapter title`() {
        val ct = builder.build(
            listOf(
                Block.PageBreak,
                Block.Heading(level = 1, text = StyledText("Chapter One")),
                Block.Paragraph(StyledText("Body.")),
            ),
            config.copy(publisherStyling = false),
        )
        // The heading is still the first EMITTED block (the page break contributes no text),
        // so it should be centered and given headroom.
        val titleStart = ct.text.toString().indexOf("Chapter One")
        val aligns = ct.text.getSpans(titleStart, titleStart + "Chapter One".length, AlignmentSpan::class.java)
        assertThat(aligns).hasLength(1)
        assertThat(aligns.single().alignment).isEqualTo(Layout.Alignment.ALIGN_CENTER)
    }

    @Test
    fun `a chapter that opens with a paragraph gets no chapter-title space or centering`() {
        val ct = builder.build(
            listOf(Block.Paragraph(StyledText("Body."))),
            config.copy(publisherStyling = false),
        )
        assertThat(ct.text.toString()).doesNotContain("\n\nBody") // no prepended headroom
        assertThat(ct.text.getSpans(0, ct.text.length, AlignmentSpan::class.java)).isEmpty()
    }

    @Test
    fun `a mid-chapter heading is not force-centered or given extra headroom`() {
        // Only the chapter-OPENING heading (the first emitted block) gets the Task 4
        // treatment; a heading that shows up after other content has already been emitted
        // is unaffected — it keeps only its existing bold+scale.
        val ct = builder.build(
            listOf(
                Block.Paragraph(StyledText("Intro.")),
                Block.Heading(level = 2, text = StyledText("Section Two")),
            ),
            config.copy(publisherStyling = false),
        )
        assertThat(ct.text.toString()).startsWith("Intro.") // no prepended headroom before the paragraph
        val headingStart = ct.text.toString().indexOf("Section Two")
        val aligns = ct.text.getSpans(headingStart, headingStart + "Section Two".length, AlignmentSpan::class.java)
        assertThat(aligns).isEmpty()
    }

}
