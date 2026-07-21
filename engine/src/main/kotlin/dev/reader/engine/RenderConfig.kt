package dev.reader.engine

/**
 * The whitespace between the columns of a landscape spread, in raw pixels. Roughly twice the
 * default margin: the two columns have to read as two pages, and a gap merely equal to the margin
 * makes the inner edges look like one block of text that happens to have a hole in it. Nothing is
 * ever drawn here.
 */
const val COLUMN_GAP_PX = 140

/**
 * How many columns a viewport of this shape should be read in: two when it is wider than it is
 * tall, one otherwise. A single column across a landscape panel runs to roughly a hundred
 * characters a line, far past what reads comfortably, so landscape is the case that wants
 * splitting; portrait never does.
 *
 * The square case (width == height) takes one column — no real panel is square, and one column is
 * the conservative answer for a shape this rule was not written for.
 */
fun columnCountFor(viewportWidthPx: Int, viewportHeightPx: Int): Int =
    if (viewportWidthPx > viewportHeightPx) 2 else 1

/**
 * The width of ONE column. The single source of truth for column arithmetic: [RenderConfig] uses it
 * to measure the text and `PageView` uses it to place the second column, so the drawn column and the
 * measured column cannot drift apart — a mismatch of even one pixel from a differently-rounded
 * division would clip glyphs at a column edge.
 *
 * Integer division truncates, so with an odd remainder the columns are equal and up to
 * [columnCount] - 1 pixels go unused at the right margin. That is the right way to lose the
 * remainder: unequal columns would be visible, one unused pixel is not.
 */
fun columnWidthPx(viewportWidthPx: Int, marginPx: Int, columnCount: Int, columnGapPx: Int): Int {
    val inner = viewportWidthPx - marginPx * 2 - columnGapPx * (columnCount - 1)
    return inner / columnCount
}

/**
 * Reader-controlled typography. Publisher CSS never contributes to these values —
 * they come from user settings and always win.
 */
data class RenderConfig(
    val fontFamily: String,
    val textSizePx: Float,
    val lineSpacingMultiplier: Float,
    val marginPx: Int,
    val justified: Boolean,
    val hyphenated: Boolean,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    /**
     * Promote short, visually-heading-like paragraphs to headings on books that encode
     * structure as CSS classes instead of <h1>..<h6> (calibre conversions, overwhelmingly).
     * A heuristic, so it is a reader setting; emphasis recovery is a direct mapping and is
     * always on. Lives here because it changes the produced Blocks and RenderConfig already
     * keys the pagination cache — so toggling it invalidates cached chapters for free.
     */
    val inferHeadings: Boolean = true,
    /**
     * Honor the publisher's resolved CSS at render time. When true, the builder maps every
     * non-null [InlineStyle]/[BlockStyle] field the publisher specified onto a span (size,
     * decoration, colour→gray, alignment, indent, line-height); a null field falls back to
     * the reader's own value. When false, every publisher field is ignored and rendering
     * reproduces the reader-only typography exactly — the semantic heading scale, the fixed
     * block separator, bold/italic/monospace emphasis only. A render-time decision, so the
     * publisher's styles are resolved once and this toggle costs a re-render, not a re-parse;
     * like [inferHeadings] it keys the pagination cache, so flipping it re-paginates for free.
     */
    val publisherStyling: Boolean = true,
    /**
     * Columns the screen is split into — 1 in portrait, 2 for a landscape spread (see
     * [columnCountFor]). The engine has no other notion of a column: this only narrows
     * [contentWidthPx], which makes every page a column-sized page, and the display layer draws two
     * consecutive pages side by side. Pagination, locators, bookmarks and highlights are untouched.
     *
     * Being a field of this data class also means a rotation invalidates the pagination cache for
     * free, exactly as a font change does.
     */
    val columnCount: Int = 1,
    /** Whitespace between columns; ignored when [columnCount] is 1. See [COLUMN_GAP_PX]. */
    val columnGapPx: Int = COLUMN_GAP_PX,
) {
    /**
     * The width of one column of text — NOT the full width between the margins. With
     * [columnCount] = 1 the two are the same, which is why every caller measuring text against this
     * kept working unchanged when columns arrived.
     */
    val contentWidthPx: Int get() = columnWidthPx(viewportWidthPx, marginPx, columnCount, columnGapPx)
    val contentHeightPx: Int get() = viewportHeightPx - marginPx * 2

    init {
        require(fontFamily.isNotBlank()) { "fontFamily must not be blank" }
        require(textSizePx > 0f) { "textSizePx must be positive, was $textSizePx" }
        require(lineSpacingMultiplier > 0f) {
            "lineSpacingMultiplier must be positive, was $lineSpacingMultiplier"
        }
        require(marginPx >= 0) { "marginPx must be non-negative, was $marginPx" }
        require(columnCount >= 1) { "columnCount must be at least 1, was $columnCount" }
        require(columnGapPx >= 0) { "columnGapPx must be non-negative, was $columnGapPx" }
        require(contentWidthPx > 0) { "margins leave no content width: $this" }
        require(contentHeightPx > 0) { "margins leave no content height: $this" }
    }
}
