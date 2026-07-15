package dev.reader.engine

enum class InlineStyle { BOLD, ITALIC, MONOSPACE }

data class StyleSpan(val start: Int, val end: Int, val style: InlineStyle) {
    init {
        require(start >= 0) { "start must be non-negative, was $start" }
        require(end > start) { "end ($end) must exceed start ($start)" }
    }
}

data class StyledText(val text: String, val spans: List<StyleSpan> = emptyList())

/**
 * The format-neutral document model. EPUB XHTML is reduced to this whitelist;
 * everything else in the source is discarded.
 */
sealed interface Block {
    data class Paragraph(val text: StyledText) : Block
    data class Heading(val level: Int, val text: StyledText) : Block {
        init { require(level in 1..6) { "heading level must be 1..6, was $level" } }
    }
    data class Quote(val text: StyledText) : Block
    data class ListItem(val text: StyledText, val ordinal: Int? = null) : Block
    data class Image(val href: String) : Block
    data object PageBreak : Block
}
