package dev.reader.formats.mobi

import dev.reader.engine.Block
import dev.reader.engine.BlockStyle
import dev.reader.engine.InlineStyle
import dev.reader.engine.StyleSpan
import dev.reader.engine.StyledText
import dev.reader.engine.TextAlign
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Reduces mobi7 markup to the format-neutral [Block] model.
 *
 * This is a separate parser from `XhtmlBlockParser` rather than a reuse of it, and the reason is
 * not incidental: mobi7 is HTML3-era markup with no stylesheet, and two of its habits invert the
 * meaning the EPUB parser assigns.
 *
 *  * **`<blockquote>` is the paragraph.** Amazon's converter emits body text as blockquote with
 *    `width` supplying the first-line indent — in a real book, thousands of them against a
 *    handful of `<p>`. Sent through the EPUB parser, which maps blockquote to [Block.Quote], an
 *    entire novel renders as one continuous pull-quote.
 *  * **Style is attributes, not CSS.** `height`/`width` on a block carry space-before and
 *    text-indent; `<font size>` carries emphasis. There is no stylesheet to cascade, so the
 *    CSS machinery has nothing to do here.
 *
 * What it produces feeds the same publisher-styling toggle EPUB does: [BlockStyle.marginTopEm],
 * [BlockStyle.textIndentEm] and [BlockStyle.align] are resolved from the file's own attributes,
 * so "publisher styling ON" reproduces the book's intent and OFF gives the reader's own look —
 * identical semantics to an EPUB, which is the point.
 *
 * Not thread-safe: [parse] carries per-call mutable state. Construct one per chapter, as
 * `MobiDocument.readBlocks` does.
 */
internal class Mobi7BlockParser {

    private val blocks = mutableListOf<Block>()
    private val text = StringBuilder()
    private val spans = mutableListOf<StyleSpan>()
    private var pendingStyle = BlockStyle()

    /**
     * Parses a fragment of mobi7 HTML into blocks. [inferHeadings] turns on the size-and-brevity
     * heuristic below; [imageBytes] resolves a `recindex` to image bytes, or returns null.
     */
    fun parse(
        html: String,
        inferHeadings: Boolean,
        imageBytes: (Int) -> ByteArray? = { null },
    ): List<Block> {
        blocks.clear()
        resetParagraph()
        // Parsed as a fragment, not a document: a chapter is a slice out of one long body, so it
        // routinely starts and ends mid-element. Jsoup's body-fragment parse tolerates that where
        // a document parse would invent structure around it.
        val body = Jsoup.parseBodyFragment(html).body()
        walk(body, InlineStyle(), inferHeadings, imageBytes)
        flushParagraph(inferHeadings)
        return blocks.toList()
    }

    private fun walk(node: Node, inherited: InlineStyle, inferHeadings: Boolean, imageBytes: (Int) -> ByteArray?) {
        for (child in node.childNodes()) {
            when (child) {
                is TextNode -> appendText(child.text(), inherited)
                is Element -> element(child, inherited, inferHeadings, imageBytes)
                else -> Unit // comments, doctypes and the rest carry nothing to read
            }
        }
    }

