package dev.reader.engine

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
) {
    val contentWidthPx: Int get() = viewportWidthPx - marginPx * 2
    val contentHeightPx: Int get() = viewportHeightPx - marginPx * 2

    init {
        require(fontFamily.isNotBlank()) { "fontFamily must not be blank" }
        require(textSizePx > 0f) { "textSizePx must be positive, was $textSizePx" }
        require(lineSpacingMultiplier > 0f) {
            "lineSpacingMultiplier must be positive, was $lineSpacingMultiplier"
        }
        require(marginPx >= 0) { "marginPx must be non-negative, was $marginPx" }
        require(contentWidthPx > 0) { "margins leave no content width: $this" }
        require(contentHeightPx > 0) { "margins leave no content height: $this" }
    }
}
