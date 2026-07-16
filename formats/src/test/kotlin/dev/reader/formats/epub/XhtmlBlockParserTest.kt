package dev.reader.formats.epub

import com.google.common.truth.Truth.assertThat
import dev.reader.engine.Block
import dev.reader.engine.InlineStyle
import dev.reader.engine.StyleSpan
import org.junit.Test

class XhtmlBlockParserTest {

    private val parser = XhtmlBlockParser()

    private fun parse(
        body: String,
        css: CssRules = CssRules.EMPTY,
        inferHeadings: Boolean = true,
        chapterPath: String = "OEBPS/text/ch1.xhtml",
    ) = parser.parse("<html><body>$body</body></html>", chapterPath, css, inferHeadings)

    @Test
    fun `extracts paragraphs`() {
        val blocks = parse("<p>Hello there.</p><p>Second.</p>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Paragraph).text.text).isEqualTo("Hello there.")
        assertThat((blocks[1] as Block.Paragraph).text.text).isEqualTo("Second.")
    }

    @Test
    fun `extracts headings with their level`() {
        val blocks = parse("<h1>Title</h1><h3>Sub</h3>")

        assertThat((blocks[0] as Block.Heading).level).isEqualTo(1)
        assertThat((blocks[0] as Block.Heading).text.text).isEqualTo("Title")
        assertThat((blocks[1] as Block.Heading).level).isEqualTo(3)
    }

    @Test
    fun `records bold and italic as spans`() {
        val blocks = parse("<p>A <b>bold</b> and <em>italic</em> word.</p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("A bold and italic word.")
        assertThat(text.spans).containsExactly(
            StyleSpan(2, 6, InlineStyle(bold = true)),
            StyleSpan(11, 17, InlineStyle(italic = true)),
        )
    }

    @Test
    fun `nested emphasis produces overlapping spans`() {
        val blocks = parse("<p><b>bold <i>both</i></b></p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("bold both")
        assertThat(text.spans).containsExactly(
            StyleSpan(5, 9, InlineStyle(italic = true)),
            StyleSpan(0, 9, InlineStyle(bold = true)),
        )
    }

    @Test
    fun `collapses runs of whitespace without shifting spans`() {
        val blocks = parse("<p>A\n   spaced   <b>word</b>\t here.</p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("A spaced word here.")
        // "A spaced " is 9 chars, so the bold run must start at 9.
        assertThat(text.spans).containsExactly(StyleSpan(9, 13, InlineStyle(bold = true)))
    }

    @Test
    fun `recurses through container elements`() {
        val blocks = parse("<div><section><p>Deep.</p></section></div>")
        assertThat((blocks.single() as Block.Paragraph).text.text).isEqualTo("Deep.")
    }

    @Test
    fun `discards style and script content`() {
        val blocks = parse("<style>p { color: red }</style><script>alert(1)</script><p>Kept.</p>")
        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.Paragraph).text.text).isEqualTo("Kept.")
    }

    @Test
    fun `extracts blockquotes`() {
        val blocks = parse("<blockquote>Quoted.</blockquote>")
        assertThat((blocks.single() as Block.Quote).text.text).isEqualTo("Quoted.")
    }

    @Test
    fun `unordered list items have no ordinal`() {
        val blocks = parse("<ul><li>First</li><li>Second</li></ul>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.ListItem).ordinal).isNull()
        assertThat((blocks[0] as Block.ListItem).text.text).isEqualTo("First")
    }

    @Test
    fun `ordered list items are numbered from one`() {
        val blocks = parse("<ol><li>First</li><li>Second</li></ol>")

        assertThat((blocks[0] as Block.ListItem).ordinal).isEqualTo(1)
        assertThat((blocks[1] as Block.ListItem).ordinal).isEqualTo(2)
    }

    @Test
    fun `resolves image sources against the chapter path`() {
        val blocks = parse("""<p><img src="../images/fig1.png"/></p>""")
        assertThat((blocks.single() as Block.Image).href).isEqualTo("OEBPS/images/fig1.png")
    }

    @Test
    fun `emits a page break for a page-break-before style`() {
        val blocks = parse("""<p>Before</p><div style="page-break-before:always"><p>After</p></div>""")

        assertThat(blocks).hasSize(3)
        assertThat(blocks[1]).isEqualTo(Block.PageBreak)
        assertThat((blocks[2] as Block.Paragraph).text.text).isEqualTo("After")
    }

    @Test
    fun `emits a page break for the modern break-before property`() {
        val blocks = parse("""<p>A</p><div style="break-before: page"><p>B</p></div>""")
        assertThat(blocks[1]).isEqualTo(Block.PageBreak)
    }

    @Test
    fun `br becomes a newline inside the paragraph`() {
        val blocks = parse("<p>One<br/>Two</p>")
        assertThat((blocks.single() as Block.Paragraph).text.text).isEqualTo("One\nTwo")
    }

    @Test
    fun `drops empty paragraphs`() {
        val blocks = parse("<p></p><p>   </p><p>Real.</p>")
        assertThat(blocks).hasSize(1)
    }

    @Test
    fun `survives unclosed tags`() {
        val blocks = parse("<p>Unclosed<p>Next")
        assertThat(blocks.map { (it as Block.Paragraph).text.text })
            .containsExactly("Unclosed", "Next").inOrder()
    }

    @Test
    fun `empty body yields no blocks`() {
        assertThat(parse("")).isEmpty()
    }

    // --- Finding 1: trailing space inside a trailing inline element must not throw ---

    @Test
    fun `trailing space before closing bold tag does not throw`() {
        val blocks = parse("<p><b>text </b></p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("text")
        assertThat(text.spans).containsExactly(StyleSpan(0, 4, InlineStyle(bold = true)))
    }

    @Test
    fun `trailing space before closing italic tag does not throw`() {
        val blocks = parse("<p><i>The end. </i></p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("The end.")
        assertThat(text.spans).containsExactly(StyleSpan(0, 8, InlineStyle(italic = true)))
    }

    @Test
    fun `trailing space before closing bold tag with leading text does not throw`() {
        val blocks = parse("<p>x<b>y </b></p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("xy")
        assertThat(text.spans).containsExactly(StyleSpan(1, 2, InlineStyle(bold = true)))
    }

    // --- Finding 2: inline style spans must survive an <img> flush mid-run ---

    @Test
    fun `bold span survives an image splitting the run in the middle`() {
        val blocks = parse("""<p>Hello <b>wo<img src="a.png"/>rld</b>!</p>""")

        assertThat(blocks).hasSize(3)
        val first = (blocks[0] as Block.Paragraph).text
        assertThat(first.text).isEqualTo("Hello wo")
        assertThat(first.spans).containsExactly(StyleSpan(6, 8, InlineStyle(bold = true)))

        assertThat(blocks[1]).isInstanceOf(Block.Image::class.java)

        val second = (blocks[2] as Block.Paragraph).text
        assertThat(second.text).isEqualTo("rld!")
        assertThat(second.spans).containsExactly(StyleSpan(0, 3, InlineStyle(bold = true)))
    }

    @Test
    fun `bold span reopens at zero after an image and covers the whole resumed run`() {
        val blocks = parse("""<p>a<b>b<img src="a.png"/>this is a long bold run</b></p>""")

        assertThat(blocks).hasSize(3)
        val after = (blocks[2] as Block.Paragraph).text
        assertThat(after.text).isEqualTo("this is a long bold run")
        assertThat(after.spans).containsExactly(StyleSpan(0, 23, InlineStyle(bold = true)))
    }

    @Test
    fun `text before an image becomes its own paragraph`() {
        val blocks = parse("""<p>Before text<img src="a.png"/></p>""")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Paragraph).text.text).isEqualTo("Before text")
        assertThat(blocks[1]).isInstanceOf(Block.Image::class.java)
    }

    @Test
    fun `text after an image becomes its own paragraph`() {
        val blocks = parse("""<p><img src="a.png"/>After text</p>""")

        assertThat(blocks).hasSize(2)
        assertThat(blocks[0]).isInstanceOf(Block.Image::class.java)
        assertThat((blocks[1] as Block.Paragraph).text.text).isEqualTo("After text")
    }

    @Test
    fun `text on both sides of an image becomes two paragraphs`() {
        val blocks = parse("""<p>Before<img src="a.png"/>After</p>""")

        assertThat(blocks).hasSize(3)
        assertThat((blocks[0] as Block.Paragraph).text.text).isEqualTo("Before")
        assertThat(blocks[1]).isInstanceOf(Block.Image::class.java)
        assertThat((blocks[2] as Block.Paragraph).text.text).isEqualTo("After")
    }

    @Test
    fun `multiple images in one paragraph each split the run`() {
        val blocks = parse("""<p>A<img src="1.png"/>B<img src="2.png"/>C</p>""")

        assertThat(blocks).hasSize(5)
        assertThat((blocks[0] as Block.Paragraph).text.text).isEqualTo("A")
        assertThat((blocks[1] as Block.Image).href).isEqualTo("OEBPS/text/1.png")
        assertThat((blocks[2] as Block.Paragraph).text.text).isEqualTo("B")
        assertThat((blocks[3] as Block.Image).href).isEqualTo("OEBPS/text/2.png")
        assertThat((blocks[4] as Block.Paragraph).text.text).isEqualTo("C")
    }

    // --- Finding 4: blockquote with block-level children emits one Quote per child ---

    @Test
    fun `blockquote with paragraph children emits one quote per paragraph`() {
        val blocks = parse("<blockquote><p>A</p><p>B</p></blockquote>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Quote).text.text).isEqualTo("A")
        assertThat((blocks[1] as Block.Quote).text.text).isEqualTo("B")
    }

    @Test
    fun `blockquote with only inline content stays a single quote`() {
        val blocks = parse("<blockquote>Quoted <b>text</b>.</blockquote>")
        val text = (blocks.single() as Block.Quote).text

        assertThat(blocks).hasSize(1)
        assertThat(text.text).isEqualTo("Quoted text.")
        // This span is the exact property that regressed in `visitContainerChildren`
        // (identical markup, `<div>` instead of `<blockquote>`) before fix wave 3.
        assertThat(text.spans).containsExactly(StyleSpan(7, 11, InlineStyle(bold = true)))
    }

    // --- Wave 2, finding 1: blockquote must walk ALL children in document order, not just p/div ---

    @Test
    fun `blockquote preserves lead text before a paragraph child`() {
        val blocks = parse("<blockquote>lead text<p>A</p></blockquote>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Quote).text.text).isEqualTo("lead text")
        assertThat((blocks[1] as Block.Quote).text.text).isEqualTo("A")
    }

    @Test
    fun `blockquote preserves a heading child instead of dropping it`() {
        val blocks = parse("<blockquote><h2>T</h2><p>A</p></blockquote>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Quote).text.text).isEqualTo("T")
        assertThat((blocks[1] as Block.Quote).text.text).isEqualTo("A")
    }

    @Test
    fun `blockquote preserves an image sibling instead of dropping it`() {
        val blocks = parse("""<blockquote><p>A</p><img src="x.png"/></blockquote>""")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Quote).text.text).isEqualTo("A")
        assertThat((blocks[1] as Block.Image).href).isEqualTo("OEBPS/text/x.png")
    }

    @Test
    fun `blockquote recurses into a nested div so its paragraphs stay separate`() {
        val blocks = parse("<blockquote><div><p>A</p><p>B</p></div></blockquote>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Quote).text.text).isEqualTo("A")
        assertThat((blocks[1] as Block.Quote).text.text).isEqualTo("B")
    }

    // --- Finding 5: bare text directly inside a container must not be dropped ---

    @Test
    fun `bare text directly inside a container becomes a paragraph`() {
        val blocks = parse("<div>Bare text</div>")

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.Paragraph).text.text).isEqualTo("Bare text")
    }

    @Test
    fun `tail text after an element inside a container is not dropped`() {
        val blocks = parse("<div><p>x</p>tail text</div>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Paragraph).text.text).isEqualTo("x")
        assertThat((blocks[1] as Block.Paragraph).text.text).isEqualTo("tail text")
    }

    // --- Wave 2, finding 2: bare text directly inside <body> must not be dropped ---

    @Test
    fun `bare text directly inside body becomes a paragraph`() {
        val blocks = parser.parse("<html><body>Bare body text</body></html>", "OEBPS/text/ch1.xhtml")

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.Paragraph).text.text).isEqualTo("Bare body text")
    }

    @Test
    fun `tail text directly inside body after an element is not dropped`() {
        val blocks = parser.parse("<html><body><p>x</p>tail body text</body></html>", "OEBPS/text/ch1.xhtml")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Paragraph).text.text).isEqualTo("x")
        assertThat((blocks[1] as Block.Paragraph).text.text).isEqualTo("tail body text")
    }

    @Test
    fun `whitespace-only text nodes between elements do not become empty paragraphs`() {
        val blocks = parse("<div>   <p>x</p>   </div>")

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.Paragraph).text.text).isEqualTo("x")
    }

    // --- Finding 6: PageBreak must not be emitted for content-free elements or duplicated ---

    @Test
    fun `no page break is emitted for an empty break-requesting element`() {
        val blocks = parse(
            """<div style="page-break-before:always"></div><p>After</p>""",
        )

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.Paragraph).text.text).isEqualTo("After")
    }

    @Test
    fun `nested break-requesting containers do not duplicate the page break`() {
        val blocks = parse(
            """<div style="page-break-before:always">""" +
                """<div style="page-break-before:always"><p>Text</p></div></div>""",
        )

        assertThat(blocks).hasSize(2)
        assertThat(blocks[0]).isEqualTo(Block.PageBreak)
        assertThat((blocks[1] as Block.Paragraph).text.text).isEqualTo("Text")
    }

    // --- Finding 7: an image whose resolved href collapses to blank must not throw ---

    @Test
    fun `image whose resolved href collapses to blank is silently dropped`() {
        val blocks = parse("""<img src=".."/>""", chapterPath = "ch1.xhtml")
        assertThat(blocks).isEmpty()
    }

    // --- Wave 2, minor 4: nested styles across a flush, the hardest shape the style stack supports ---

    @Test
    fun `nested styles survive an image flush in the middle of the innermost style`() {
        val blocks = parse("""<p><b>a<i>b<img src="x.png"/>c</i>d</b></p>""")

        assertThat(blocks).hasSize(3)
        val before = (blocks[0] as Block.Paragraph).text
        assertThat(before.text).isEqualTo("ab")
        assertThat(before.spans).containsExactly(
            StyleSpan(0, 2, InlineStyle(bold = true)),
            StyleSpan(1, 2, InlineStyle(italic = true)),
        )

        assertThat(blocks[1]).isInstanceOf(Block.Image::class.java)

        val after = (blocks[2] as Block.Paragraph).text
        assertThat(after.text).isEqualTo("cd")
        assertThat(after.spans).containsExactly(
            StyleSpan(0, 1, InlineStyle(italic = true)),
            StyleSpan(0, 2, InlineStyle(bold = true)),
        )
    }

    // --- Wave 2, minor 5: the `leading != 0` shift arm of build(), untested with an actual span ---

    @Test
    fun `a leading line break shifts a later span's offsets`() {
        val blocks = parse("<p><br/>Hi <b>bold</b></p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("Hi bold")
        assertThat(text.spans).containsExactly(StyleSpan(3, 7, InlineStyle(bold = true)))
    }

    // --- Wave 2, minor 6: zero-length style at a flush must not throw and must not appear ---

    @Test
    fun `a style open for zero characters at a flush produces no span for that piece`() {
        val blocks = parse("""<p><b><img src="x.png"/>x</b></p>""")

        assertThat(blocks).hasSize(2)
        assertThat(blocks[0]).isInstanceOf(Block.Image::class.java)

        val after = (blocks[1] as Block.Paragraph).text
        assertThat(after.text).isEqualTo("x")
        assertThat(after.spans).containsExactly(StyleSpan(0, 1, InlineStyle(bold = true)))
    }

    // --- Fix wave 3: a container with MIXED inline content must accumulate one run, not
    // fragment into one Paragraph per bare text node with styling destroyed. No prior test
    // exercised a bare inline element (`<b>`) as a *direct* child of a transparent container
    // or of <body> itself — the exact shape that let this survive three review waves. ---

    @Test
    fun `mixed inline content directly in a container becomes one paragraph with its span intact`() {
        val blocks = parse("<div>Quoted <b>text</b>.</div>")

        assertThat(blocks).hasSize(1)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.text).isEqualTo("Quoted text.")
        assertThat(text.spans).containsExactly(StyleSpan(7, 11, InlineStyle(bold = true)))
    }

    @Test
    fun `mixed inline content directly in body becomes one paragraph with its span intact`() {
        val blocks = parser.parse("<html><body>Hello <b>world</b>!</body></html>", "OEBPS/text/ch1.xhtml")

        assertThat(blocks).hasSize(1)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.text).isEqualTo("Hello world!")
        assertThat(text.spans).containsExactly(StyleSpan(6, 11, InlineStyle(bold = true)))
    }

    // --- Fix wave 3, minor 1: unifying the container/blockquote walks makes routing <ul>/<ol>
    // through normal dispatch natural, so list items nested in a blockquote now keep their
    // ListItem type (with ordinals) instead of degrading to plain Quote text. ---

    @Test
    fun `list items inside a blockquote keep their ListItem type via natural dispatch`() {
        val blocks = parse("<blockquote><ul><li>A</li><li>B</li></ul></blockquote>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.ListItem).text.text).isEqualTo("A")
        assertThat((blocks[0] as Block.ListItem).ordinal).isNull()
        assertThat((blocks[1] as Block.ListItem).text.text).isEqualTo("B")
    }

    // --- Plan 1b Task 2: emphasis recovered from CSS ---

    @Test
    fun `span with an italic class becomes an italic span`() {
        val css = CssRules.parse(".italic { font-style: italic }")
        val blocks = parse("""<p>A <span class="italic">word</span>.</p>""", css)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.text).isEqualTo("A word.")
        assertThat(text.spans).containsExactly(StyleSpan(2, 6, InlineStyle(italic = true)))
    }

    @Test
    fun `numeric font-weight of 600 or more is bold`() {
        val css = CssRules.parse(".b { font-weight: 700 }")
        val blocks = parse("""<p>A <span class="b">word</span>.</p>""", css)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.spans).containsExactly(StyleSpan(2, 6, InlineStyle(bold = true)))
    }

    @Test
    fun `numeric font-weight below 600 is not bold`() {
        val css = CssRules.parse(".n { font-weight: 400 }")
        val blocks = parse("""<p>A <span class="n">word</span>.</p>""", css)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.spans).isEmpty()
    }

    @Test
    fun `oblique is italic`() {
        val css = CssRules.parse(".o { font-style: oblique }")
        val blocks = parse("""<p>A <span class="o">word</span>.</p>""", css)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.spans).containsExactly(StyleSpan(2, 6, InlineStyle(italic = true)))
    }

    @Test
    fun `inline style attribute produces emphasis`() {
        val blocks = parse("""<p>A <span style="font-style:italic">word</span>.</p>""")
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.spans).containsExactly(StyleSpan(2, 6, InlineStyle(italic = true)))
    }

    @Test
    fun `css emphasis and tag emphasis combine`() {
        val css = CssRules.parse(".italic { font-style: italic }")
        val blocks = parse("""<p><b class="italic">x</b></p>""", css)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.text).isEqualTo("x")
        assertThat(text.spans).containsExactly(
            StyleSpan(0, 1, InlineStyle(bold = true)),
            StyleSpan(0, 1, InlineStyle(italic = true)),
        )
    }

    @Test
    fun `an empty css-emphasised span produces no span`() {
        val css = CssRules.parse(".italic { font-style: italic }")
        val blocks = parse("""<p>A<span class="italic"></span>B</p>""", css)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.text).isEqualTo("AB")
        assertThat(text.spans).isEmpty()
    }

    // --- Plan 1b Task 2: heading inference from CSS ---

    @Test
    fun `a short large centered paragraph is inferred as a heading`() {
        val css = CssRules.parse(".t { font-size: 2em; text-align: center }")
        val blocks = parse("""<p class="t">Prologue</p>""", css, inferHeadings = true)
        val h = blocks.single() as Block.Heading
        assertThat(h.level).isEqualTo(1)
        assertThat(h.text.text).isEqualTo("Prologue")
    }

    @Test
    fun `heading level follows the size ratio`() {
        val css = CssRules.parse(
            ".r1 { font-size: 2em } .r2 { font-size: 1.6em } " +
                ".r3 { font-size: 1.4em } .r4 { font-size: 1.2em }",
        )
        assertThat((parse("""<p class="r1">A</p>""", css).single() as Block.Heading).level).isEqualTo(1)
        assertThat((parse("""<p class="r2">A</p>""", css).single() as Block.Heading).level).isEqualTo(2)
        assertThat((parse("""<p class="r3">A</p>""", css).single() as Block.Heading).level).isEqualTo(3)
        assertThat((parse("""<p class="r4">A</p>""", css).single() as Block.Heading).level).isEqualTo(4)
    }

    @Test
    fun `centered and bold with no size signal infers level 3`() {
        val css = CssRules.parse(".t { text-align: center; font-weight: bold }")
        val blocks = parse("""<p class="t">Title</p>""", css)
        assertThat((blocks.single() as Block.Heading).level).isEqualTo(3)
    }

    @Test
    fun `a long paragraph is never inferred as a heading`() {
        val css = CssRules.parse(".t { font-size: 2em }")
        val longText = "x".repeat(200)
        val blocks = parse("""<p class="t">$longText</p>""", css)
        assertThat(blocks.single()).isInstanceOf(Block.Paragraph::class.java)
    }

    @Test
    fun `body text is never inferred as a heading`() {
        val css = CssRules.parse(".calibre_13 { font-size: 1em }")
        val blocks = parse("""<p class="calibre_13">Just ordinary prose.</p>""", css)
        assertThat(blocks.single()).isInstanceOf(Block.Paragraph::class.java)
    }

    @Test
    fun `inference is off when inferHeadings is false`() {
        val css = CssRules.parse(".t { font-size: 2em; text-align: center }")
        val blocks = parse("""<p class="t">Prologue</p>""", css, inferHeadings = false)
        assertThat(blocks.single()).isInstanceOf(Block.Paragraph::class.java)
    }

    @Test
    fun `emphasis still works when inferHeadings is false`() {
        val css = CssRules.parse(".italic { font-style: italic }")
        val blocks = parse("""<p>A <span class="italic">word</span>.</p>""", css, inferHeadings = false)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.spans).containsExactly(StyleSpan(2, 6, InlineStyle(italic = true)))
    }

    @Test
    fun `an absolute px size with no baseline gives no heading signal`() {
        val css = CssRules.parse(".t { font-size: 24px }")
        val blocks = parse("""<p class="t">Prologue</p>""", css)
        assertThat(blocks.single()).isInstanceOf(Block.Paragraph::class.java)
    }

    @Test
    fun `a px size is usable relative to a body baseline`() {
        val css = CssRules.parse("body { font-size: 12px } .t { font-size: 24px }")
        val blocks = parse("""<p class="t">Prologue</p>""", css)
        assertThat((blocks.single() as Block.Heading).level).isEqualTo(1)
    }

    @Test
    fun `an inferred heading keeps its inline spans`() {
        val css = CssRules.parse(".t { font-size: 2em } .italic { font-style: italic }")
        val blocks = parse("""<p class="t">A <span class="italic">word</span></p>""", css)
        val h = blocks.single() as Block.Heading
        assertThat(h.text.text).isEqualTo("A word")
        // The heading's own 2em rides along as a full-width sizeRatio span (Plan 3 Task 3),
        // alongside the inner italic span. Both coexist — inference still chose Heading.
        assertThat(h.text.spans).containsExactly(
            StyleSpan(0, 6, InlineStyle(sizeRatio = 2.0f)),
            StyleSpan(2, 6, InlineStyle(italic = true)),
        )
    }

    @Test
    fun `semantic h1 still wins without any css`() {
        val blocks = parse("<h1>Title</h1>", CssRules.EMPTY)
        assertThat((blocks.single() as Block.Heading).level).isEqualTo(1)
    }

    // --- Fix wave 1, finding 1: a CSS-emphasised block container must not collapse
    // block structure. `blockquote { font-style: italic }` used to make the styles-first
    // branch of walkRun treat the whole blockquote as one inline run, losing its <p>
    // children as separate Quotes. ---

    @Test
    fun `css emphasis on a blockquote does not collapse its paragraph children`() {
        val css = CssRules.parse("blockquote { font-style: italic }")
        val blocks = parse("<blockquote><p>A</p><p>B</p></blockquote>", css)

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Quote).text.text).isEqualTo("A")
        assertThat((blocks[1] as Block.Quote).text.text).isEqualTo("B")
    }

    @Test
    fun `css emphasis on a div does not collapse its paragraph children`() {
        val css = CssRules.parse("div { font-weight: bold }")
        val blocks = parse("<div><p>A</p><p>B</p></div>", css)

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Paragraph).text.text).isEqualTo("A")
        assertThat((blocks[1] as Block.Paragraph).text.text).isEqualTo("B")
    }

    @Test
    fun `css emphasis on a container with only inline content still applies as one run`() {
        // No block-level child here (just bare text), so this container is legitimately
        // an inline run, unlike the two cases above.
        val css = CssRules.parse(".epigraph { font-style: italic }")
        val blocks = parse("""<div class="epigraph">A line of verse.</div>""", css)

        assertThat(blocks).hasSize(1)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.text).isEqualTo("A line of verse.")
        assertThat(text.spans).containsExactly(StyleSpan(0, 16, InlineStyle(italic = true)))
    }

    @Test
    fun `css emphasis on a section with paragraph children does not collapse them`() {
        val css = CssRules.parse("section { font-style: italic }")
        val blocks = parse("<section><p>A</p><p>B</p></section>", css)

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Paragraph).text.text).isEqualTo("A")
        assertThat((blocks[1] as Block.Paragraph).text.text).isEqualTo("B")
    }

    // --- Fix wave 1, minor 6: the same InlineStyle recovered at two nested levels
    // (tag + CSS) must not produce two identical, redundant spans. ---

    @Test
    fun `an already-open style from an ancestor is not duplicated by a redundant css rule`() {
        val css = CssRules.parse(".italic { font-style: italic }")
        val blocks = parse("""<p><i><span class="italic">x</span></i></p>""", css)
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("x")
        assertThat(text.spans).containsExactly(StyleSpan(0, 1, InlineStyle(italic = true)))
    }

    // --- Fix wave 1, finding 2: heading inference must look through a sole text-bearing
    // descendant chain when the <p>/<div> itself carries no size/weight signal — the real
    // shape calibre-style EPUBs use, per The Dark Forest. ---

    @Test
    fun `a calibre-style title wrapped in a nested span is inferred as a heading`() {
        val css = CssRules.parse(
            ".calibre_7 { text-align: center } .calibre2 { font-size: 1.29167em }",
        )
        val blocks = parse(
            """<p class="calibre_7"><a href="x"><span class="calibre2">Year 3, Crisis Era</span></a></p>""",
            css,
        )
        val h = blocks.single() as Block.Heading
        assertThat(h.level).isEqualTo(4)
        assertThat(h.text.text).isEqualTo("Year 3, Crisis Era")
    }

    @Test
    fun `a larger calibre-style title wrapped in a nested span infers a higher level`() {
        val css = CssRules.parse(
            ".calibre_2 { text-align: center } .calibre7 { font-size: 1.66667em }",
        )
        val blocks = parse(
            """<p class="calibre_2"><a href="x"><span class="calibre7">THE WALLFACERS</span></a></p>""",
            css,
        )
        assertThat((blocks.single() as Block.Heading).level).isEqualTo(2)
    }

    @Test
    fun `a doubly-nested span carrying size and bold separately is still inferred as a heading`() {
        val css = CssRules.parse(
            ".calibre_7 { text-align: center } .calibre2 { font-size: 1.29167em } .bold { font-weight: bold }",
        )
        val blocks = parse(
            """<p class="calibre_7"><a href="x"><span class="calibre2">""" +
                """<span class="bold">PART I</span></span></a></p>""",
            css,
        )
        assertThat(blocks.single()).isInstanceOf(Block.Heading::class.java)
    }

    @Test
    fun `ordinary body prose with an inner span but no size signal is not promoted`() {
        val css = CssRules.parse(".calibre3 { color: black }")
        val blocks = parse(
            """<p class="calibre_13">Ordinary prose with a <span class="calibre3">span</span> inside.</p>""",
            css,
        )
        assertThat(blocks.single()).isInstanceOf(Block.Paragraph::class.java)
    }

    @Test
    fun `a long paragraph with a large inner span is never inferred as a heading`() {
        val css = CssRules.parse(".big { font-size: 2em }")
        val longText = "x".repeat(200)
        val blocks = parse("""<p><span class="big">$longText</span></p>""", css)
        assertThat(blocks.single()).isInstanceOf(Block.Paragraph::class.java)
    }

    @Test
    fun `a sole-child wrapper carrying no signal at all is still not promoted`() {
        val css = CssRules.parse(".wrap { color: red }")
        val blocks = parse("""<p><a href="x"><span class="wrap">Ordinary short link.</span></a></p>""", css)
        assertThat(blocks.single()).isInstanceOf(Block.Paragraph::class.java)
    }

    // --- Fix wave A, C1: an UNSTYLED inline-by-tag element (<a>, <sup>, <span>, ...) as a
    // direct child of a container must join the surrounding text run, not shatter it into
    // one Paragraph per fragment. The earlier fix only covered *styled* inline children. ---

    @Test
    fun `unstyled anchor inside a container joins the surrounding text run`() {
        val blocks = parse("""<div>Hello <a href="x">link</a> world.</div>""")

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.Paragraph).text.text).isEqualTo("Hello link world.")
    }

    @Test
    fun `unstyled sup footnote marker inside a container joins the run`() {
        val blocks = parse("<div>Text with a footnote<sup><a>1</a></sup> continuing here.</div>")

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.Paragraph).text.text)
            .isEqualTo("Text with a footnote1 continuing here.")
    }

    @Test
    fun `styled and unstyled inline siblings in a container stay one run`() {
        val blocks = parse("<div><i>lead</i> middle <a>tail</a></div>")

        assertThat(blocks).hasSize(1)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.text).isEqualTo("lead middle tail")
        assertThat(text.spans).containsExactly(StyleSpan(0, 4, InlineStyle(italic = true)))
    }

    @Test
    fun `control - the same inline markup inside a p stays one paragraph`() {
        val blocks = parse("""<p>Hello <a href="x">link</a> world.</p>""")

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.Paragraph).text.text).isEqualTo("Hello link world.")
    }

    @Test
    fun `unstyled anchor directly inside body joins the surrounding run`() {
        val blocks = parser.parse(
            """<html><body>Hello <a href="x">link</a> world.</body></html>""",
            "OEBPS/text/ch1.xhtml",
        )

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.Paragraph).text.text).isEqualTo("Hello link world.")
    }

    @Test
    fun `styled and unstyled inline siblings directly inside body stay one run`() {
        val blocks = parser.parse(
            "<html><body><i>lead</i> middle <a>tail</a></body></html>",
            "OEBPS/text/ch1.xhtml",
        )

        assertThat(blocks).hasSize(1)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.text).isEqualTo("lead middle tail")
        assertThat(text.spans).containsExactly(StyleSpan(0, 4, InlineStyle(italic = true)))
    }

    @Test
    fun `an anchor wrapping block content still recurses so paragraphs stay separate`() {
        val blocks = parse("<div><a><p>A</p><p>B</p></a></div>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Paragraph).text.text).isEqualTo("A")
        assertThat((blocks[1] as Block.Paragraph).text.text).isEqualTo("B")
    }

    // --- Fix wave A, I1: block-level children of an <li> are boundaries — their words
    // must never concatenate with the surrounding text without a separator. ---

    @Test
    fun `paragraph children of a list item do not merge words`() {
        val blocks = parse("<ul><li><p>one</p><p>two</p></li></ul>")

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.ListItem).text.text).isEqualTo("one\ntwo")
    }

    @Test
    fun `a nested list inside a list item does not merge words`() {
        val blocks = parse("<ul><li>outer<ul><li>inner</li></ul></li></ul>")

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.ListItem).text.text).isEqualTo("outer\ninner")
    }

    @Test
    fun `a blockquote inside a list item does not merge words`() {
        val blocks = parse("<ul><li>note:<blockquote>quoted</blockquote></li></ul>")

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.ListItem).text.text).isEqualTo("note:\nquoted")
    }

    // --- Fix wave A, M1: a list whose <li>s are wrapped in a non-<li> element (or that
    // holds bare text directly) must not silently vanish. ---

    @Test
    fun `list items wrapped in a div inside the list still emit`() {
        val blocks = parse("<ul><div><li>item one</li><li>item two</li></div></ul>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.ListItem).text.text).isEqualTo("item one")
        assertThat((blocks[1] as Block.ListItem).text.text).isEqualTo("item two")
    }

    @Test
    fun `ordered list items wrapped in a div keep their ordinals`() {
        val blocks = parse("<ol><div><li>a</li><li>b</li></div></ol>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.ListItem).ordinal).isEqualTo(1)
        assertThat((blocks[1] as Block.ListItem).ordinal).isEqualTo(2)
    }

    @Test
    fun `bare text directly inside a list becomes a paragraph instead of vanishing`() {
        val blocks = parse("<ul>loose text<li>item</li></ul>")

        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as Block.Paragraph).text.text).isEqualTo("loose text")
        assertThat((blocks[1] as Block.ListItem).text.text).isEqualTo("item")
    }

    @Test
    fun `a nested list met while scanning wrappers emits its items exactly once`() {
        val blocks = parse("<ul><div><ul><li>only once</li></ul></div></ul>")

        assertThat(blocks).hasSize(1)
        assertThat((blocks.single() as Block.ListItem).text.text).isEqualTo("only once")
    }

    // --- Fix wave A, M6: page-break-before declared via a class rule (calibre's standard
    // shape) must produce a PageBreak, not just the inline style attribute. ---

    @Test
    fun `emits a page break for a class-declared page-break-before`() {
        val css = CssRules.parse(".pb { page-break-before: always }")
        val blocks = parse("""<p>Before</p><div class="pb"><p>After</p></div>""", css)

        assertThat(blocks).hasSize(3)
        assertThat(blocks[1]).isEqualTo(Block.PageBreak)
        assertThat((blocks[2] as Block.Paragraph).text.text).isEqualTo("After")
    }

    @Test
    fun `emits a page break for a class-declared modern break-before property`() {
        val css = CssRules.parse(".pb { break-before: page }")
        val blocks = parse("""<p>A</p><div class="pb"><p>B</p></div>""", css)

        assertThat(blocks).hasSize(3)
        assertThat(blocks[1]).isEqualTo(Block.PageBreak)
    }

    // --- Plan 3 Task 3: resolve the full CSS property table into the model ---
    //
    // Helpers: the parser pushes each honored property as its own single-field InlineStyle
    // through the same one-span-per-semantic stack, so a run's style for a given property is
    // read off the span whose style carries that field.

    private fun paragraphSpans(body: String, css: CssRules): List<StyleSpan> =
        (parse(body, css).single() as Block.Paragraph).text.spans

    private fun paragraphStyle(body: String, css: CssRules) =
        (parse(body, css).single() as Block.Paragraph).style

    // -- text-decoration --

    @Test
    fun `text-decoration underline becomes an underline span`() {
        val css = CssRules.parse(".u { text-decoration: underline }")
        val spans = paragraphSpans("""<p>a <span class="u">b</span> c</p>""", css)
        assertThat(spans).containsExactly(StyleSpan(2, 3, InlineStyle(underline = true)))
    }

    @Test
    fun `text-decoration line-through becomes a strikethrough span`() {
        val css = CssRules.parse(".s { text-decoration: line-through }")
        val spans = paragraphSpans("""<p>a <span class="s">b</span> c</p>""", css)
        assertThat(spans).containsExactly(StyleSpan(2, 3, InlineStyle(strikethrough = true)))
    }

    @Test
    fun `text-decoration with both underline and line-through emits both spans`() {
        val css = CssRules.parse(".b { text-decoration: underline line-through }")
        val spans = paragraphSpans("""<p><span class="b">x</span></p>""", css)
        assertThat(spans).containsExactly(
            StyleSpan(0, 1, InlineStyle(underline = true)),
            StyleSpan(0, 1, InlineStyle(strikethrough = true)),
        )
    }

    @Test
    fun `text-decoration none produces no span`() {
        val css = CssRules.parse(".n { text-decoration: none }")
        val spans = paragraphSpans("""<p><span class="n">x</span></p>""", css)
        assertThat(spans).isEmpty()
    }

    @Test
    fun `text-decoration does not inherit into descendants`() {
        // text-decoration is a non-inherited property: an underlined block must not paint
        // every nested run with its own underline span.
        val css = CssRules.parse(".u { text-decoration: underline }")
        val spans = paragraphSpans("""<p class="u">a <span>b</span></p>""", css)
        // Only the block-level underline over the whole paragraph; the inner span adds none.
        assertThat(spans).containsExactly(StyleSpan(0, 3, InlineStyle(underline = true)))
    }

    // -- letter-spacing --

    @Test
    fun `letter-spacing in em maps to letterSpacingEm`() {
        val css = CssRules.parse(".w { letter-spacing: 0.15em }")
        val spans = paragraphSpans("""<p><span class="w">x</span></p>""", css)
        val ls = spans.single().style.letterSpacingEm!!
        assertThat(ls).isWithin(1e-4f).of(0.15f)
    }

    @Test
    fun `letter-spacing in px maps against the baseline`() {
        val css = CssRules.parse("body { font-size: 10px } .w { letter-spacing: 2px }")
        val spans = paragraphSpans("""<p><span class="w">x</span></p>""", css)
        val ls = spans.single().style.letterSpacingEm!!
        assertThat(ls).isWithin(1e-4f).of(0.2f)
    }

    @Test
    fun `letter-spacing normal yields no span`() {
        val css = CssRules.parse(".w { letter-spacing: normal }")
        val spans = paragraphSpans("""<p><span class="w">x</span></p>""", css)
        assertThat(spans).isEmpty()
    }

    // -- color / grayLevel (luminance = 0.2126R + 0.7152G + 0.0722B) --

    @Test
    fun `color hex red maps to its luminance gray level`() {
        val css = CssRules.parse(".c { color: #ff0000 }")
        val spans = paragraphSpans("""<p><span class="c">x</span></p>""", css)
        val gray = spans.single().style.grayLevel!!
        assertThat(gray).isWithin(1e-4f).of(0.2126f)
    }

    @Test
    fun `color three-digit hex expands like six-digit`() {
        val css = CssRules.parse(".c { color: #00f }")
        val spans = paragraphSpans("""<p><span class="c">x</span></p>""", css)
        assertThat(spans.single().style.grayLevel!!).isWithin(1e-4f).of(0.0722f)
    }

    @Test
    fun `color rgb function maps to luminance`() {
        val css = CssRules.parse(".c { color: rgb(0, 255, 0) }")
        val spans = paragraphSpans("""<p><span class="c">x</span></p>""", css)
        assertThat(spans.single().style.grayLevel!!).isWithin(1e-4f).of(0.7152f)
    }

    @Test
    fun `named color gray maps to mid gray`() {
        val css = CssRules.parse(".c { color: gray }")
        val spans = paragraphSpans("""<p><span class="c">x</span></p>""", css)
        // #808080 → 128/255 on each channel → luminance 128/255.
        assertThat(spans.single().style.grayLevel!!).isWithin(1e-4f).of(128f / 255f)
    }

    @Test
    fun `named color black maps to zero`() {
        val css = CssRules.parse(".c { color: black }")
        val spans = paragraphSpans("""<p><span class="c">x</span></p>""", css)
        assertThat(spans.single().style.grayLevel!!).isWithin(1e-4f).of(0f)
    }

    @Test
    fun `unknown color name produces no gray span`() {
        val css = CssRules.parse(".c { color: rebeccapurpleish }")
        val spans = paragraphSpans("""<p><span class="c">x</span></p>""", css)
        assertThat(spans).isEmpty()
    }

    // -- font-size / sizeRatio --

    @Test
    fun `font-size in em maps to sizeRatio`() {
        val css = CssRules.parse(".big { font-size: 1.5em }")
        val spans = paragraphSpans("""<p>a <span class="big">b</span></p>""", css)
        assertThat(spans.single().style.sizeRatio!!).isWithin(1e-4f).of(1.5f)
    }

    @Test
    fun `font-size in percent maps to sizeRatio`() {
        // Leading unstyled text keeps this a Paragraph: a <p> whose SOLE content is a short
        // enlarged span is (correctly) inferred as a Heading, which would test inference, not
        // the size mapping this test is about. See the em case above for the same pattern.
        val css = CssRules.parse(".big { font-size: 150% }")
        val spans = paragraphSpans("""<p>a <span class="big">b</span></p>""", css)
        assertThat(spans.single().style.sizeRatio!!).isWithin(1e-4f).of(1.5f)
    }

    @Test
    fun `font-size in px maps against the mined body baseline`() {
        val css = CssRules.parse("body { font-size: 12px } .big { font-size: 24px }")
        val spans = paragraphSpans("""<p>a <span class="big">b</span></p>""", css)
        assertThat(spans.single().style.sizeRatio!!).isWithin(1e-4f).of(2.0f)
    }

    @Test
    fun `font-size in px with no baseline yields no size span`() {
        val css = CssRules.parse(".big { font-size: 24px }")
        val spans = paragraphSpans("""<p><span class="big">b</span></p>""", css)
        assertThat(spans).isEmpty()
    }

    @Test
    fun `font-size equal to baseline emits no redundant span`() {
        // 1em resolves to ratio 1.0 — identity — which must not become a span on every run.
        val css = CssRules.parse(".same { font-size: 1em }")
        val spans = paragraphSpans("""<p><span class="same">b</span></p>""", css)
        assertThat(spans).isEmpty()
    }

    // -- font-family monospace --

    @Test
    fun `monospace font-family maps to a monospace span`() {
        val css = CssRules.parse(".code { font-family: \"Courier New\", monospace }")
        val spans = paragraphSpans("""<p><span class="code">x</span></p>""", css)
        assertThat(spans).containsExactly(StyleSpan(0, 1, InlineStyle(monospace = true)))
    }

    // -- multi-property element pushes separate single-field styles through the stack --

    @Test
    fun `an element with bold underline and color pushes three separate spans`() {
        val css = CssRules.parse(".m { font-weight: bold; text-decoration: underline; color: #ff0000 }")
        val spans = paragraphSpans("""<p><span class="m">x</span></p>""", css)
        assertThat(spans.map { it.style.bold }).contains(true)
        assertThat(spans.map { it.style.underline }).contains(true)
        val gray = spans.mapNotNull { it.style.grayLevel }.single()
        assertThat(gray).isWithin(1e-4f).of(0.2126f)
        // Each is a distinct single-field style over the same [0,1) range.
        assertThat(spans).hasSize(3)
        assertThat(spans.map { it.start to it.end }.toSet()).containsExactly(0 to 1)
    }

    // -- text-align → BlockStyle.align --

    @Test
    fun `text-align center maps to BlockStyle align CENTER`() {
        val css = CssRules.parse(".c { text-align: center }")
        assertThat(paragraphStyle("""<p class="c">x</p>""", css).align)
            .isEqualTo(dev.reader.engine.TextAlign.CENTER)
    }

    @Test
    fun `text-align justify maps to BlockStyle align JUSTIFY`() {
        val css = CssRules.parse(".j { text-align: justify }")
        assertThat(paragraphStyle("""<p class="j">x</p>""", css).align)
            .isEqualTo(dev.reader.engine.TextAlign.JUSTIFY)
    }

    @Test
    fun `text-align inherits from an ancestor onto the block`() {
        val css = CssRules.parse("body { text-align: center }")
        assertThat(paragraphStyle("<p>x</p>", css).align)
            .isEqualTo(dev.reader.engine.TextAlign.CENTER)
    }

    // -- text-indent --

    @Test
    fun `text-indent in em maps to textIndentEm`() {
        val css = CssRules.parse(".i { text-indent: 2em }")
        assertThat(paragraphStyle("""<p class="i">x</p>""", css).textIndentEm!!)
            .isWithin(1e-4f).of(2.0f)
    }

    @Test
    fun `text-indent in px maps against the baseline`() {
        val css = CssRules.parse("body { font-size: 12px } .i { text-indent: 24px }")
        assertThat(paragraphStyle("""<p class="i">x</p>""", css).textIndentEm!!)
            .isWithin(1e-4f).of(2.0f)
    }

    @Test
    fun `text-indent in px with no baseline is null`() {
        val css = CssRules.parse(".i { text-indent: 24px }")
        assertThat(paragraphStyle("""<p class="i">x</p>""", css).textIndentEm).isNull()
    }

    // -- margin-top / margin-bottom --

    @Test
    fun `margin-top and margin-bottom map to their em fields`() {
        val css = CssRules.parse(".m { margin-top: 1em; margin-bottom: 2em }")
        val style = paragraphStyle("""<p class="m">x</p>""", css)
        assertThat(style.marginTopEm!!).isWithin(1e-4f).of(1.0f)
        assertThat(style.marginBottomEm!!).isWithin(1e-4f).of(2.0f)
    }

    @Test
    fun `margin shorthand expands into top and bottom`() {
        val css = CssRules.parse(".m { margin: 3em 0 }")
        val style = paragraphStyle("""<p class="m">x</p>""", css)
        assertThat(style.marginTopEm!!).isWithin(1e-4f).of(3.0f)
        assertThat(style.marginBottomEm!!).isWithin(1e-4f).of(3.0f)
    }

    // -- line-height --

    @Test
    fun `line-height unitless is taken as-is`() {
        val css = CssRules.parse(".l { line-height: 1.5 }")
        assertThat(paragraphStyle("""<p class="l">x</p>""", css).lineHeightMultiplier!!)
            .isWithin(1e-4f).of(1.5f)
    }

    @Test
    fun `line-height percent maps to a ratio`() {
        val css = CssRules.parse(".l { line-height: 150% }")
        assertThat(paragraphStyle("""<p class="l">x</p>""", css).lineHeightMultiplier!!)
            .isWithin(1e-4f).of(1.5f)
    }

    @Test
    fun `line-height normal is null`() {
        val css = CssRules.parse(".l { line-height: normal }")
        assertThat(paragraphStyle("""<p class="l">x</p>""", css).lineHeightMultiplier).isNull()
    }

    // -- inheritance carries an ancestor's emphasis onto descendant text (the Plan 3 TODO) --

    @Test
    fun `css italic on a blockquote reaches its paragraph children by inheritance`() {
        val css = CssRules.parse("blockquote { font-style: italic }")
        val blocks = parse("<blockquote><p>A</p><p>B</p></blockquote>", css)

        assertThat(blocks).hasSize(2)
        val a = (blocks[0] as Block.Quote).text
        assertThat(a.text).isEqualTo("A")
        assertThat(a.spans).containsExactly(StyleSpan(0, 1, InlineStyle(italic = true)))
        val b = (blocks[1] as Block.Quote).text
        assertThat(b.spans).containsExactly(StyleSpan(0, 1, InlineStyle(italic = true)))
    }

    @Test
    fun `an inherited style is not duplicated between a block and its inner run`() {
        // color inherits; the block paints it once over its whole text, and the nested
        // span must not re-push the identical gray it merely inherited.
        val css = CssRules.parse("body { color: #808080 }")
        val spans = paragraphSpans("<p>a <span>b</span> c</p>", css)
        assertThat(spans).hasSize(1)
        assertThat(spans.single().start).isEqualTo(0)
        assertThat(spans.single().end).isEqualTo(5)
        assertThat(spans.single().style.grayLevel!!).isWithin(1e-4f).of(128f / 255f)
    }

    // -- hostile / malformed values never throw --

    @Test
    fun `malformed property values degrade to null and never throw`() {
        val css = CssRules.parse(
            ".x { font-size: ; color: #zzz; letter-spacing: wat; " +
                "text-indent: 5furlongs; margin-top: nope; line-height: banana }",
        )
        val blocks = parse("""<p class="x">A <span class="x">word</span>.</p>""", css)
        val p = blocks.single() as Block.Paragraph
        // Nothing usable resolved, so no spans and an all-null block style — but no crash.
        assertThat(p.text.text).isEqualTo("A word.")
        assertThat(p.text.spans).isEmpty()
        assertThat(p.style).isEqualTo(dev.reader.engine.BlockStyle())
    }
}
