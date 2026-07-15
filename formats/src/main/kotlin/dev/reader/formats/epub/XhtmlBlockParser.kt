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
        body.children().forEach { visit(it, chapterPath, out) }
        return out
    }

    private fun visit(el: Element, chapterPath: String, out: MutableList<Block>) {
        if (requestsPageBreak(el)) out += Block.PageBreak

        when (el.tagName().lowercase()) {
            "p" -> emitTextBlock(el, chapterPath, out) { Block.Paragraph(it) }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = el.tagName()[1].digitToInt()
                emitTextBlock(el, chapterPath, out) { Block.Heading(level, it) }
            }

            "blockquote" -> emitTextBlock(el, chapterPath, out) { Block.Quote(it) }

            "ul" -> listItems(el).forEach { li ->
                emitTextBlock(li, chapterPath, out) { Block.ListItem(it, ordinal = null) }
            }

            "ol" -> listItems(el).forEachIndexed { index, li ->
                emitTextBlock(li, chapterPath, out) { Block.ListItem(it, ordinal = index + 1) }
            }

            "img" -> emitImage(el, chapterPath, out)

            "style", "script", "head", "title", "link", "meta" -> Unit

            // Containers are transparent: keep looking for whitelisted content inside.
            else -> el.children().forEach { visit(it, chapterPath, out) }
        }
    }

    private fun listItems(el: Element) = el.children().filter { it.tagName().lowercase() == "li" }

    private fun requestsPageBreak(el: Element): Boolean {
        val style = el.attr("style").lowercase().replace(" ", "")
        return "page-break-before:always" in style || "break-before:page" in style
    }

    private fun emitImage(el: Element, chapterPath: String, out: MutableList<Block>) {
        el.attr("src").takeIf { it.isNotEmpty() }
            ?.let { out += Block.Image(resolveHref(chapterPath, it)) }
    }

    /**
     * Walks [el]'s inline content into [factory]'s block type (Paragraph/Heading/Quote/
     * ListItem). An `<img>` nested inside — real EPUBs routinely wrap a figure in `<p>` —
     * is not text: it splits the surrounding run, flushing whatever text came before it
     * as one block, emitting `Block.Image` as its own block, then resuming text
     * accumulation from a fresh (zero-based) offset for whatever follows.
     */
    private fun emitTextBlock(
        el: Element,
        chapterPath: String,
        out: MutableList<Block>,
        factory: (StyledText) -> Block,
    ) {
        val builder = InlineBuilder()
        val flush = {
            val text = builder.build()
            if (text.text.isNotBlank()) out += factory(text)
            builder.reset()
        }
        el.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out) }
        flush()
    }

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
                    val start = builder.length
                    node.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out) }
                    if (style != null && builder.length > start) {
                        builder.addSpan(start, builder.length, style)
                    }
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
 */
private class InlineBuilder {
    private val sb = StringBuilder()
    private val spans = mutableListOf<StyleSpan>()

    val length: Int get() = sb.length

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

    fun addSpan(start: Int, end: Int, style: InlineStyle) {
        spans += StyleSpan(start, end, style)
    }

    /** Clears accumulated text and spans so offsets for the next run start at zero. */
    fun reset() {
        sb.setLength(0)
        spans.clear()
    }

    fun build(): StyledText {
        val trimmed = sb.toString().trim()
        // Trimming the head would invalidate span offsets, so only trim when it cannot.
        val leading = sb.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        return if (leading == 0) {
            StyledText(sb.toString().trimEnd(), spans.filter { it.start < sb.length })
        } else {
            StyledText(trimmed, spans.mapNotNull { shift(it, leading, trimmed.length) })
        }
    }

    private fun shift(span: StyleSpan, by: Int, max: Int): StyleSpan? {
        val start = (span.start - by).coerceAtLeast(0)
        val end = (span.end - by).coerceAtMost(max)
        return if (end > start) StyleSpan(start, end, span.style) else null
    }
}