    private fun element(
        el: Element,
        inherited: InlineStyle,
        inferHeadings: Boolean,
        imageBytes: (Int) -> ByteArray?,
    ) {
        when (el.normalName()) {
            // mobi's own page break, and the most reliable structural signal in the format.
            "mbp:pagebreak", "pagebreak" -> {
                flushParagraph(inferHeadings)
                blocks += Block.PageBreak
            }

            // The paragraph, in both its spellings. blockquote is NOT a quote here — see the
            // class KDoc. div is a bare container in this markup and only breaks the paragraph.
            "p", "blockquote" -> {
                flushParagraph(inferHeadings)
                pendingStyle = blockStyleOf(el)
                walk(el, inherited, inferHeadings, imageBytes)
                flushParagraph(inferHeadings)
            }

            "div", "body", "html", "center" -> {
                flushParagraph(inferHeadings)
                val style = blockStyleOf(el)
                if (el.normalName() == "center") pendingStyle = style.copy(align = TextAlign.CENTER)
                walk(el, inherited, inferHeadings, imageBytes)
                flushParagraph(inferHeadings)
            }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flushParagraph(inferHeadings)
                val level = el.normalName().substring(1).toIntOrNull()?.coerceIn(1, 6) ?: 3
                val style = blockStyleOf(el)
                walk(el, inherited, inferHeadings, imageBytes)
                emitHeading(level, style)
            }

            "br" -> appendText("\n", inherited)

            "img" -> {
                // mobi images are referenced by record index, not by path. recindex is 1-based
                // relative to the first image record; the document resolves it to bytes.
                val rec = el.attr("recindex").trim().toIntOrNull()
                if (rec != null && rec > 0) {
                    flushParagraph(inferHeadings)
                    blocks += Block.Image(href = "recindex:$rec", bytes = imageBytes(rec))
                }
            }

            // Dropped entirely: they carry no reading content.
            "head", "title", "style", "script", "guide", "reference" -> Unit

            else -> walk(el, inherited.merge(inlineStyleOf(el)), inferHeadings, imageBytes)
        }
    }

    private fun appendText(raw: String, style: InlineStyle) {
        if (raw.isEmpty()) return
        val start = text.length
        text.append(raw)
        if (text.length > start && style != InlineStyle()) {
            spans += StyleSpan(start, text.length, style)
        }
    }

    private fun emitHeading(level: Int, style: BlockStyle) {
        val body = collapse(text.toString())
        if (body.isNotBlank()) {
            blocks += Block.Heading(level, StyledText(body, spansFor(body)), style)
        }
        resetParagraph()
    }

    /**
     * Closes the paragraph under construction. When [inferHeadings] is on, a short line set
     * noticeably larger than the body becomes a heading instead — mobi7 has no `<h1>`, so a
     * chapter opener is a `<p>` wrapped in `<font size="7"><b>`, and without this every chapter
     * of every book would open on an ordinary paragraph.
     */
    private fun flushParagraph(inferHeadings: Boolean) {
        val body = collapse(text.toString())
        if (body.isBlank()) {
            resetParagraph()
            return
        }
        val style = pendingStyle
        val level = if (inferHeadings) inferredHeadingLevel(body) else null
        blocks += if (level != null) {
            Block.Heading(level, StyledText(body, spansFor(body)), style)
        } else {
            Block.Paragraph(StyledText(body, spansFor(body)), style)
        }
        resetParagraph()
    }

    /**
     * A heading level for a flushed paragraph, or null for ordinary prose. Mirrors the EPUB
     * parser's rule so the two formats infer alike: short, and set larger than the body.
     */
    private fun inferredHeadingLevel(body: String): Int? {
        if (body.length > MAX_HEADING_CHARS) return null
        // The size that covers the whole line, if one does — a heading is uniformly large, where
        // an emphasized word inside a sentence is not.
        val covering = spans.filter { it.start == 0 && it.end >= text.length && it.style.sizeRatio != null }
        val ratio = covering.maxOfOrNull { it.style.sizeRatio ?: 1f } ?: return null
        return when {
            ratio >= 1.8f -> 1
            ratio >= 1.5f -> 2
            ratio >= 1.2f -> 3
            else -> null
        }
    }

    /**
     * Re-maps the spans collected against the raw buffer onto the collapsed text. Whitespace
     * collapsing shifts every offset after the first run, so spans are rebuilt by walking the
     * same collapse in step rather than reused verbatim — reusing them lands emphasis on the
     * wrong words in any paragraph containing a line break.
     */
    private fun spansFor(collapsed: String): List<StyleSpan> {
        if (spans.isEmpty()) return emptyList()
        val map = collapseMap(text.toString())
        return spans.mapNotNull { span ->
            val start = map.getOrNull(span.start) ?: return@mapNotNull null
            val end = (span.end - 1).let { map.getOrNull(it) }?.plus(1) ?: return@mapNotNull null
            if (end <= start || end > collapsed.length) null else StyleSpan(start, end, span.style)
        }
    }

    private fun resetParagraph() {
        text.setLength(0)
        spans.clear()
        pendingStyle = BlockStyle()
    }

    /** Reads mobi's presentational block attributes into the model's own units. */
    private fun blockStyleOf(el: Element): BlockStyle {
        val align = when (el.attr("align").trim().lowercase()) {
            "center" -> TextAlign.CENTER
            "right" -> TextAlign.RIGHT
            "left" -> TextAlign.LEFT
            "justify" -> TextAlign.JUSTIFY
            else -> null
        }
        // height is space ABOVE the block in mobi, not the block's own height.
        val spaceAbove = lengthEm(el.attr("height"))
        // width is the first-line indent; a negative value is a hanging indent, which the model
        // carries as-is (the renderer clamps what it cannot draw).
        val indent = lengthEm(el.attr("width"))
        return BlockStyle(align = align, marginTopEm = spaceAbove, textIndentEm = indent)
    }

    /**
     * Parses mobi's lengths — `1em`, `0pt`, `-19pt`, or a bare number — into em.
     * Points are converted at the 12pt-per-em the format's own converter assumes.
     */
    private fun lengthEm(raw: String): Float? {
        val v = raw.trim().lowercase()
        if (v.isEmpty()) return null
        val number = v.trimEnd('e', 'm', 'p', 't', 'x', '%').toFloatOrNull() ?: return null
        return when {
            v.endsWith("em") -> number
            v.endsWith("pt") -> number / POINTS_PER_EM
            v.endsWith("px") -> number / PIXELS_PER_EM
            v.endsWith("%") -> number / 100f
            else -> number
        }
    }

    /** Reads mobi's inline presentation into [InlineStyle]. */
    private fun inlineStyleOf(el: Element): InlineStyle = when (el.normalName()) {
        "b", "strong" -> InlineStyle(bold = true)
        "i", "em", "cite" -> InlineStyle(italic = true)
        "u" -> InlineStyle(underline = true)
        "s", "strike", "del" -> InlineStyle(strikethrough = true)
        "code", "tt", "kbd", "samp" -> InlineStyle(monospace = true)
        "sup" -> InlineStyle(superscript = true)
        "sub" -> InlineStyle(subscript = true)
        "big" -> InlineStyle(sizeRatio = 1.2f)
        "small" -> InlineStyle(sizeRatio = 0.85f)
        // HTML3 font sizes run 1..7 with 3 as the body size — the scale mobi7 headings use.
        "font" -> InlineStyle(sizeRatio = fontSizeRatio(el.attr("size")))
        else -> InlineStyle()
    }

    /**
     * HTML3 `<font size>` to a ratio against body text. Absolute 1..7 and relative `+2`/`-1` are
     * both in the wild; anything unrecognised contributes nothing rather than guessing.
     */
    private fun fontSizeRatio(raw: String): Float? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        val level = when {
            v.startsWith("+") -> BODY_FONT_LEVEL + (v.drop(1).toIntOrNull() ?: return null)
            v.startsWith("-") -> BODY_FONT_LEVEL - (v.drop(1).toIntOrNull() ?: return null)
            else -> v.toIntOrNull() ?: return null
        }.coerceIn(1, 7)
        return FONT_SIZE_RATIOS[level - 1].takeIf { it != 1f }
    }

    private companion object {
        /** Longest line still eligible to be inferred a heading. Matches the EPUB parser's rule. */
        const val MAX_HEADING_CHARS = 120

        /** `<font size="3">` is body text in HTML3, and mobi7's converter follows that. */
        const val BODY_FONT_LEVEL = 3

        /** Ratios for `<font size>` 1..7, against body text at level 3. */
        val FONT_SIZE_RATIOS = floatArrayOf(0.7f, 0.85f, 1f, 1.2f, 1.5f, 1.8f, 2.2f)

        const val POINTS_PER_EM = 12f
        const val PIXELS_PER_EM = 16f

        /**
         * Collapses runs of whitespace to a single space and trims, the way an HTML renderer
         * treats source whitespace. mobi7 wraps its source at arbitrary columns, so without this
         * every line break in the file becomes a space in the middle of a sentence.
         */
        fun collapse(raw: String): String = raw.replace(WHITESPACE, " ").trim()

        /**
         * Index map from raw offsets to collapsed offsets, so spans survive [collapse]. Entry
         * `i` is where raw character `i` landed, or null if it was collapsed away.
         */
        fun collapseMap(raw: String): Array<Int?> {
            val out = arrayOfNulls<Int>(raw.length)
            var written = 0
            var lastWasSpace = true // leading whitespace is trimmed, so start as if mid-run
            for (i in raw.indices) {
                val c = raw[i]
                if (c.isWhitespace()) {
                    if (!lastWasSpace) {
                        out[i] = written
                        written++
                        lastWasSpace = true
                    }
                } else {
                    out[i] = written
                    written++
                    lastWasSpace = false
                }
            }
            return out
        }

        val WHITESPACE = Regex("\\s+")
    }
}

/** Fills only the fields [other] specifies, so an inner tag adds to the emphasis it sits inside. */
private fun InlineStyle.merge(other: InlineStyle) = InlineStyle(
    bold = other.bold ?: bold,
    italic = other.italic ?: italic,
    monospace = other.monospace ?: monospace,
    sizeRatio = other.sizeRatio ?: sizeRatio,
    underline = other.underline ?: underline,
    strikethrough = other.strikethrough ?: strikethrough,
    letterSpacingEm = other.letterSpacingEm ?: letterSpacingEm,
    grayLevel = other.grayLevel ?: grayLevel,
    superscript = other.superscript ?: superscript,
    subscript = other.subscript ?: subscript,
)
