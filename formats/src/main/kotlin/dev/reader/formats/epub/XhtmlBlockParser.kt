package dev.reader.formats.epub

import dev.reader.engine.Block
import dev.reader.engine.BlockStyle
import dev.reader.engine.InlineStyle
import dev.reader.engine.StyleSpan
import dev.reader.engine.StyledText
import dev.reader.engine.TextAlign
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import kotlin.math.abs

/**
 * Reduces chapter XHTML to the [Block] whitelist. Every element is resolved through the
 * [CssRules] cascade over its ancestor chain, and the honored slice of its computed style
 * is mapped into the resolved [InlineStyle]/[BlockStyle] model as *optional* values — the
 * publisher-styling toggle downstream decides whether to apply them. Emphasis (bold/italic/
 * monospace) rides the same resolution path as everything else: font-size → sizeRatio,
 * color → grayLevel, text-decoration → underline/strikethrough, letter-spacing, and the
 * block-level text-align/margins/text-indent/line-height. Box-layout properties (float,
 * position, border, background, width/height, padding, `display` beyond the block/inline
 * split already inferred) are ignored, never half-honored.
 *
 * Font-size is resolved to a **ratio** against the document's own baseline, never an
 * absolute size: `em`/`rem`/`%`/keywords compose into [ComputedStyle.fontSizeRatio], and a
 * `px`/`pt` size is divided by the baseline mined once per chapter from `body`/`html`. With
 * no baseline, an absolute size yields null rather than a guess.
 *
 * Parsed with jsoup's lenient HTML parser rather than its XML parser: real-world EPUBs are
 * frequently not well-formed XML, and a book must open anyway. Resolution never throws —
 * malformed CSS values degrade to null.
 *
 * NOT thread-safe: [baselinePx] is set per chapter and [colorMemo] is an unsynchronized
 * cache. Callers confine parsing to a single thread (see [EpubDocument.chapter]).
 */
class XhtmlBlockParser {

    /** The px baseline for this chapter's absolute font-sizes, mined from `body`/`html`. */
    private var baselinePx: Float? = null

    /** Caches parsed colour luminance across chapters; colour strings resolve identically. */
    private val colorMemo = HashMap<String, Float?>()

    /**
     * @param css Resolves each element's computed style over its ancestor chain — emphasis
     *   plus the full honored property table — and, when [inferHeadings] is set, heading
     *   candidacy for CSS-encoded structure. Defaults to [CssRules.EMPTY] so tag emphasis
     *   (`<b>`, `<i>`, ...) still works with no stylesheet at all.
     * @param inferHeadings Whether a short, visually-heading-like `<p>`/`<div>` is
     *   promoted to [Block.Heading]. Independent of the resolved styles, which always ride
     *   along on whichever block type is emitted.
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
        baselinePx = mineBaseline(css)
        val out = mutableListOf<Block>()
        // body is itself just another container: route it through the same childNodes()
        // walk as any other transparent container, so bare text sitting directly inside
        // <body> (no wrapping element) isn't dropped the way children()-only iteration
        // would drop it. See visitContainerChildren. The ancestor chain is seeded with
        // <body> (and any <html> above it) so inheritance and combinators resolve from the
        // document root down.
        visitContainerChildren(body, chapterPath, out, ancestorChainOf(body), css, inferHeadings)
        return out
    }

    /**
     * Dispatches [el] into [out], then gates that content behind a page break: a
     * break-requesting element that ends up contributing nothing (an empty div, say)
     * must not leave a stray `Block.PageBreak` with no content after it, and nested
     * break-requesting containers wrapping the same content must not each add their own
     * break. [addBlock] enforces both by refusing to stack a `PageBreak` directly on
     * top of another one already at the tail of [out].
     *
     * [chain] runs root → [el] inclusive (so `chain.last()` is [el]'s context).
     */
    private fun visit(
        el: Element,
        chapterPath: String,
        out: MutableList<Block>,
        chain: List<ElementCtx>,
        css: CssRules,
        inferHeadings: Boolean,
    ) {
        val produced = mutableListOf<Block>()
        dispatch(el, chapterPath, produced, chain, css, inferHeadings)
        if (produced.isEmpty()) return

        if (requestsPageBreak(el, css)) addBlock(out, Block.PageBreak)
        produced.forEach { addBlock(out, it) }
    }

