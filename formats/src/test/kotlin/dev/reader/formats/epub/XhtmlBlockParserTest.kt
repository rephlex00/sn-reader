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
            StyleSpan(2, 6, InlineStyle.BOLD),
            StyleSpan(11, 17, InlineStyle.ITALIC),
        )
    }

    @Test
    fun `nested emphasis produces overlapping spans`() {
        val blocks = parse("<p><b>bold <i>both</i></b></p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("bold both")
        assertThat(text.spans).containsExactly(
            StyleSpan(5, 9, InlineStyle.ITALIC),
            StyleSpan(0, 9, InlineStyle.BOLD),
        )
    }

    @Test
    fun `collapses runs of whitespace without shifting spans`() {
        val blocks = parse("<p>A\n   spaced   <b>word</b>\t here.</p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("A spaced word here.")
        // "A spaced " is 9 chars, so the bold run must start at 9.
        assertThat(text.spans).containsExactly(StyleSpan(9, 13, InlineStyle.BOLD))
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
        assertThat(text.spans).containsExactly(StyleSpan(0, 4, InlineStyle.BOLD))
    }

    @Test
    fun `trailing space before closing italic tag does not throw`() {
        val blocks = parse("<p><i>The end. </i></p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("The end.")
        assertThat(text.spans).containsExactly(StyleSpan(0, 8, InlineStyle.ITALIC))
    }

    @Test
    fun `trailing space before closing bold tag with leading text does not throw`() {
        val blocks = parse("<p>x<b>y </b></p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("xy")
        assertThat(text.spans).containsExactly(StyleSpan(1, 2, InlineStyle.BOLD))
    }

    // --- Finding 2: inline style spans must survive an <img> flush mid-run ---

    @Test
    fun `bold span survives an image splitting the run in the middle`() {
        val blocks = parse("""<p>Hello <b>wo<img src="a.png"/>rld</b>!</p>""")

        assertThat(blocks).hasSize(3)
        val first = (blocks[0] as Block.Paragraph).text
        assertThat(first.text).isEqualTo("Hello wo")
        assertThat(first.spans).containsExactly(StyleSpan(6, 8, InlineStyle.BOLD))

        assertThat(blocks[1]).isInstanceOf(Block.Image::class.java)

        val second = (blocks[2] as Block.Paragraph).text
        assertThat(second.text).isEqualTo("rld!")
        assertThat(second.spans).containsExactly(StyleSpan(0, 3, InlineStyle.BOLD))
    }

    @Test
    fun `bold span reopens at zero after an image and covers the whole resumed run`() {
        val blocks = parse("""<p>a<b>b<img src="a.png"/>this is a long bold run</b></p>""")

        assertThat(blocks).hasSize(3)
        val after = (blocks[2] as Block.Paragraph).text
        assertThat(after.text).isEqualTo("this is a long bold run")
        assertThat(after.spans).containsExactly(StyleSpan(0, 23, InlineStyle.BOLD))
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
        assertThat(text.spans).containsExactly(StyleSpan(7, 11, InlineStyle.BOLD))
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
            StyleSpan(0, 2, InlineStyle.BOLD),
            StyleSpan(1, 2, InlineStyle.ITALIC),
        )

        assertThat(blocks[1]).isInstanceOf(Block.Image::class.java)

        val after = (blocks[2] as Block.Paragraph).text
        assertThat(after.text).isEqualTo("cd")
        assertThat(after.spans).containsExactly(
            StyleSpan(0, 1, InlineStyle.ITALIC),
            StyleSpan(0, 2, InlineStyle.BOLD),
        )
    }

    // --- Wave 2, minor 5: the `leading != 0` shift arm of build(), untested with an actual span ---

    @Test
    fun `a leading line break shifts a later span's offsets`() {
        val blocks = parse("<p><br/>Hi <b>bold</b></p>")
        val text = (blocks.single() as Block.Paragraph).text

        assertThat(text.text).isEqualTo("Hi bold")
        assertThat(text.spans).containsExactly(StyleSpan(3, 7, InlineStyle.BOLD))
    }

    // --- Wave 2, minor 6: zero-length style at a flush must not throw and must not appear ---

    @Test
    fun `a style open for zero characters at a flush produces no span for that piece`() {
        val blocks = parse("""<p><b><img src="x.png"/>x</b></p>""")

        assertThat(blocks).hasSize(2)
        assertThat(blocks[0]).isInstanceOf(Block.Image::class.java)

        val after = (blocks[1] as Block.Paragraph).text
        assertThat(after.text).isEqualTo("x")
        assertThat(after.spans).containsExactly(StyleSpan(0, 1, InlineStyle.BOLD))
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
        assertThat(text.spans).containsExactly(StyleSpan(7, 11, InlineStyle.BOLD))
    }

    @Test
    fun `mixed inline content directly in body becomes one paragraph with its span intact`() {
        val blocks = parser.parse("<html><body>Hello <b>world</b>!</body></html>", "OEBPS/text/ch1.xhtml")

        assertThat(blocks).hasSize(1)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.text).isEqualTo("Hello world!")
        assertThat(text.spans).containsExactly(StyleSpan(6, 11, InlineStyle.BOLD))
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
        assertThat(text.spans).containsExactly(StyleSpan(2, 6, InlineStyle.ITALIC))
    }

    @Test
    fun `numeric font-weight of 600 or more is bold`() {
        val css = CssRules.parse(".b { font-weight: 700 }")
        val blocks = parse("""<p>A <span class="b">word</span>.</p>""", css)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.spans).containsExactly(StyleSpan(2, 6, InlineStyle.BOLD))
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
        assertThat(text.spans).containsExactly(StyleSpan(2, 6, InlineStyle.ITALIC))
    }

    @Test
    fun `inline style attribute produces emphasis`() {
        val blocks = parse("""<p>A <span style="font-style:italic">word</span>.</p>""")
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.spans).containsExactly(StyleSpan(2, 6, InlineStyle.ITALIC))
    }

    @Test
    fun `css emphasis and tag emphasis combine`() {
        val css = CssRules.parse(".italic { font-style: italic }")
        val blocks = parse("""<p><b class="italic">x</b></p>""", css)
        val text = (blocks.single() as Block.Paragraph).text
        assertThat(text.text).isEqualTo("x")
        assertThat(text.spans).containsExactly(
            StyleSpan(0, 1, InlineStyle.BOLD),
            StyleSpan(0, 1, InlineStyle.ITALIC),
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
        assertThat(text.spans).containsExactly(StyleSpan(2, 6, InlineStyle.ITALIC))
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
        assertThat(h.text.spans).containsExactly(StyleSpan(2, 6, InlineStyle.ITALIC))
    }

    @Test
    fun `semantic h1 still wins without any css`() {
        val blocks = parse("<h1>Title</h1>", CssRules.EMPTY)
        assertThat((blocks.single() as Block.Heading).level).isEqualTo(1)
    }
}
