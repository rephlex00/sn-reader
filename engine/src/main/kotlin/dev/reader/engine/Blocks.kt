package dev.reader.engine

/**
 * A resolved inline style run.
 *
 * Every field is optional and defaults to null. **Null means "the publisher's stylesheet
 * said nothing about this property" — the reader's own value applies.** A concrete value
 * means the publisher specified it; whether that value is honored is the *builder's*
 * decision, not this model's (it is gated on the publisher-styling toggle at render time).
 *
 * The parser resolves the publisher's honored CSS into every field: [bold]/[italic] from
 * font-weight/style, [monospace] from a monospace font-family, [sizeRatio] from font-size
 * (as a ratio, see below), [underline]/[strikethrough] from text-decoration,
 * [letterSpacingEm] from letter-spacing, and [grayLevel] from color. A field the publisher
 * left unspecified stays null. Whether a resolved value is actually rendered is decided at
 * render time by the publisher-styling toggle, not here.
 *
 * @property sizeRatio font size **relative** to the reader's base size, never an absolute
 *   point size — a publisher size is resolved to a ratio against the document baseline so
 *   the reader's chosen text size still governs.
 * @property grayLevel 0f..1f luminance; the e-ink panel has no colour, so `color` maps to
 *   a gray level.
 */
data class InlineStyle(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val monospace: Boolean? = null,
    val sizeRatio: Float? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    val letterSpacingEm: Float? = null,
    val grayLevel: Float? = null,
)

/**
 * Paragraph-level resolved style.
 *
 * Every field is optional and defaults to null, with the same meaning as [InlineStyle]:
 * **null means the publisher specified nothing and the reader's default applies.** The
 * parser resolves [align] from text-align, [textIndentEm] from text-indent, [marginTopEm]/
 * [marginBottomEm] from margins, and [lineHeightMultiplier] from line-height. Whether a
 * resolved value is rendered is a render-time decision: text-align and text-indent are
 * applied under the publisher-styling toggle, while margins and line-height are resolved
 * but deliberately not applied (see [dev.reader.formats.render] — margins are deferred and
 * line spacing is always the reader's).
 */
data class BlockStyle(
    val align: TextAlign? = null,
    val marginTopEm: Float? = null,
    val marginBottomEm: Float? = null,
    val textIndentEm: Float? = null,
    val lineHeightMultiplier: Float? = null,
)

/** Publisher text alignment. Lives in `:engine`, which stays Android-free. */
enum class TextAlign { LEFT, RIGHT, CENTER, JUSTIFY }

data class StyleSpan(val start: Int, val end: Int, val style: InlineStyle) {
    init {
        require(start >= 0) { "start must be non-negative, was $start" }
        require(end > start) { "end ($end) must exceed start ($start)" }
    }
}

data class StyledText(val text: String, val spans: List<StyleSpan> = emptyList()) {
    init {
        spans.forEach {
            require(it.end <= text.length) {
                "span [${it.start}, ${it.end}) exceeds text length ${text.length}"
            }
        }
    }
}

/**
 * The format-neutral document model. EPUB XHTML is reduced to this whitelist;
 * everything else in the source is discarded.
 *
 * The text blocks carry a resolved [BlockStyle]; it defaults to an all-null [BlockStyle]
 * so existing positional construction stays source-compatible.
 */
sealed interface Block {
    data class Paragraph(val text: StyledText, val style: BlockStyle = BlockStyle()) : Block
    data class Heading(val level: Int, val text: StyledText, val style: BlockStyle = BlockStyle()) : Block {
        init { require(level in 1..6) { "heading level must be 1..6, was $level" } }
    }
    data class Quote(val text: StyledText, val style: BlockStyle = BlockStyle()) : Block
    data class ListItem(val text: StyledText, val ordinal: Int? = null, val style: BlockStyle = BlockStyle()) : Block
    data class Image(val href: String) : Block {
        init { require(href.isNotBlank()) { "href must not be blank" } }
    }
    data object PageBreak : Block
}
