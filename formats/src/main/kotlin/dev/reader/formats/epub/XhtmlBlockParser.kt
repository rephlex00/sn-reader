package dev.reader.formats.epub

import dev.reader.engine.Block
import dev.reader.engine.InlineStyle
import dev.reader.engine.StyleSpan
import dev.reader.engine.StyledText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Reduces chapter XHTML to the [Block] whitelist. Publisher CSS, embedded fonts and
 * every unlisted element are discarded — the reader's own typography is applied later,
 * from RenderConfig. The one exception is [CssRules]: it is mined for exactly two
 * semantic signals — is a run italic/bold, and is a short paragraph actually a heading —
 * never for presentation. See [CssRules]'s kdoc for why that split exists.
 *
 * Parsed with jsoup's lenient HTML parser rather than its XML parser: real-world EPUBs
 * are frequently not well-formed XML, and a book must open anyway.
 */
class XhtmlBlockParser {

    /**
     * @param css Resolves emphasis (always applied) and, when [inferHeadings] is set,
     *   heading candidacy for CSS-encoded structure. Defaults to [CssRules.EMPTY] so tag
     *   emphasis (`<b>`, `<i>`, ...) still works with no stylesheet at all.
     * @param inferHeadings Whether a short, visually-heading-like `<p>`/`<div>` is
     *   promoted to [Block.Heading]. Independent of emphasis, which is always on.
     */
    fun parse(
        xhtml: String,
        chapterPath: String,
        css: CssRules = CssRules.EMPTY,
        inferHeadings: Boolean = true,
    ): List<Block> = parse(Jsoup.parse(xhtml), chapterPath, css, inferHeadings)

    /**
     * Same as the `String` overload, but takes an already-parsed [Document] so a caller
     * that also needs to inspect the document for something else (e.g. [EpubDocument]
     * mining it for `<link rel="stylesheet">`/`<style>` references) doesn't pay for a
     * second `Jsoup.parse` of the same chapter.
     */
    fun parse(
        doc: Document,
        chapterPath: String,
        css: CssRules = CssRules.EMPTY,
        inferHeadings: Boolean = true,
    ): List<Block> {
        val body = doc.body()
        val out = mutableListOf<Block>()
        // body is itself just another container: route it through the same childNodes()
        // walk as any other transparent container, so bare text sitting directly inside
        // <body> (no wrapping element) isn't dropped the way children()-only iteration
        // would drop it. See visitContainerChildren.
        visitContainerChildren(body, chapterPath, out, css, inferHeadings)
        return out
    }

    /**
     * Dispatches [el] into [out], then gates that content behind a page break: a
     * break-requesting element that ends up contributing nothing (an empty div, say)
     * must not leave a stray `Block.PageBreak` with no content after it, and nested
     * break-requesting containers wrapping the same content must not each add their own
     * break. [addBlock] enforces both by refusing to stack a `PageBreak` directly on
     * top of another one already at the tail of [out].
     */
    private fun visit(el: Element, chapterPath: String, out: MutableList<Block>, css: CssRules, inferHeadings: Boolean) {
        val produced = mutableListOf<Block>()
        dispatch(el, chapterPath, produced, css, inferHeadings)
        if (produced.isEmpty()) return

        if (requestsPageBreak(el)) addBlock(out, Block.PageBreak)
        produced.forEach { addBlock(out, it) }
    }

    private fun addBlock(out: MutableList<Block>, block: Block) {
        if (block == Block.PageBreak && out.lastOrNull() == Block.PageBreak) return
        out += block
    }