    private fun addBlock(out: MutableList<Block>, block: Block) {
        if (block == Block.PageBreak && out.lastOrNull() == Block.PageBreak) return
        out += block
    }

    private fun dispatch(
        el: Element,
        chapterPath: String,
        out: MutableList<Block>,
        chain: List<ElementCtx>,
        css: CssRules,
        inferHeadings: Boolean,
    ) {
        when (el.tagName().lowercase()) {
            "p" -> emitTextBlock(el, chapterPath, out, chain, css) { text, style ->
                paragraphOrHeading(el, text, style, css, inferHeadings)
            }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = el.tagName()[1].digitToInt()
                emitTextBlock(el, chapterPath, out, chain, css) { text, style -> Block.Heading(level, text, style) }
            }

            "blockquote" -> emitBlockquote(el, chapterPath, out, chain, css, inferHeadings)

            "ul" -> emitList(el, chapterPath, out, chain, css, inferHeadings, ordered = false)

            "ol" -> emitList(el, chapterPath, out, chain, css, inferHeadings, ordered = true)

            "img" -> emitImage(el, chapterPath, out)

            "style", "script", "head", "title", "link", "meta" -> Unit

            // Containers are transparent: keep looking for whitelisted content inside.
            else -> visitContainerChildren(el, chapterPath, out, chain, css, inferHeadings)
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
        chain: List<ElementCtx>,
        css: CssRules,
        inferHeadings: Boolean,
    ) {
        emitRun(
            el, chapterPath, out, chain,
            { text, style -> paragraphOrHeading(el, text, style, css, inferHeadings) },
            flatten = false, css, inferHeadings,
        )
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
    private fun emitBlockquote(
        el: Element,
        chapterPath: String,
        out: MutableList<Block>,
        chain: List<ElementCtx>,
        css: CssRules,
        inferHeadings: Boolean,
    ) {
        emitRun(el, chapterPath, out, chain, { text, style -> Block.Quote(text, style) }, flatten = true, css, inferHeadings)
    }

    /**
     * Builds the running [InlineBuilder] + [flushClosure] pair that [walkRun] shares
     * between [visitContainerChildren] and [emitBlockquote], then flushes once more after
     * the walk completes to catch whatever ran to the end of [el] unflushed.
     *
     * [el]'s own resolved styles are opened on the builder up front so that bare text and
     * inline runs sitting directly in the container inherit them — a `<div class="epigraph">`
     * whose stylesheet makes it italic paints that italic over its own loose text.
     */
    private fun emitRun(
        el: Element,
        chapterPath: String,
        out: MutableList<Block>,
        chain: List<ElementCtx>,
        factory: (StyledText, BlockStyle) -> Block,
        flatten: Boolean,
        css: CssRules,
        inferHeadings: Boolean,
    ) {
        val builder = InlineBuilder()
        val computed = css.resolve(chain)
        val flush = flushClosure(builder, out, blockStyleFrom(computed), factory)
        inlineStylesFrom(chain.last().tag, computed).forEach { builder.pushStyle(it) }
        walkRun(el, chapterPath, builder, flush, out, chain, factory, flatten, css, inferHeadings)
        flush()
    }

    private fun walkRun(
        el: Element,
        chapterPath: String,
        builder: InlineBuilder,
        flush: () -> Unit,
        out: MutableList<Block>,
        chain: List<ElementCtx>,
        factory: (StyledText, BlockStyle) -> Block,
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
                        if (flatten) emitImage(node, chapterPath, out) else visit(node, chapterPath, out, chain + ctxOf(node), css, inferHeadings)
                    }
                    "p", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        flush()
                        val childChain = chain + ctxOf(node)
                        if (flatten) {
                            emitTextBlock(node, chapterPath, out, childChain, css, factory)
                        } else {
                            visit(node, chapterPath, out, childChain, css, inferHeadings)
                        }
                    }
                    else -> {
                        // An element joins the enclosing inline run when it is inline BY
                        // TAG (`<a>`, `<span>`, `<sup>`, ... — styled or not: an unstyled
                        // link mid-sentence must not shatter the sentence into separate
                        // Paragraphs) or when its OWN emphasis makes an otherwise-unknown
                        // element behave like one — but never when it carries a
                        // block-level descendant of its own. A publisher stylesheet
                        // routinely puts `font-style: italic` on a bare tag selector
                        // (`blockquote { ... }`, `div.epigraph { ... }`); treating every
                        // such element as one inline run would flatten its <p>/heading/
                        // list children into a single merged block, silently losing their
                        // structure — and even a genuine `<a>` around a `<p>` must recurse
                        // so the paragraph keeps its own Block.
                        //
                        // The routing signal is the element's OWN emphasis (not its full
                        // inherited computed style): inherited colour/size must not flip a
                        // plain container into an inline run and merge it with its
                        // siblings. Once we DO treat it as a run, the styles pushed are the
                        // full resolved set, deduped against whatever an ancestor already
                        // opened.
                        val childChain = chain + ctxOf(node)
                        val emphasiseAsInlineRun = (isInlineByTag(tag) || ownEmphasisStyles(node, css).isNotEmpty()) &&
                            !containsBlockLevelDescendant(node)
                        when {
                            emphasiseAsInlineRun -> {
                                // Skip pushing (and later popping) any style already open
                                // from an ancestor — e.g. `<i><span class="italic">`, or a
                                // colour merely inherited from the enclosing block — so the
                                // same InlineStyle doesn't produce two identical, redundant
                                // spans over the same text.
                                val newStyles = resolveInlineStyles(childChain, css).filterNot { builder.hasOpenStyle(it) }
                                newStyles.forEach { builder.pushStyle(it) }
                                node.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out, childChain, css) }
                                newStyles.forEach { builder.popStyle() }
                            }
                            // Lists have no "Quote" equivalent, so even a flattening walk
                            // routes them through normal dispatch (see emitBlockquote's kdoc).
                            flatten && tag != "ul" && tag != "ol" -> {
                                flush()
                                walkRun(node, chapterPath, builder, flush, out, childChain, factory, flatten, css, inferHeadings)
                            }
                            else -> {
                                flush()
                                visit(node, chapterPath, out, childChain, css, inferHeadings)
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * Emits a list's items. Direct `<li>` children are the well-formed case, but real
     * EPUBs also wrap `<li>`s in a non-`<li>` element (`<div>`, most commonly) inside the
     * list, and leave bare text sitting directly inside `<ul>` — so this recurses into
     * non-`<li>` wrapper children looking for `<li>`s at any depth, and keeps bare text
     * as a plain paragraph, rather than silently dropping either. A nested `<ul>`/`<ol>`
     * met while scanning is its own list, not a source of this list's items: it re-enters
     * normal dispatch so its items are emitted exactly once, as its own. (A nested list
     * *inside* an `<li>` never reaches here — [emitTextBlock] flattens it into that
     * item's text, see [walkInline].)
     */
    private fun emitList(
        el: Element,
        chapterPath: String,
        out: MutableList<Block>,
        chain: List<ElementCtx>,
        css: CssRules,
        inferHeadings: Boolean,
        ordered: Boolean,
    ) {
        var ordinal = 0
        fun walk(container: Element, containerChain: List<ElementCtx>) {
            container.childNodes().forEach { node ->
                when (node) {
                    is TextNode -> {
                        val builder = InlineBuilder()
                        builder.appendText(node.wholeText)
                        val text = builder.build()
                        if (text.text.isNotBlank()) out += Block.Paragraph(text)
                    }
                    is Element -> when (node.tagName().lowercase()) {
                        "li" -> emitTextBlock(node, chapterPath, out, containerChain + ctxOf(node), css) { text, style ->
                            Block.ListItem(text, ordinal = if (ordered) ++ordinal else null, style = style)
                        }
                        "ul", "ol" -> visit(node, chapterPath, out, containerChain + ctxOf(node), css, inferHeadings)
                        "style", "script" -> Unit
                        else -> walk(node, containerChain + ctxOf(node))
                    }
                    else -> Unit
                }
            }
        }
        walk(el, chain)
    }

    /**
     * Whether [el] asks for a page break before itself, from its *cascaded* declarations —
     * calibre standardly declares `page-break-before: always` via a class rule, not the
     * inline `style` attribute, and [declarationsFor] already resolves both (inline wins).
     */
    private fun requestsPageBreak(el: Element, css: CssRules): Boolean {
        val declarations = declarationsFor(el, css)
        return declarations["page-break-before"] == "always" || declarations["break-before"] == "page"
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
     * [el]'s own resolved [BlockStyle] rides on the block, and its own inline styles
     * (font-size, colour, letter-spacing, and any emphasis it inherits or declares) are
     * opened over the whole text before its children add their deltas.
     *
     * [chain] runs root → [el] inclusive.
     */
    private fun emitTextBlock(
        el: Element,
        chapterPath: String,
        out: MutableList<Block>,
        chain: List<ElementCtx>,
        css: CssRules,
        factory: (StyledText, BlockStyle) -> Block,
    ) {
        val builder = InlineBuilder()
        val computed = css.resolve(chain)
        val flush = flushClosure(builder, out, blockStyleFrom(computed), factory)
        inlineStylesFrom(chain.last().tag, computed).forEach { builder.pushStyle(it) }
        el.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out, chain, css) }
        flush()
    }

    /**
     * The flush step shared by every accumulator in this file ([emitTextBlock], and
     * [emitRun] on behalf of both [visitContainerChildren] and [emitBlockquote]): close
     * every style still open at this boundary so the block being flushed keeps it (see the
     * `<img>` case in [walkInline] below and [InlineBuilder]'s kdoc), build, hand the result
     * — with [blockStyle] — to [factory] unless it's blank, then reset for whatever follows.
     */
    private fun flushClosure(
        builder: InlineBuilder,
        out: MutableList<Block>,
        blockStyle: BlockStyle,
        factory: (StyledText, BlockStyle) -> Block,
    ): () -> Unit = {
        builder.closeOpenStyles()
        val text = builder.build()
        if (text.text.isNotBlank()) out += factory(text, blockStyle)
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
     *
     * [chain] runs root → the element whose children these nodes are, inclusive; each child
     * element extends it before its own styles are resolved.
     */
    private fun walkInline(
        node: Node,
        chapterPath: String,
        builder: InlineBuilder,
        flush: () -> Unit,
        out: MutableList<Block>,
        chain: List<ElementCtx>,
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
                // A block-level tag inside a flattening walk (block children of an <li>,
                // most commonly) is a boundary: its words must never concatenate with the
                // surrounding text without a separator, so a line break brackets it on
                // both sides. The content itself still flattens into the enclosing block
                // — rendering e.g. a nested list as real nested ListItems is later work.
                "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "div", "ul", "ol", "li" -> {
                    val childChain = chain + ctxOf(node)
                    val styles = resolveInlineStyles(childChain, css).filterNot { builder.hasOpenStyle(it) }
                    builder.appendBlockBoundary()
                    styles.forEach { builder.pushStyle(it) }
                    node.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out, childChain, css) }
                    styles.forEach { builder.popStyle() }
                    builder.appendBlockBoundary()
                }
                else -> {
                    // See the matching comment in walkRun: don't re-push a style an
                    // ancestor already has open.
                    val childChain = chain + ctxOf(node)
                    val styles = resolveInlineStyles(childChain, css).filterNot { builder.hasOpenStyle(it) }
                    styles.forEach { builder.pushStyle(it) }
                    node.childNodes().forEach { walkInline(it, chapterPath, builder, flush, out, childChain, css) }
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
        "b", "strong" -> InlineStyle(bold = true)
        "i", "em", "cite", "dfn" -> InlineStyle(italic = true)
        "code", "kbd", "samp", "tt" -> InlineStyle(monospace = true)
        else -> null
    }

