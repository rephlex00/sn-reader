package dev.reader.engine

/**
 * Whether [text] is a "scene break" separator line — a line the reader renders as blank vertical
 * space (a scene break) instead of leaving as flush body text (e.g. "***", "* * *", "---", "· · ·").
 * True when the trimmed text is non-empty and contains at least one non-whitespace character but NO
 * letters or digits (only punctuation/symbols and spaces). Pure.
 */
fun isSeparatorLine(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.none { !it.isWhitespace() }) return false
    return trimmed.none { it.isLetterOrDigit() }
}