    private fun dispatch(el: Element, chapterPath: String, out: MutableList<Block>, css: CssRules, inferHeadings: Boolean) {
        when (el.tagName().lowercase()) {
            "p" -> emitTextBlock(el, chapterPath, out, css) { text -> paragraphOrHeading(el, text, css, inferHeadings) }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = el.tagName()[1].digitToInt()
                emitTextBlock(el, chapterPath, out, css) { Block.Heading(level, it) }
            }

            "blockquote" -> emitBlockquote(el, chapterPath, out, css, inferHeadings)

            "ul" -> listItems(el).forEach { li ->
                emitTextBlock(li, chapterPath, out, css) { Block.ListItem(it, ordinal = null) }
            }

            "ol" -> listItems(el).forEachIndexed { index, li ->
                emitTextBlock(li, chapterPath, out, css) { Block.ListItem(it, ordinal = index + 1) }
            }

            "img" -> emitImage(el, chapterPath, out)

            "style", "script", "head", "title", "link", "meta" -> Unit

            // Containers are transparent: keep looking for whitelisted content inside.
            else -> visitContainerChildren(el, chapterPath, out, css, inferHeadings)
        }
    }

    /**
     * Walks [el]'s child *nodes* (not just its child *elements*) so that bare text sitting
     * directly inside a transparent container — no wrapping element — isn't dropped, e.g.
     * the "tail text" after a `<p>` inside a `<div>`, or the whole chapter when a chapter's
     * `<body>` has no wrapping element at all. Shared by [dispatch]'s container branch and
     * by [parse], since `<body>` is itself just another container: `Elements`-only iteration
     * (`children()`) would silently drop bare text in either place the same way.
     *
     * `flatten = false`: a recognized block-level child (`<p>`, a heading, `<ul>`, `<img>`,
     * a nested `<blockquote>`, ...) keeps its own natural [Block] type by flushing whatever
     * bare text ran before it and then re-entering the normal [visit] dispatch — this is
     * what lets a heading nested three `<div>`s deep still come out as `Block.Heading`
     * rather than degrading to plain text.
     *
     * The factory bound here is [el]'s own — a `<div class="chapterTitle">` that owns its
     * text directly (no wrapping `<p>`) is just as eligible for heading promotion as a
     * literal `<p class="...">`, per [paragraphOrHeading].
     */
    private fun visitContainerChildren(
        el: Element,
        chapterPath: String,
        out: MutableList<Block>,
        css: CssRules,
        inferHeadings: Boolean,
    ) {
        emitRun(el, chapterPath, out, { text -> paragraphOrHeading(el, text, css, inferHeadings) }, flatten = false, css, inferHeadings)
    }

    /**
     * A blockquote is walked in document order rather than filtered down to an allowlist
     * of child tags: an allowlist makes every *other* child — lead text before the first
     * `<p>`, a heading, an `<img>` — unreachable and silently dropped the moment any
     * allowed child exists. Instead: bare text and inline runs (`<b>`, `<i>`, ...)
     * accumulate into a running `Quote`; a block-level child (`<p>`, any heading) flushes
     * that run and becomes its own `Quote`, still splitting around any `<img>` nested
     * inside it via the normal [emitTextBlock]/[walkInline] machinery; an `<img>` flushes
     * and becomes its own `Block.Image`; any other container (`<div>`, etc.) flushes and
     * is recursed into with this same walk — so `<div><p>A</p><p>B</p></div>` still
     * yields two Quotes rather than re-collapsing into one concatenated "AB".
     *
     * This shares [walkRun] with [visitContainerChildren], differing only in the `Block`
     * factory (`Block.Quote` here) and in `flatten = true`: unlike a plain container, a
     * blockquote *flattens* every text-bearing descendant — including headings — down to
     * `Block.Quote`, rather than letting them keep their natural type. Heading inference
     * never applies here (only a `<p>`/`<div>` that would become a `Paragraph` is
     * eligible), so the factory is fixed regardless of [inferHeadings]. `<ul>`/`<ol>` are
     * the one exception: routing them through normal dispatch is what they'd do anyway
     * (there's no "Quote" equivalent of a list item), so nesting a list in a blockquote
     * now correctly keeps `Block.ListItem` with its ordinal instead of losing it.
     */
    private fun emitBlockquote(el: Element, chapterPath: String, out: MutableList<Block>, css: CssRules, inferHeadings: Boolean) {
        emitRun(el, chapterPath, out, { Block.Quote(it) }, flatten = true, css, inferHeadings)
    }

    /**
     * Builds the running [InlineBuilder] + [flushClosure] pair that [walkRun] shares
     * between [visitContainerChildren] and [emitBlockquote], then flushes once more after
     * the walk completes to catch whatever ran to the end of [el] unflushed.
     */
    private fun emitRun(
        el: Element,
        chapterPath: String,
        out: MutableList<Block>,
        factory: (StyledText) -> Block,
        flatten: Boolean,
        css: CssRules,
        inferHeadings: Boolean,
    ) {
        val builder = InlineBuilder()
        val flush = flushClosure(builder, out, factory)
        walkRun(el, chapterPath, builder, flush, out, factory, flatten, css, inferHeadings)
        flush()
    }

    private fun walkRun(
        el: Element,
        chapterPath: String,
        builder: InlineBuilder,
        flush: () -> Unit,
        out: MutableList<Block>,
        factory: (StyledText) -> Block,
        flatten: Boolean,
        css: CssRules,
        inferHeadings: Boolean,
    ) {
        el.childNodes().forEach { node ->
            when (node) {
                is TextNode -> builder.appendText(node.wholeText)
                is Element -> when (val tag = node.tagName().lowercase()) {
                    "br" -> builder.appendLineBreak()
                    "style", "script" -> Unit
                    "img" -> {
                        flush()
                        if (flatten) emitImage(node, chapterPath, out) else visit(node, chapterPath, out, css, inferHeadings)
                    }
                    "p", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        flush()
                        if (flatten) {
                            emitTextBlock(node, chapterPath, out, css, factory)
                        } else {
                            visit(node, chapterPath, out, css, inferHeadings)
                        }
                    }
                    else -> {
                        // CSS emphasis is only ever taken as an inline run for elements
                        // that are inline BY TAG, or that carry no block-level descendant
                        // of their own — never for an arbitrary container. A publisher
                        // stylesheet routinely puts `font-style: italic` on a bare tag
                        // selector (`blockquote { ... }`, `div.epigraph { ... }`); treating
                        // every such element as one inline run would flatten its <p>/
                        // heading/list children into a single merged block, silently
                        // losing their structure. Containers that DO qualify (a `<span>`,
                        // or a `<div>` whose only content is text/inline elements) still
                        // take the inline path exactly as before.
                        val styles = effectiveStyles(node, css)
                        val emphasiseAsInlineRun = styles.isNotEmpty() &&
                            (isInlineByTag(tag) || !containsBlockLevelDescendant(node))
                        when {
                            emphasiseAsInlineRun -> {
                                // Skip pushing (and later popping) any style already open
                                // from an ancestor — e.g. `<i><span class="italic">` — so
                                // the same InlineStyle doesn't produce two identical,
                                // redundant spans over the same text.
                                val newStyles = styles.filterNot { builder.hasOpenStyle(it) }
                                newStyles.forEach { builder.pushStyle(it) }
                                node.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out, css) }
                                newStyles.forEach { builder.popStyle() }
                            }
                            // Lists have no "Quote" equivalent, so even a flattening walk
                            // routes them through normal dispatch (see emitBlockquote's kdoc).
                            flatten && tag != "ul" && tag != "ol" -> {
                                flush()
                                walkRun(node, chapterPath, builder, flush, out, factory, flatten, css, inferHeadings)
                            }
                            else -> {
                                flush()
                                visit(node, chapterPath, out, css, inferHeadings)
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun listItems(el: Element) = el.children().filter { it.tagName().lowercase() == "li" }

    private fun requestsPageBreak(el: Element): Boolean {
        val style = el.attr("style").lowercase().replace(" ", "")
        return "page-break-before:always" in style || "break-before:page" in style
    }

    private fun emitImage(el: Element, chapterPath: String, out: MutableList<Block>) {
        val src = el.attr("src")
        if (src.isBlank()) return
        val resolved = resolveHref(chapterPath, src)
        if (resolved.isNotBlank()) out += Block.Image(resolved)
    }

    /**
     * Walks [el]'s inline content into [factory]'s block type (Paragraph/Heading/Quote/
     * ListItem), via [walkInline] — see its kdoc for how a nested `<img>` splits the run.
     */
    private fun emitTextBlock(
        el: Element,
        chapterPath: String,
        out: MutableList<Block>,
        css: CssRules,
        factory: (StyledText) -> Block,
    ) {
        val builder = InlineBuilder()
        val flush = flushClosure(builder, out, factory)
        el.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out, css) }
        flush()
    }

    /**
     * The flush step shared by every accumulator in this file ([emitTextBlock], and
     * [emitRun] on behalf of both [visitContainerChildren] and [emitBlockquote]): close
     * every style still open at this boundary so the block being flushed keeps it (see the
     * `<img>` case in [walkInline] below and [InlineBuilder]'s kdoc), build, hand the result
     * to [factory] unless it's blank, then reset for whatever follows.
     */
    private fun flushClosure(
        builder: InlineBuilder,
        out: MutableList<Block>,
        factory: (StyledText) -> Block,
    ): () -> Unit = {
        builder.closeOpenStyles()
        val text = builder.build()
        if (text.text.isNotBlank()) out += factory(text)
        builder.reset()
    }

    /**
     * An `<img>` nested inside a text run — real EPUBs routinely wrap a figure in `<p>`,
     * including inside a `<b>`/`<i>` run — is not text: it splits the surrounding run,
     * flushing whatever text came before it as one block, emitting `Block.Image` as its
     * own block, then resuming text accumulation from a fresh (zero-based) offset for
     * whatever follows. Open inline styles are tracked on [builder] itself (not as a
     * local `start` here) precisely so a `flush()` triggered mid-recursion — from an
     * `<img>` nested inside this element — doesn't strand a stale offset: the builder
     * closes and rebases them itself.
     */
    private fun walkInline(
        node: Node,
        chapterPath: String,
        builder: InlineBuilder,
        flush: () -> Unit,
        out: MutableList<Block>,
        css: CssRules,
    ) {
        when (node) {
            is TextNode -> builder.appendText(node.wholeText)

            is Element -> when (val tag = node.tagName().lowercase()) {
                "br" -> builder.appendLineBreak()
                "style", "script" -> Unit
                "img" -> {
                    flush()
                    emitImage(node, chapterPath, out)
                }
                else -> {
                    // See the matching comment in walkRun: don't re-push a style an
                    // ancestor already has open.
                    val styles = effectiveStyles(node, css).filterNot { builder.hasOpenStyle(it) }
                    styles.forEach { builder.pushStyle(it) }
                    node.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out, css) }
                    styles.forEach { builder.popStyle() }
                }
            }
        }
    }

    /**
     * True for a tag that is inline by nature — safe to treat wholesale as one text run
     * even when CSS gives it emphasis. Deliberately does NOT include container tags like
     * `div`/`section`/`blockquote`/`td`: those may or may not hold block-level content,
     * so [containsBlockLevelDescendant] decides for them instead.
     */
    private fun isInlineByTag(tag: String): Boolean = tag in INLINE_TAGS

    /**
     * True if [el] itself, or anything nested inside it, is one of the tags [dispatch]
     * gives its own [Block]: a `<p>`/heading/`<blockquote>`/`<ul>`/`<ol>`/`<img>`. Used to
     * decide whether a CSS-emphasised container is safe to flatten into one inline run
     * (no such descendant) or must instead recurse so those children keep their own
     * [Block] type (see the call site in [walkRun]).
     */
    private fun containsBlockLevelDescendant(el: Element): Boolean {
        if (el.tagName().lowercase() in BLOCK_LEVEL_TAGS) return true
        return el.children().any { containsBlockLevelDescendant(it) }
    }

    private fun inlineStyleOf(tag: String): InlineStyle? = when (tag) {
        "b", "strong" -> InlineStyle.BOLD
        "i", "em", "cite", "dfn" -> InlineStyle.ITALIC
        "code", "kbd", "samp", "tt" -> InlineStyle.MONOSPACE
        else -> null
    }

    /**
     * An element's effective emphasis is its tag mapping (`b`/`strong`/`i`/`em`/`code`...)
     * **unioned** with what [css] resolves for it — never replaced by it. A calibre-style
     * `<span class="italic">` and a hand-authored `<i>` both recover the same authorial
     * intent; `<b class="italic">` recovers both. Each style pushed here goes through
     * [InlineBuilder]'s own zero-length guard when popped, exactly like tag-based
     * emphasis — a `<span class="italic"></span>` must not throw.
     */
    private fun effectiveStyles(el: Element, css: CssRules): List<InlineStyle> {
        val styles = LinkedHashSet<InlineStyle>()
        inlineStyleOf(el.tagName().lowercase())?.let { styles += it }
        styles += cssEmphasisStyles(declarationsFor(el, css))
        return styles.toList()
    }

    private fun declarationsFor(el: Element, css: CssRules): Map<String, String> =
        css.declarationsFor(el.tagName().lowercase(), el.classNames().toList(), el.attr("style").ifBlank { null })

    private fun cssEmphasisStyles(declarations: Map<String, String>): Set<InlineStyle> {
        val out = mutableSetOf<InlineStyle>()
        if (declarations["font-style"] in ITALIC_KEYWORDS) out += InlineStyle.ITALIC
        if (isBoldValue(declarations["font-weight"])) out += InlineStyle.BOLD
        return out
    }

    private fun isBoldValue(value: String?): Boolean {
        if (value == null) return false
        if (value == "bold" || value == "bolder") return true
        return (value.toIntOrNull() ?: return false) >= 600
    }

    /**
     * Promotes [text] from a plain paragraph to a [Block.Heading] when [inferHeadings] is
     * on and [el]'s resolved style says it visually reads as one — see [headingLevelFor].
     * This is the ONLY place a [Block.Heading] is produced from non-semantic markup; a
     * literal `<h1>`..`<h6>` always wins regardless of CSS (handled directly in
     * [dispatch]) and is never routed through here.
     */
    private fun paragraphOrHeading(el: Element, text: StyledText, css: CssRules, inferHeadings: Boolean): Block {
        if (inferHeadings) {
            headingLevelFor(el, text, css)?.let { return Block.Heading(it, text) }
        }
        return Block.Paragraph(text)
    }

    /**
     * A `<p>`/`<div>` reads as a heading when its text is short (≤120 chars, non-empty)
     * AND its resolved style is visually heading-like: either a font-size ratio ≥ 1.2
     * relative to the body baseline, or centered text that is also bold (with no size
     * signal, that combination lands at level 3). Returns null — "no heading signal" —
     * rather than guessing, so an unusable size (see [sizeRatioFor]) never promotes.
     *
     * The style consulted is [effectiveHeadingDeclarations], not just [el]'s own: a
     * calibre-style export routinely puts the actual size/weight several wrapper elements
     * below the `<p>` (`<p class="calibre_7"><a><span class="calibre2">Title</span></a>`),
     * with the `<p>` itself carrying only layout declarations like `text-align: center`.
     */
    private fun headingLevelFor(el: Element, text: StyledText, css: CssRules): Int? {
        if (text.text.isEmpty() || text.text.length > 120) return null

        val declarations = effectiveHeadingDeclarations(el, css)
        val ratio = declarations["font-size"]?.let { sizeRatioFor(it, css) }
        if (ratio != null && ratio >= 1.2f) return levelForRatio(ratio)

        val centered = declarations["text-align"] == "center"
        if (centered && isBoldValue(declarations["font-weight"])) return 3

        return null
    }

    /**
     * Merges [el]'s own resolved declarations with those of the sole text-bearing
     * descendant chain beneath it (see [soleTextBearingChain]): descendant declarations
     * are layered on top (inner wins on a shared property, e.g. `font-size`), the same
     * direction real CSS inheritance would resolve it in. A property only [el] itself
     * sets — commonly `text-align: center` on the `<p>`, with no equivalent on the
     * wrapper chain below it — is still visible in the result even though the chain never
     * repeats it.
     */
    private fun effectiveHeadingDeclarations(el: Element, css: CssRules): Map<String, String> {
        val merged = LinkedHashMap(declarationsFor(el, css))
        for (descendant in soleTextBearingChain(el)) {
            merged.putAll(declarationsFor(descendant, css))
        }
        return merged
    }

    /**
     * The chain of wrapper elements strictly below [el] that, between them, carry ALL of
     * [el]'s text and nothing else - the `<a>`/`<span>` nesting calibre (and many other)
     * EPUB exports wrap a chapter title's actual text in. Stops descending the moment a
     * level has any text of its own directly inside it (not delegated to a single child),
     * or doesn't have exactly one element child to descend into - a paragraph with real
     * mixed inline content (prose plus an incidental `<span>`) never engages this at all.
     */
    private fun soleTextBearingChain(el: Element): List<Element> {
        val chain = mutableListOf<Element>()
        var current = el
        while (true) {
            val hasOwnText = current.childNodes().any { it is TextNode && it.wholeText.isNotBlank() }
            if (hasOwnText) break
            val children = current.children()
            if (children.size != 1) break
            current = children[0]
            // Not `chain += current`: Element implements Iterable<Element> (over its own
            // children), which makes `+=` ambiguous between appending current itself vs.
            // spreading its children — .add() is unambiguous.
            chain.add(current)
        }
        return chain
    }

    private fun levelForRatio(ratio: Float): Int = when {
        ratio >= 2.0f -> 1
        ratio >= 1.6f -> 2
        ratio >= 1.4f -> 3
        else -> 4
    }

    /**
     * Resolves a `font-size` value to a ratio relative to normal body text. `em`/`rem`,
     * `%`, and the CSS size keywords are self-contained and always usable. `px`/`pt` are
     * only meaningful relative to a baseline — see [baselineFontSizePx] — because this
     * reader has no notion of a root/browser default font size; without one, an absolute
     * size is unusable and this returns 1.0 ("no signal"), never a guess.
     */
    private fun sizeRatioFor(rawValue: String, css: CssRules): Float? {
        relativeSizeRatio(rawValue)?.let { return it }
        val px = absolutePx(rawValue) ?: return null
        val baselinePx = baselineFontSizePx(css) ?: return 1.0f
        return px / baselinePx
    }

    private fun relativeSizeRatio(value: String): Float? {
        emOrRemRegex.matchEntire(value)?.let { return it.groupValues[1].toFloatOrNull() }
        percentRegex.matchEntire(value)?.let { m -> return m.groupValues[1].toFloatOrNull()?.div(100f) }
        return keywordSizeRatios[value]
    }

    private fun absolutePx(value: String): Float? {
        pxRegex.matchEntire(value)?.let { return it.groupValues[1].toFloatOrNull() }
        ptRegex.matchEntire(value)?.let { m -> return m.groupValues[1].toFloatOrNull()?.times(4f / 3f) }
        return null
    }

    /**
     * The absolute pixel baseline that a `px`/`pt` font-size is judged against: the
     * resolved `font-size` of `body`, else of `p`, else no baseline at all. Itself must
     * resolve to an absolute size (not `1em`/`100%`/a keyword) — a relative baseline has
     * no fixed meaning here — so [absolutePx] is reused rather than [relativeSizeRatio].
     */
    private fun baselineFontSizePx(css: CssRules): Float? {
        val bodySize = css.declarationsFor("body", emptyList(), null)["font-size"]
        val pSize = css.declarationsFor("p", emptyList(), null)["font-size"]
        val raw = bodySize ?: pSize ?: return null
        return absolutePx(raw)
    }

    companion object {
        private val ITALIC_KEYWORDS = setOf("italic", "oblique")

        // See isInlineByTag/containsBlockLevelDescendant.
        private val INLINE_TAGS = setOf(
            "span", "a", "small", "sub", "sup", "font",
            "b", "strong", "i", "em", "cite", "dfn",
            "code", "kbd", "samp", "tt",
        )
        private val BLOCK_LEVEL_TAGS = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "img",
        )

        private val emOrRemRegex = Regex("^(-?\\d*\\.?\\d+)(em|rem)$")
        private val percentRegex = Regex("^(-?\\d*\\.?\\d+)%$")
        private val pxRegex = Regex("^(-?\\d*\\.?\\d+)px$")
        private val ptRegex = Regex("^(-?\\d*\\.?\\d+)pt$")

        // Conventional CSS absolute-size-keyword ratios (CSS3 §Absolute Size Keywords),
        // relative to "medium" = 1. "larger"/"smaller" are the relative-keyword ratios
        // browsers conventionally use for a single step up/down from the parent.
        private val keywordSizeRatios = mapOf(
            "xx-small" to 0.6f,
            "x-small" to 0.75f,
            "small" to 0.889f,
            "medium" to 1.0f,
            "large" to 1.2f,
            "x-large" to 1.5f,
            "xx-large" to 2.0f,
            "larger" to 1.2f,
            "smaller" to 0.83f,
        )
    }
}