    // --- Chain + resolution --------------------------------------------------

    /** The cascade context [el] contributes: tag, classes, id, inline `style`. */
    private fun ctxOf(el: Element): ElementCtx = ElementCtx(
        tag = el.tagName(),
        classes = el.classNames().toList(),
        id = el.id().ifBlank { null },
        inlineStyle = el.attr("style").ifBlank { null },
    )

    /** The full ancestor chain root → [el] inclusive, e.g. `[html, body]` for `<body>`. */
    private fun ancestorChainOf(el: Element): List<ElementCtx> {
        val chain = ArrayList<ElementCtx>()
        el.parents().reversed().forEach { chain.add(ctxOf(it)) }
        chain.add(ctxOf(el))
        return chain
    }

    /**
     * The element at the end of [chain], resolved into single-field [InlineStyle] pushes —
     * one per honored property — so a multi-property element (bold + underline + gray)
     * flows through the same one-span-per-semantic stack as a bare `<b>` does.
     */
    private fun resolveInlineStyles(chain: List<ElementCtx>, css: CssRules): List<InlineStyle> =
        inlineStylesFrom(chain.last().tag, css.resolve(chain))

    /**
     * Maps the honored inline slice of [computed] (plus [tag] semantics) into a list of
     * single-field [InlineStyle]s. Tag emphasis (`<b>`/`<i>`/`<code>`) is unioned with what
     * the cascade resolved — `<b class="italic">` recovers both — and duplicates collapse
     * (a `<code>` whose stylesheet also declares `font-family: monospace` pushes one mono
     * style, not two). Every value comes out null-safe: a garbage size/colour/spacing
     * resolves to no style rather than throwing.
     */
    private fun inlineStylesFrom(tag: String, computed: ComputedStyle): List<InlineStyle> {
        val styles = LinkedHashSet<InlineStyle>()
        inlineStyleOf(tag.lowercase())?.let { styles += it }
        if (computed["font-style"] in ITALIC_KEYWORDS) styles += InlineStyle(italic = true)
        if (isBoldValue(computed["font-weight"])) styles += InlineStyle(bold = true)
        if (isMonospaceFamily(computed["font-family"])) styles += InlineStyle(monospace = true)
        computed["text-decoration"]?.let { decoration ->
            if (decoration.contains("underline")) styles += InlineStyle(underline = true)
            if (decoration.contains("line-through")) styles += InlineStyle(strikethrough = true)
        }
        sizeRatioOf(computed)?.let { styles += InlineStyle(sizeRatio = it) }
        letterSpacingEmOf(computed["letter-spacing"])?.let { styles += InlineStyle(letterSpacingEm = it) }
        grayLevelOf(computed["color"])?.let { styles += InlineStyle(grayLevel = it) }
        return styles.toList()
    }

