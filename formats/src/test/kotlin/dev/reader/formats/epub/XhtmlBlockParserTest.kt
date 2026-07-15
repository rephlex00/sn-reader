package dev.reader.formats.epub

import com.google.common.truth.Truth.assertThat
import dev.reader.engine.Block
import dev.reader.engine.InlineStyle
import dev.reader.engine.StyleSpan
import org.junit.Test

class XhtmlBlockParserTest {

    private val parser = XhtmlBlockParser()

    private fun parse(body: String) =
        parser.parse("<html><body>$body</body></html>", "OEBPS/text/ch1.xhtml")

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
}
