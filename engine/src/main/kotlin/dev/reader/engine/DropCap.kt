package dev.reader.engine

/**
 * How many leading characters of [paragraph] get a drop cap: 1 when the paragraph starts with a
 * letter, else 0 (a leading quote, digit, whitespace, or empty paragraph gets none). Pure.
 *
 * Only a leading letter caps — a paragraph opening on a quotation mark, a number (e.g. "1984"),
 * or leading whitespace reads better without an enlarged initial, and mis-capping punctuation
 * looks broken. Kept to a single character: the drop cap covers exactly the initial glyph, so a
 * caller applies its span over `[first, first + dropCapLength)` and the initial stays a normal
 * character in the text — character offsets, locators, and page-break math are unchanged.
 */
fun dropCapLength(paragraph: String): Int =
    if (paragraph.isNotEmpty() && paragraph.first().isLetter()) 1 else 0