    /**
     * Maps the honored block slice of [computed] into a [BlockStyle]. text-align is
     * inherited (a centered `body` centers its paragraphs); margins are not. Every field is
     * null when unspecified or unusable, so an all-null [BlockStyle] means "publisher said
     * nothing" and equals the default.
     */
    private fun blockStyleFrom(computed: ComputedStyle): BlockStyle = BlockStyle(
        align = alignOf(computed["text-align"]),
        marginTopEm = lengthToEm(computed["margin-top"]),
        marginBottomEm = lengthToEm(computed["margin-bottom"]),
        textIndentEm = lengthToEm(computed["text-indent"]),
        lineHeightMultiplier = lineHeightMultiplierOf(computed["line-height"]),
    )

    /**
     * The element's font-size as a ratio against the document baseline, or null for "no
     * signal" (reader's size governs). Prefers [ComputedStyle.fontSizeRatio] — the
     * cascade's composed `em`/`rem`/`%`/keyword ratio — and only falls back to interpreting
     * the raw `px`/`pt` value against the chapter [baselinePx] when that ratio is null. A
     * ratio of 1.0 (same as the baseline, including a body-size element merely inheriting an
     * absolute size) is suppressed so it doesn't paint a redundant span on every run.
     */
    private fun sizeRatioOf(computed: ComputedStyle): Float? {
        computed.fontSizeRatio?.let { return if (abs(it - 1f) < RATIO_EPSILON) null else it }
        val raw = computed["font-size"] ?: return null
        val px = absolutePx(raw) ?: return null
        val base = baselinePx ?: return null
        val ratio = px / base
        return if (abs(ratio - 1f) < RATIO_EPSILON) null else ratio
    }

