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

    // --- Fix wave A, I2: a selector that appears MORE THAN ONCE in the stylesheet must
    // keep each block at its own position in the cascade. Merging all of `.a`'s blocks
    // into one map replayed every `.a` declaration at the LAST block's index, letting an
    // early `.a { font-weight: bold }` beat a later `.b { font-weight: normal }`. ---

    @Test
    fun `a repeated class selector does not hoist its earlier declarations past a later rule`() {
        val css = CssRules.parse(".a { font-weight: bold }  .b { font-weight: normal }  .a { color: red }")

        assertThat(css.declarationsFor("span", listOf("a", "b"), null))
            .containsEntry("font-weight", "normal")
    }

    @Test
    fun `a repeated class selector still contributes all of its declarations`() {
        val css = CssRules.parse(".a { font-weight: bold }  .b { font-weight: normal }  .a { color: red }")
        val d = css.declarationsFor("span", listOf("a", "b"), null)

        assertThat(d).containsEntry("color", "red")
        // .a alone (no .b competing) still resolves bold from its first block.
        assertThat(css.declarationsFor("span", listOf("a"), null))
            .containsEntry("font-weight", "bold")
    }

    @Test
    fun `a repeated compound selector does not hoist its earlier declarations past a later rule`() {
        // Both competing selectors are `p.<class>`, so they share specificity (0,1,1) and
        // source order alone decides — the property this guards. (The original fixture pitted
        // `p.a` against a bare `.b`; under the real (id,class,type) cascade this task adds,
        // `p.a` (0,1,1) legitimately outranks `.b` (0,1,0) by specificity, which would mask
        // the hoisting bug rather than expose it. Equal-specificity operands keep the guard
        // meaningful: the later `p.a { color: red }` block must not drag its sibling block's
        // earlier `font-weight: bold` past the intervening `p.b { font-weight: normal }`.)
        val css = CssRules.parse("p.a { font-weight: bold }  p.b { font-weight: normal }  p.a { color: red }")

        assertThat(css.declarationsFor("p", listOf("a", "b"), null))
            .containsEntry("font-weight", "normal")
    }

    @Test
    fun `later block of a repeated class still beats an earlier competing class`() {
        val css = CssRules.parse(".a { font-style: normal }  .b { font-style: italic }  .a { font-style: oblique }")

        assertThat(css.declarationsFor("span", listOf("a", "b"), null))
            .containsEntry("font-style", "oblique")
    }

    @Test
    fun `selectors and properties are case-insensitive`() {
        val css = CssRules.parse(".X { FONT-STYLE: Italic }")
        assertThat(css.declarationsFor("span", listOf("x"), null))
            .containsEntry("font-style", "italic")
    }

    // --- Specificity: the real (id, class, type) rule, compared left to right. ---

    @Test
    fun `tag dot class beats a bare class regardless of source order`() {
        // p.foo appears FIRST; under flat tiering the later `.foo` would win by source
        // order. Real specificity: p.foo (0,1,1) outranks .foo (0,1,0), so blue wins.
        val css = CssRules.parse("p.foo { color: blue } .foo { color: red }")
        assertThat(css.resolve(listOf(ElementCtx("p", listOf("foo")))))
            .containsEntry("color", "blue")
    }

    @Test
    fun `a class beats a tag even when the tag rule is later`() {
        val css = CssRules.parse(".c { color: blue } p { color: red }")
        assertThat(css.resolve(listOf(ElementCtx("p", listOf("c")))))
            .containsEntry("color", "blue")
    }

    @Test
    fun `an id beats a class regardless of source order`() {
        val css = CssRules.parse("#x { color: blue } .c { color: red }")
        assertThat(css.resolve(listOf(ElementCtx("p", listOf("c"), id = "x"))))
            .containsEntry("color", "blue")
    }

    @Test
    fun `equal specificity is resolved by source order`() {
        val css = CssRules.parse(".a { color: red } .b { color: blue }")
        assertThat(css.resolve(listOf(ElementCtx("span", listOf("a", "b")))))
            .containsEntry("color", "blue")
    }

    @Test
    fun `inline style beats even an id rule`() {
        val css = CssRules.parse("#x { color: red }")
        assertThat(css.resolve(listOf(ElementCtx("p", id = "x", inlineStyle = "color: green"))))
            .containsEntry("color", "green")
    }

    // --- Inheritance down the ancestor chain. ---

    @Test
    fun `an inherited property reaches a descendant several levels down`() {
        val css = CssRules.parse("body { color: red; font-family: serif }")
        val chain = listOf(ElementCtx("body"), ElementCtx("div"), ElementCtx("p"), ElementCtx("span"))
        val computed = css.resolve(chain)
        assertThat(computed).containsEntry("color", "red")
        assertThat(computed).containsEntry("font-family", "serif")
    }

    @Test
    fun `a closer ancestor overrides a farther one for an inherited property`() {
        val css = CssRules.parse("body { color: red } div { color: blue }")
        assertThat(css.resolve(listOf(ElementCtx("body"), ElementCtx("div"), ElementCtx("span"))))
            .containsEntry("color", "blue")
    }

    @Test
    fun `an element's own declaration overrides an inherited one`() {
        val css = CssRules.parse("body { color: red } .self { color: green }")
        assertThat(css.resolve(listOf(ElementCtx("body"), ElementCtx("span", listOf("self")))))
            .containsEntry("color", "green")
    }

    @Test
    fun `a non-inherited property does not reach a descendant`() {
        val css = CssRules.parse(
            "div { margin-top: 5px; background-color: black; text-decoration: underline; padding: 3px }"
        )
        val computed = css.resolve(listOf(ElementCtx("div"), ElementCtx("p")))
        assertThat(computed.asMap()).doesNotContainKey("margin-top")
        assertThat(computed.asMap()).doesNotContainKey("background-color")
        assertThat(computed.asMap()).doesNotContainKey("text-decoration")
        assertThat(computed.asMap()).doesNotContainKey("padding")
    }

    @Test
    fun `a non-inherited property still applies to the element that declares it`() {
        val css = CssRules.parse("p { margin-top: 5px }")
        assertThat(css.resolve(listOf(ElementCtx("div"), ElementCtx("p"))))
            .containsEntry("margin-top", "5px")
    }

    // --- Descendant and child combinators. ---

    @Test
    fun `a descendant selector matches a nested element`() {
        val css = CssRules.parse("div p { color: red }")
        assertThat(css.resolve(listOf(ElementCtx("div"), ElementCtx("p"))))
            .containsEntry("color", "red")
    }

    @Test
    fun `a descendant selector matches at any depth`() {
        val css = CssRules.parse(".chapter em { font-style: italic }")
        val chain = listOf(
            ElementCtx("div", listOf("chapter")),
            ElementCtx("p"),
            ElementCtx("span"),
            ElementCtx("em"),
        )
        assertThat(css.resolve(chain)).containsEntry("font-style", "italic")
    }

    @Test
    fun `a descendant selector does not match without the ancestor`() {
        val css = CssRules.parse("div p { color: red }")
        // p under a section, no div anywhere in the chain.
        assertThat(css.resolve(listOf(ElementCtx("section"), ElementCtx("p"))).asMap())
            .doesNotContainKey("color")
    }

    @Test
    fun `a child selector matches only a direct child`() {
        val css = CssRules.parse("div > p { color: red }")
        assertThat(css.resolve(listOf(ElementCtx("div"), ElementCtx("p"))))
            .containsEntry("color", "red")
    }

    @Test
    fun `a child selector does not match a deeper descendant`() {
        val css = CssRules.parse("div > p { color: red }")
        // p's direct parent is section, not div.
        assertThat(css.resolve(listOf(ElementCtx("div"), ElementCtx("section"), ElementCtx("p"))).asMap())
            .doesNotContainKey("color")
    }

    @Test
    fun `an unmatched combinator rule does not drop its neighbours`() {
        val css = CssRules.parse("div p { color: red } .x { font-style: italic }")
        assertThat(css.resolve(listOf(ElementCtx("span", listOf("x")))))
            .containsEntry("font-style", "italic")
    }

    @Test
    fun `a descendant selector contributes at its own specificity`() {
        // `div p` is (0,0,2); a bare `.c` is (0,1,0) and outranks it.
        val css = CssRules.parse("div p { color: red } .c { color: blue }")
        assertThat(css.resolve(listOf(ElementCtx("div"), ElementCtx("p", listOf("c")))))
            .containsEntry("color", "blue")
    }

    // --- Shorthand expansion. ---

    @Test
    fun `margin shorthand expands to top and bottom only`() {
        val css = CssRules.parse("p { margin: 5px 10px 15px 20px }")
        val computed = css.resolve(listOf(ElementCtx("p")))
        assertThat(computed).containsEntry("margin-top", "5px")
        assertThat(computed).containsEntry("margin-bottom", "15px")
        // We deliberately do not honor left/right.
        assertThat(computed.asMap()).doesNotContainKey("margin-left")
        assertThat(computed.asMap()).doesNotContainKey("margin-right")
    }

    @Test
    fun `margin shorthand with one value applies to top and bottom`() {
        val css = CssRules.parse("p { margin: 8px }")
        val computed = css.resolve(listOf(ElementCtx("p")))
        assertThat(computed).containsEntry("margin-top", "8px")
        assertThat(computed).containsEntry("margin-bottom", "8px")
    }

    @Test
    fun `a later explicit margin-top overrides the shorthand within a block`() {
        val css = CssRules.parse("p { margin: 8px; margin-top: 0 }")
        assertThat(css.resolve(listOf(ElementCtx("p")))).containsEntry("margin-top", "0")
    }

    @Test
    fun `font shorthand expands to font-size and line-height only`() {
        val css = CssRules.parse("p { font: italic bold 12px/1.5 serif }")
        val computed = css.resolve(listOf(ElementCtx("p")))
        assertThat(computed).containsEntry("font-size", "12px")
        assertThat(computed).containsEntry("line-height", "1.5")
        // The rest of the shorthand is deliberately ignored, not half-parsed.
        assertThat(computed.asMap()).doesNotContainKey("font-style")
        assertThat(computed.asMap()).doesNotContainKey("font-weight")
        assertThat(computed.asMap()).doesNotContainKey("font-family")
    }

    @Test
    fun `font shorthand without a size is ignored entirely`() {
        val css = CssRules.parse("p { font: menu }")
        assertThat(css.resolve(listOf(ElementCtx("p"))).asMap()).doesNotContainKey("font-size")
    }

    // --- font-size composition down the chain. ---

    @Test
    fun `relative font sizes compose down the chain`() {
        val css = CssRules.parse("body { font-size: 1.2em } p { font-size: 0.5em }")
        val ratio = css.resolve(listOf(ElementCtx("body"), ElementCtx("p"))).fontSizeRatio
        assertThat(ratio).isNotNull()
        assertThat(ratio!!).isWithin(1e-4f).of(0.6f)
    }

    @Test
    fun `percentage font sizes compose like em`() {
        val css = CssRules.parse("body { font-size: 200% } span { font-size: 50% }")
        val ratio = css.resolve(listOf(ElementCtx("body"), ElementCtx("span"))).fontSizeRatio
        assertThat(ratio!!).isWithin(1e-4f).of(1.0f)
    }

    @Test
    fun `larger and smaller step from the parent's size, not the baseline`() {
        // Real CSS makes these parent-relative single steps: "larger" under a 2em body is ~2.4,
        // not a reset to 1.2. The xx-small..xx-large keywords, by contrast, ARE baseline resets.
        val css = CssRules.parse("body { font-size: 2em } span { font-size: larger }")
        val larger = css.resolve(listOf(ElementCtx("body"), ElementCtx("span"))).fontSizeRatio
        assertThat(larger!!).isWithin(1e-4f).of(2.4f)

        val css2 = CssRules.parse("body { font-size: 2em } span { font-size: smaller }")
        val smaller = css2.resolve(listOf(ElementCtx("body"), ElementCtx("span"))).fontSizeRatio
        assertThat(smaller!!).isWithin(1e-4f).of(1.66f)

        // No parent size: a bare "larger" steps from the baseline.
        val css3 = CssRules.parse("span { font-size: larger }")
        assertThat(css3.resolve(listOf(ElementCtx("span"))).fontSizeRatio!!).isWithin(1e-4f).of(1.2f)
    }

    @Test
    fun `an absolute font size leaves the composed ratio null but keeps the raw value`() {
        val css = CssRules.parse("body { font-size: 12pt }")
        val computed = css.resolve(listOf(ElementCtx("body")))
        assertThat(computed.fontSizeRatio).isNull()
        assertThat(computed).containsEntry("font-size", "12pt")
    }

    @Test
    fun `no font-size anywhere leaves the composed ratio null`() {
        val css = CssRules.parse("p { color: red }")
        assertThat(css.resolve(listOf(ElementCtx("p"))).fontSizeRatio).isNull()
    }

    @Test
    fun `resolve never throws on an empty chain`() {
        assertThat(CssRules.parse(".x { color: red }").resolve(emptyList()).asMap()).isEmpty()
    }
}
