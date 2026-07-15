package dev.reader.formats.epub

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CssTest {

    @Test
    fun `resolves a class rule`() {
        val css = CssRules.parse(".italic { font-style: italic }")
        assertThat(css.declarationsFor("span", listOf("italic"), null))
            .containsEntry("font-style", "italic")
    }

    @Test
    fun `resolves a tag rule`() {
        val css = CssRules.parse("p { font-size: 1em; }")
        assertThat(css.declarationsFor("p", emptyList(), null)).containsEntry("font-size", "1em")
    }

    @Test
    fun `class beats tag`() {
        val css = CssRules.parse("p { font-weight: normal } .b { font-weight: bold }")
        assertThat(css.declarationsFor("p", listOf("b"), null))
            .containsEntry("font-weight", "bold")
    }

    @Test
    fun `inline style beats class`() {
        val css = CssRules.parse(".x { font-style: italic }")
        assertThat(css.declarationsFor("span", listOf("x"), "font-style: normal"))
            .containsEntry("font-style", "normal")
    }

    @Test
    fun `later rule wins among equals`() {
        val css = CssRules.parse(".x { color: red } .x { color: blue }")
        assertThat(css.declarationsFor("span", listOf("x"), null)).containsEntry("color", "blue")
    }

    @Test
    fun `handles a comma-separated selector list`() {
        val css = CssRules.parse("h1, .title { font-weight: bold }")
        assertThat(css.declarationsFor("h1", emptyList(), null)).containsEntry("font-weight", "bold")
        assertThat(css.declarationsFor("p", listOf("title"), null)).containsEntry("font-weight", "bold")
    }

    @Test
    fun `handles tag dot class selectors`() {
        val css = CssRules.parse("p.lead { font-size: 1.4em }")
        assertThat(css.declarationsFor("p", listOf("lead"), null)).containsEntry("font-size", "1.4em")
        // Must not apply to a different tag carrying the same class.
        assertThat(css.declarationsFor("span", listOf("lead"), null)).doesNotContainKey("font-size")
    }

    @Test
    fun `multiple classes on one element all apply`() {
        val css = CssRules.parse(".a { font-style: italic } .b { font-weight: bold }")
        val d = css.declarationsFor("span", listOf("a", "b"), null)
        assertThat(d).containsEntry("font-style", "italic")
        assertThat(d).containsEntry("font-weight", "bold")
    }

    @Test
    fun `conflicting classes resolve by stylesheet order not classes list order`() {
        // .a comes after .b in the stylesheet, so .a must win regardless of
        // which order the caller happens to list the classes in.
        val css = CssRules.parse(".b { font-style: normal } .a { font-style: italic }")
        assertThat(css.declarationsFor("span", listOf("a", "b"), null))
            .containsEntry("font-style", "italic")
        assertThat(css.declarationsFor("span", listOf("b", "a"), null))
            .containsEntry("font-style", "italic")
    }

    @Test
    fun `stray closing brace does not drop the following rule`() {
        val css = CssRules.parse("p { color: red } } .x { font-style: italic }")
        assertThat(css.declarationsFor("span", listOf("x"), null))
            .containsEntry("font-style", "italic")
    }

    @Test
    fun `class names may start with underscore or hyphen`() {
        val css = CssRules.parse("._chapter { font-style: italic } .-title { font-weight: bold }")
        assertThat(css.declarationsFor("span", listOf("_chapter"), null))
            .containsEntry("font-style", "italic")
        assertThat(css.declarationsFor("span", listOf("-title"), null))
            .containsEntry("font-weight", "bold")
    }

    @Test
    fun `survives a nested-brace at-rule`() {
        val css = CssRules.parse("@media screen { .x { color: red } } .y { font-style: italic }")
        assertThat(css.declarationsFor("span", listOf("y"), null))
            .containsEntry("font-style", "italic")
        // The nested .x rule lives inside the skipped @media block and must
        // not leak out as if it were a top-level rule.
        assertThat(css.declarationsFor("span", listOf("x"), null)).doesNotContainKey("color")
    }

    @Test
    fun `strips comments`() {
        val css = CssRules.parse("/* a note */ .x { font-style: italic } /* another */")
        assertThat(css.declarationsFor("span", listOf("x"), null))
            .containsEntry("font-style", "italic")
    }

    @Test
    fun `strips important`() {
        val css = CssRules.parse(".x { font-weight: bold !important }")
        assertThat(css.declarationsFor("span", listOf("x"), null)).containsEntry("font-weight", "bold")
    }

    @Test
    fun `ignores at-rules without dropping the rules around them`() {
        val css = CssRules.parse(
            "@font-face { font-family: Foo; src: url(x.ttf) } .x { font-style: italic }"
        )
        assertThat(css.declarationsFor("span", listOf("x"), null))
            .containsEntry("font-style", "italic")
    }

    @Test
    fun `ignores selectors it does not understand`() {
        val css = CssRules.parse("div > p:first-child { color: red } .x { font-style: italic }")
        assertThat(css.declarationsFor("span", listOf("x"), null))
            .containsEntry("font-style", "italic")
        assertThat(css.declarationsFor("p", emptyList(), null)).doesNotContainKey("color")
    }

    @Test
    fun `garbage never throws`() {
        for (bad in listOf("", "{{{", "}", ".x {", ".x { font-style", "@media screen {")) {
            assertThat(CssRules.parse(bad).declarationsFor("p", listOf("x"), null)).isNotNull()
        }
    }

    @Test
    fun `unterminated block does not swallow earlier rules`() {
        val css = CssRules.parse(".good { font-style: italic } .bad { font-weight:")
        assertThat(css.declarationsFor("span", listOf("good"), null))
            .containsEntry("font-style", "italic")
    }

    @Test
    fun `EMPTY resolves to nothing`() {
        assertThat(CssRules.EMPTY.declarationsFor("p", listOf("x"), "font-style: italic"))
            .containsEntry("font-style", "italic")
        assertThat(CssRules.EMPTY.declarationsFor("p", listOf("x"), null)).isEmpty()
    }

    @Test
    fun `selectors and properties are case-insensitive`() {
        val css = CssRules.parse(".X { FONT-STYLE: Italic }")
        assertThat(css.declarationsFor("span", listOf("x"), null))
            .containsEntry("font-style", "italic")
    }
}