    private fun alignOf(value: String?): TextAlign? = when (value) {
        "left", "start" -> TextAlign.LEFT
        "right", "end" -> TextAlign.RIGHT
        "center" -> TextAlign.CENTER
        "justify" -> TextAlign.JUSTIFY
        else -> null
    }

    /**
     * A length resolved to an em ratio: `em`/`rem` as-is, `%` divided by 100, `px`/`pt`
     * divided by the chapter baseline (null with no baseline — never a guess), a bare `0`
     * as zero. Anything else → null. Shared by text-indent, margins and letter-spacing.
     */
    private fun lengthToEm(value: String?): Float? {
        val v = value?.trim() ?: return null
        if (v == "0") return 0f
        emOrRemRegex.matchEntire(v)?.let { return it.groupValues[1].toFloatOrNull() }
        percentRegex.matchEntire(v)?.let { m -> return m.groupValues[1].toFloatOrNull()?.div(100f) }
        absolutePx(v)?.let { px -> return baselinePx?.let { px / it } }
        return null
    }

    private fun letterSpacingEmOf(value: String?): Float? {
        val v = value?.trim() ?: return null
        if (v == "normal") return null
        return lengthToEm(v)
    }

    /** unitless → as-is; `%`/`em`/`rem` → ratio; `normal` (and unusable `px`/etc.) → null. */
    private fun lineHeightMultiplierOf(value: String?): Float? {
        val v = value?.trim() ?: return null
        if (v == "normal") return null
        unitlessNumberRegex.matchEntire(v)?.let { return it.groupValues[1].toFloatOrNull() }
        emOrRemRegex.matchEntire(v)?.let { return it.groupValues[1].toFloatOrNull() }
        percentRegex.matchEntire(v)?.let { m -> return m.groupValues[1].toFloatOrNull()?.div(100f) }
        return null
    }

