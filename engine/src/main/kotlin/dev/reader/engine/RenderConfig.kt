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