/**
 * Accumulates inline text, collapsing whitespace as it appends so that recorded span
 * offsets always index the final string. Collapsing afterwards would silently shift
 * every span.
 *
 * Open inline styles (`<b>`, `<i>`, ...) are tracked here as a [openStyles] stack rather
 * than as a local variable in the caller's recursion, so that an `<img>` nested inside a
 * style run can flush the builder (resetting it to offset 0) without stranding a stale
 * start offset in some outer stack frame: [closeOpenStyles] closes every currently-open
 * style at the flush boundary, and [reset] rebases each one back to 0 so the run that
 * resumes after the image keeps the style from its first character.
 */
private class InlineBuilder {
    private class OpenStyle(val style: InlineStyle, var start: Int)

    private val sb = StringBuilder()
    private val spans = mutableListOf<StyleSpan>()
    private val openStyles = ArrayDeque<OpenStyle>()

    fun appendText(raw: String) {
        for (ch in raw) {
            val c = if (ch.isWhitespace()) ' ' else ch
            if (c == ' ' && (sb.isEmpty() || sb.last() == ' ' || sb.last() == '\n')) continue
            sb.append(c)
        }
    }

    fun appendLineBreak() {
        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        sb.append('\n')
    }

    /** Marks [style] as open from the current offset; matched by a later [popStyle]. */
    fun pushStyle(style: InlineStyle) {
        openStyles.addLast(OpenStyle(style, sb.length))
    }

