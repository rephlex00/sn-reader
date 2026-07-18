package dev.reader.engine

import java.text.BreakIterator

/** A highlighted character range `[start, end)` into a chapter's source text. Always non-empty. */
data class HighlightRange(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "start must be in 0..end, was $start..$end" }
        require(start < end) { "a highlight range must be non-empty, was $start..$end" }
    }
}

/** An existing highlight reduced to what merge/containment reasoning needs. */
data class ExistingHighlight(val id: Long, val start: Int, val end: Int)

/** The outcome of merging a new range with the chapter's existing highlights (see [mergeHighlights]). */
data class MergeResult(val merged: HighlightRange, val removedIds: List<Long>)

/**
 * Snaps a raw selection to whole-word boundaries: the start expands left to the start of the word it
 * lands in, the end expands right to the end of the word it lands in. A single point (`start == end`)
 * returns the enclosing word. Returns null when the snapped span is empty or whitespace-only (a tap
 * or drag that landed between words), which the caller treats as "no highlight". Pure — uses
 * [BreakIterator], available on plain JVM, so it is unit-testable without a device.
 */
fun snapToWords(text: CharSequence, start: Int, end: Int): HighlightRange? {
    val len = text.length
    if (len == 0) return null
    val a = start.coerceIn(0, len)
    val b = end.coerceIn(0, len)
    val lo = minOf(a, b)
    val hi = maxOf(a, b)
    val bi = BreakIterator.getWordInstance()
    bi.setText(text.toString())

    val wordStart = if (bi.isBoundary(lo)) lo else bi.preceding(lo).let { if (it == BreakIterator.DONE) 0 else it }
    // For a point, extend from the same index so a zero-width selection captures its enclosing word.
    val wordEnd = when {
        hi > wordStart && bi.isBoundary(hi) -> hi
        else -> bi.following(hi).let { if (it == BreakIterator.DONE) len else it }
    }
    if (wordEnd <= wordStart) return null
    if (text.subSequence(wordStart, wordEnd).isBlank()) return null
    return HighlightRange(wordStart, wordEnd)
}

/**
 * Merges [new] with every existing highlight it overlaps or abuts, transitively: widening the range
 * can bring a previously-untouched neighbour into contact, so this loops to a fixed point. Returns the
 * unioned range and the ids of the existing highlights it subsumed (the caller deletes those and
 * inserts the merged one). Two ranges `[a,b)` and `[c,d)` touch when `c <= b && a <= d` (equality is
 * the abutting case). Pure.
 */
fun mergeHighlights(existing: List<ExistingHighlight>, new: HighlightRange): MergeResult {
    var lo = new.start
    var hi = new.end
    val removed = mutableListOf<Long>()
    val remaining = existing.toMutableList()
    var changed = true
    while (changed) {
        changed = false
        val iter = remaining.iterator()
        while (iter.hasNext()) {
            val h = iter.next()
            if (h.start <= hi && lo <= h.end) {
                lo = minOf(lo, h.start)
                hi = maxOf(hi, h.end)
                removed += h.id
                iter.remove()
                changed = true
            }
        }
    }
    return MergeResult(HighlightRange(lo, hi), removed)
}

/** The highlight whose half-open range `[start, end)` covers [offset], or null. Pure. */
fun highlightContaining(highlights: List<ExistingHighlight>, offset: Int): ExistingHighlight? =
    highlights.firstOrNull { offset >= it.start && offset < it.end }

/**
 * A one-line panel excerpt from a highlight's captured [text]: internal whitespace collapsed, trimmed,
 * and ellipsized to [maxChars] (the ellipsis counts toward the cap). Pure.
 */
fun highlightExcerpt(text: String, maxChars: Int = 60): String {
    val collapsed = text.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length <= maxChars) collapsed else collapsed.take(maxChars - 1).trimEnd() + "…"
}
