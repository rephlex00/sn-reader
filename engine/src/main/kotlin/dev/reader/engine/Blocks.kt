package dev.reader.engine

/**
 * A resolved inline style run.
 *
 * Every field is optional and defaults to null. **Null means "the publisher's stylesheet
 * said nothing about this property" — the reader's own value applies.** A concrete value
 * means the publisher specified it; whether that value is honored is the *builder's*
 * decision, not this model's (it is gated on the publisher-styling toggle at render time).
 *
 * The parser resolves publisher CSS into these fields. As of this task only [bold],
 * [italic] and [monospace] are ever populated — they mirror the three semantic emphases
 * the previous enum carried. The remaining fields ([sizeRatio], [underline],
 * [strikethrough], [letterSpacingEm], [grayLevel]) exist in the shape but stay null
 * everywhere; the parser begins populating them in a later task.
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
 * **null means the publisher specified nothing and the reader's default applies.** As of
 * this task the parser never populates any field — the shape exists so downstream tasks
 * can carry resolved block styling without another model change.
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