    /**
     * True if [style] is already open from an enclosing element — e.g. an outer `<i>`
     * around an inner `<span class="italic">`. Callers use this to skip re-pushing (and
     * later re-popping) a style an ancestor already contributes, so the same run doesn't
     * get two identical, redundant spans over the same text.
     */
    fun hasOpenStyle(style: InlineStyle): Boolean = openStyles.any { it.style == style }

    /** Closes the innermost still-open style, recording its span if it covered any text. */
    fun popStyle() {
        val open = openStyles.removeLastOrNull() ?: return
        if (sb.length > open.start) spans += StyleSpan(open.start, sb.length, open.style)
    }

    /**
     * Records a span for every style still open at the current offset without popping
     * it off the stack — the enclosing element hasn't closed yet, so the style is still
     * "in effect" for whatever text follows (on the far side of an `<img>` flush, once
     * [reset] rebases it to 0).
     */
    fun closeOpenStyles() {
        for (open in openStyles) {
            if (sb.length > open.start) spans += StyleSpan(open.start, sb.length, open.style)
        }
    }

    /**
     * Clears accumulated text and spans so offsets for the next run start at zero, and
     * rebases any still-open styles to that new zero — they resume covering text from
     * the very first character of the resumed run.
     */
    fun reset() {
        sb.setLength(0)
        spans.clear()
        openStyles.forEach { it.start = 0 }
    }

    fun build(): StyledText {
        val trimmed = sb.toString().trim()
        // Trimming the head would invalidate span offsets, so shift them by however much
        // was trimmed there; `shift` also clamps each span's end to the trimmed length
        // and drops any span that collapses to nothing (e.g. a trailing space before a
        // closing `</b>` at the end of the run) rather than let it exceed the text.
        val leading = sb.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        return StyledText(trimmed, spans.mapNotNull { shift(it, leading, trimmed.length) })
    }

    private fun shift(span: StyleSpan, by: Int, max: Int): StyleSpan? {
        val start = (span.start - by).coerceAtLeast(0)
        val end = (span.end - by).coerceAtMost(max)
        return if (end > start) StyleSpan(start, end, span.style) else null
    }
}
