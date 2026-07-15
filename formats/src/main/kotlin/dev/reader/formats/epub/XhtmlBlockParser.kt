package dev.reader.formats.epub

import dev.reader.engine.Block
import dev.reader.engine.InlineStyle
import dev.reader.engine.StyleSpan
import dev.reader.engine.StyledText
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Reduces chapter XHTML to the [Block] whitelist. Publisher CSS, embedded fonts and
 * every unlisted element are discarded — the reader's own typography is applied later,
 * from RenderConfig.
 *
 * Parsed with jsoup's lenient HTML parser rather than its XML parser: real-world EPUBs
 * are frequently not well-formed XML, and a book must open anyway.
 */
class XhtmlBlockParser {

    fun parse(xhtml: String, chapterPath: String): List<Block> {
        val body = Jsoup.parse(xhtml).body()
        val out = mutableListOf<Block>()
        // body is itself just another container: route it through the same childNodes()
        // walk as any other transparent container, so bare text sitting directly inside
        // <body> (no wrapping element) isn't dropped the way children()-only iteration
        // would drop it. See visitContainerChildren.
        visitContainerChildren(body, chapterPath, out)
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
    private fun visit(el: Element, chapterPath: String, out: MutableList<Block>) {
        val produced = mutableListOf<Block>()
        dispatch(el, chapterPath, produced)
        if (produced.isEmpty()) return

        if (requestsPageBreak(el)) addBlock(out, Block.PageBreak)
        produced.forEach { addBlock(out, it) }
    }

    private fun addBlock(out: MutableList<Block>, block: Block) {
        if (block == Block.PageBreak && out.lastOrNull() == Block.PageBreak) return
        out += block
    }

    private fun dispatch(el: Element, chapterPath: String, out: MutableList<Block>) {
        when (el.tagName().lowercase()) {
            "p" -> emitTextBlock(el, chapterPath, out) { Block.Paragraph(it) }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = el.tagName()[1].digitToInt()
                emitTextBlock(el, chapterPath, out) { Block.Heading(level, it) }
            }

            "blockquote" -> emitBlockquote(el, chapterPath, out)

            "ul" -> listItems(el).forEach { li ->
                emitTextBlock(li, chapterPath, out) { Block.ListItem(it, ordinal = null) }
            }

            "ol" -> listItems(el).forEachIndexed { index, li ->
                emitTextBlock(li, chapterPath, out) { Block.ListItem(it, ordinal = index + 1) }
            }

            "img" -> emitImage(el, chapterPath, out)

            "style", "script", "head", "title", "link", "meta" -> Unit

            // Containers are transparent: keep looking for whitelisted content inside.
            else -> visitContainerChildren(el, chapterPath, out)
        }
    }

    /**
     * Walks [el]'s child *nodes* (not just its child *elements*) so that bare text sitting
     * directly inside a transparent container — no wrapping element — isn't dropped, e.g.
     * the "tail text" after a `<p>` inside a `<div>`, or the whole chapter when a chapter's
     * `<body>` has no wrapping element at all. Shared by [dispatch]'s container branch and
     * by [parse], since `<body>` is itself just another container: `Elements`-only iteration
     * (`children()`) would silently drop bare text in either place the same way.
     */
    private fun visitContainerChildren(el: Element, chapterPath: String, out: MutableList<Block>) {
        el.childNodes().forEach { child ->
            when {
                child is Element -> visit(child, chapterPath, out)
                child is TextNode && child.wholeText.isNotBlank() -> {
                    val builder = InlineBuilder()
                    builder.appendText(child.wholeText)
                    val text = builder.build()
                    if (text.text.isNotBlank()) out += Block.Paragraph(text)
                }
            }
        }
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
     */
    private fun emitBlockquote(el: Element, chapterPath: String, out: MutableList<Block>) {
        val builder = InlineBuilder()
        val flush = {
            builder.closeOpenStyles()
            val text = builder.build()
            if (text.text.isNotBlank()) out += Block.Quote(text)
            builder.reset()
        }
        walkBlockquote(el, chapterPath, builder, flush, out)
        flush()
    }

    private fun walkBlockquote(
        el: Element,
        chapterPath: String,
        builder: InlineBuilder,
        flush: () -> Unit,
        out: MutableList<Block>,
    ) {
        el.childNodes().forEach { node ->
            when (node) {
                is TextNode -> builder.appendText(node.wholeText)
                is Element -> when (val tag = node.tagName().lowercase()) {
                    "br" -> builder.appendLineBreak()
                    "style", "script" -> Unit
                    "img" -> {
                        flush()
                        emitImage(node, chapterPath, out)
                    }
                    "p", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        flush()
                        emitTextBlock(node, chapterPath, out) { Block.Quote(it) }
                    }
                    else -> {
                        val style = inlineStyleOf(tag)
                        if (style != null) {
                            builder.pushStyle(style)
                            node.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out) }
                            builder.popStyle()
                        } else {
                            flush()
                            walkBlockquote(node, chapterPath, builder, flush, out)
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
        factory: (StyledText) -> Block,
    ) {
        val builder = InlineBuilder()
        val flush = {
            // Close every style still open at this boundary so the block being
            // flushed keeps it (see the `<img>` case in walkInline below and
            // InlineBuilder's kdoc), then build and reset for whatever follows.
            builder.closeOpenStyles()
            val text = builder.build()
            if (text.text.isNotBlank()) out += factory(text)
            builder.reset()
        }
        el.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out) }
        flush()
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
                    val style = inlineStyleOf(tag)
                    if (style != null) builder.pushStyle(style)
                    node.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out) }
                    if (style != null) builder.popStyle()
                }
            }
        }
    }

    private fun inlineStyleOf(tag: String): InlineStyle? = when (tag) {
        "b", "strong" -> InlineStyle.BOLD
        "i", "em", "cite", "dfn" -> InlineStyle.ITALIC
        "code", "kbd", "samp", "tt" -> InlineStyle.MONOSPACE
        else -> null
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