    private fun isMonospaceFamily(value: String?): Boolean {
        val v = value ?: return false
        return MONOSPACE_FAMILY_HINTS.any { it in v }
    }

    /** The luminance gray level of a CSS colour, memoized; unparseable → null. */
    private fun grayLevelOf(value: String?): Float? {
        val v = value?.trim() ?: return null
        colorMemo[v]?.let { return it }
        if (colorMemo.containsKey(v)) return null
        val gray = parseColorLuminance(v)
        colorMemo[v] = gray
        return gray
    }

    private fun parseColorLuminance(value: String): Float? {
        val rgb = parseRgb(value) ?: return null
        val (r, g, b) = rgb
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }

    private fun parseRgb(value: String): Triple<Int, Int, Int>? {
        NAMED_COLORS[value]?.let { return it }
        if (value.startsWith("#")) {
            val hex = value.substring(1)
            return when (hex.length) {
                3 -> {
                    val r = hexPair(hex[0], hex[0]) ?: return null
                    val g = hexPair(hex[1], hex[1]) ?: return null
                    val b = hexPair(hex[2], hex[2]) ?: return null
                    Triple(r, g, b)
                }
                6 -> {
                    val r = hexPair(hex[0], hex[1]) ?: return null
                    val g = hexPair(hex[2], hex[3]) ?: return null
                    val b = hexPair(hex[4], hex[5]) ?: return null
                    Triple(r, g, b)
                }
                else -> null
            }
        }
        rgbFuncRegex.matchEntire(value)?.let { m ->
            val r = m.groupValues[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
            val g = m.groupValues[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
            val b = m.groupValues[3].toIntOrNull()?.coerceIn(0, 255) ?: return null
            return Triple(r, g, b)
        }
        return null
    }

    private fun hexPair(hi: Char, lo: Char): Int? {
        val h = Character.digit(hi, 16)
        val l = Character.digit(lo, 16)
        if (h < 0 || l < 0) return null
        return h * 16 + l
    }

    private fun isBoldValue(value: String?): Boolean {
        if (value == null) return false
        if (value == "bold" || value == "bolder") return true
        return (value.toIntOrNull() ?: return false) >= 600
    }

    /**
     * The element's OWN emphasis (tag mapping unioned with its single-element cascade
     * declarations), used only to decide whether an unknown container should join the
     * surrounding inline run. Kept separate from [resolveInlineStyles] on purpose: an
     * inherited colour/size must never flip routing and merge a block-holding container
     * into one run with its siblings.
     */
    private fun ownEmphasisStyles(el: Element, css: CssRules): List<InlineStyle> {
        val styles = LinkedHashSet<InlineStyle>()
        inlineStyleOf(el.tagName().lowercase())?.let { styles += it }
        val declarations = declarationsFor(el, css)
        if (declarations["font-style"] in ITALIC_KEYWORDS) styles += InlineStyle(italic = true)
        if (isBoldValue(declarations["font-weight"])) styles += InlineStyle(bold = true)
        return styles.toList()
    }

    private fun declarationsFor(el: Element, css: CssRules): Map<String, String> =
        css.declarationsFor(el.tagName().lowercase(), el.classNames().toList(), el.attr("style").ifBlank { null })

    /**
     * Promotes [text] from a plain paragraph to a [Block.Heading] when [inferHeadings] is
     * on and [el]'s resolved style says it visually reads as one — see [headingLevelFor].
     * This is the ONLY place a [Block.Heading] is produced from non-semantic markup; a
     * literal `<h1>`..`<h6>` always wins regardless of CSS (handled directly in [dispatch])
     * and is never routed through here. The resolved [style] rides along on whichever block
     * type wins — inference decides the type, not the style.
     */
    private fun paragraphOrHeading(el: Element, text: StyledText, style: BlockStyle, css: CssRules, inferHeadings: Boolean): Block {
        if (inferHeadings) {
            headingLevelFor(el, text, css)?.let { return Block.Heading(it, text, style) }
        }
        return Block.Paragraph(text, style)
    }

    /**
     * A `<p>`/`<div>` reads as a heading when its text is short (≤120 chars, non-empty)
     * AND its resolved style is visually heading-like: either a font-size ratio ≥ 1.2
     * relative to the body baseline, or centered text that is also bold (with no size
     * signal, that combination lands at level 3). Returns null — "no heading signal" —
     * rather than guessing, so an unusable size (see [headingSizeRatioFor]) never promotes.
     *
     * The style consulted is [effectiveHeadingDeclarations], not just [el]'s own: a
     * calibre-style export routinely puts the actual size/weight several wrapper elements
     * below the `<p>` (`<p class="calibre_7"><a><span class="calibre2">Title</span></a>`),
     * with the `<p>` itself carrying only layout declarations like `text-align: center`.
     */
    private fun headingLevelFor(el: Element, text: StyledText, css: CssRules): Int? {
        if (text.text.isEmpty() || text.text.length > 120) return null

        val declarations = effectiveHeadingDeclarations(el, css)
        val ratio = declarations["font-size"]?.let { headingSizeRatioFor(it) }
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
     * Resolves a `font-size` value to a heading-inference ratio relative to normal body
     * text. `em`/`rem`, `%`, and the CSS size keywords are self-contained and always usable.
     * `px`/`pt` are only meaningful relative to the chapter [baselinePx]; without one an
     * absolute size is unusable and this returns 1.0 ("no signal"), never a guess. This is
     * the single-value inference path — distinct from [sizeRatioOf], which prefers the
     * cascade's composed ratio for the rendered span.
     */
    private fun headingSizeRatioFor(rawValue: String): Float? {
        relativeSizeRatio(rawValue)?.let { return it }
        val px = absolutePx(rawValue) ?: return null
        val baselinePx = baselinePx ?: return 1.0f
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
     * The absolute pixel baseline that a `px`/`pt` font-size (and length) is judged
     * against: the resolved `font-size` of `body`, else of `html`. Itself must resolve to
     * an absolute size (not `1em`/`100%`/a keyword) — a relative baseline has no fixed
     * meaning here, and a relative descendant size composes into [ComputedStyle.fontSizeRatio]
     * anyway — so [absolutePx] is reused rather than [relativeSizeRatio]. Mined once per
     * chapter in [parse].
     */
    private fun mineBaseline(css: CssRules): Float? {
        val bodySize = css.declarationsFor("body", emptyList(), null)["font-size"]
        val htmlSize = css.declarationsFor("html", emptyList(), null)["font-size"]
        val raw = bodySize ?: htmlSize ?: return null
        return absolutePx(raw)
    }

    companion object {
        private val ITALIC_KEYWORDS = setOf("italic", "oblique")

        private const val RATIO_EPSILON = 1e-4f

        // See isInlineByTag/containsBlockLevelDescendant.
        private val INLINE_TAGS = setOf(
            "span", "a", "small", "sub", "sup", "font",
            "b", "strong", "i", "em", "cite", "dfn",
            "code", "kbd", "samp", "tt",
        )
        private val BLOCK_LEVEL_TAGS = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "img",
        )

        // Substrings that mark a font-family as monospace — we substitute the reader's mono
        // face, so the exact family name never matters, only that it is a monospace one.
        private val MONOSPACE_FAMILY_HINTS = setOf(
            "monospace", "courier", "consol", "menlo", "monaco", "mono ", "\"mono", "'mono",
        )

        private val emOrRemRegex = Regex("^(-?\\d*\\.?\\d+)(?:em|rem)$")
        private val percentRegex = Regex("^(-?\\d*\\.?\\d+)%$")
        private val pxRegex = Regex("^(-?\\d*\\.?\\d+)px$")
        private val ptRegex = Regex("^(-?\\d*\\.?\\d+)pt$")
        private val unitlessNumberRegex = Regex("^(-?\\d*\\.?\\d+)$")
        private val rgbFuncRegex = Regex("^rgb\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)$")

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

        // A small map of the common CSS named colours — enough to resolve a luminance for
        // the ones publishers actually use; an unknown name resolves to null (no gray span).
        private val NAMED_COLORS: Map<String, Triple<Int, Int, Int>> = mapOf(
            "black" to Triple(0, 0, 0),
            "white" to Triple(255, 255, 255),
            "gray" to Triple(128, 128, 128),
            "grey" to Triple(128, 128, 128),
            "silver" to Triple(192, 192, 192),
            "dimgray" to Triple(105, 105, 105),
            "dimgrey" to Triple(105, 105, 105),
            "darkgray" to Triple(169, 169, 169),
            "darkgrey" to Triple(169, 169, 169),
            "lightgray" to Triple(211, 211, 211),
            "lightgrey" to Triple(211, 211, 211),
            "red" to Triple(255, 0, 0),
            "maroon" to Triple(128, 0, 0),
            "green" to Triple(0, 128, 0),
            "lime" to Triple(0, 255, 0),
            "blue" to Triple(0, 0, 255),
            "navy" to Triple(0, 0, 128),
            "yellow" to Triple(255, 255, 0),
            "olive" to Triple(128, 128, 0),
            "purple" to Triple(128, 0, 128),
            "teal" to Triple(0, 128, 128),
            "aqua" to Triple(0, 255, 255),
            "cyan" to Triple(0, 255, 255),
            "fuchsia" to Triple(255, 0, 255),
            "magenta" to Triple(255, 0, 255),
            "orange" to Triple(255, 165, 0),
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

    /**
     * A block boundary inside flattened inline content (a `<p>` child of an `<li>`, a
     * nested list, ...): guarantees a line break separates whatever text sits on either
     * side, without stacking blank lines when boundaries meet ([appendLineBreak], by
     * contrast, always appends — two literal `<br/>`s SHOULD produce a blank line).
     */
    fun appendBlockBoundary() {
        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
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
