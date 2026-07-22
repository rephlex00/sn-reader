package dev.reader.engine

/**
 * How far through the whole book the reader is, as a fraction in `[0f, 1f]`, weighting each
 * chapter by [weights] (byte sizes are the cheap proxy the reader supplies — see
 * `EpubDocument.chapterWeights`). Pure: same inputs always yield the same fraction.
 *
 * The estimate is `(Σ weight before the current chapter + weight[current] × pageFraction) / Σ weight`,
 * where `pageFraction = pageIndex / pageCount`. It never paginates: [weights] is all it needs.
 *
 * Defensive by construction so a broken book can't crash a page turn: a zero/negative total
 * reads `0f`; [spineIndex] is clamped into [weights]; a non-positive [pageCount] (an empty
 * chapter, which navigation skips) contributes no within-chapter fraction rather than
 * overshooting; and the result is clamped to `[0f, 1f]`. Turning forward never decreases it.
 */
fun bookProgress(weights: List<Long>, spineIndex: Int, pageIndex: Int, pageCount: Int): Float {
    if (weights.isEmpty()) return 0f
    val total = weights.sumOf { maxOf(0L, it) }
    if (total <= 0L) return 0f

    val idx = spineIndex.coerceIn(0, weights.lastIndex)
    val before = (0 until idx).sumOf { maxOf(0L, weights[it]) }
    val pageFraction = if (pageCount <= 0) 0f else pageIndex.coerceIn(0, pageCount).toFloat() / pageCount
    val current = maxOf(0L, weights[idx]).toFloat() * pageFraction

    return ((before + current) / total).coerceIn(0f, 1f)
}

/**
 * The whole-book fraction at which chapter [spineIndex] *ends* — i.e. `bookProgress` for its last
 * page. Drives the progress bar's chapter-end tick, so the reader can see how much of the chapter
 * is left without opening the Contents panel.
 *
 * Defensive on exactly the same terms as [bookProgress], and for the same reason (a broken book
 * must not crash a page turn): an empty or zero-total [weights] reads `0f`, negative weights are
 * floored at zero, [spineIndex] is clamped into range, and the result is clamped to `[0f, 1f]`.
 * The last chapter always ends at `1f`.
 */
fun chapterEndFraction(weights: List<Long>, spineIndex: Int): Float {
    if (weights.isEmpty()) return 0f
    val total = weights.sumOf { maxOf(0L, it) }
    if (total <= 0L) return 0f

    val idx = spineIndex.coerceIn(0, weights.lastIndex)
    val through = (0..idx).sumOf { maxOf(0L, weights[it]) }
    return (through.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Where a whole-book fraction falls: which chapter, and how far into it. The inverse of
 * [bookProgress], used by the scrubber to turn a release position into a reading position.
 */
data class BookLocation(val spineIndex: Int, val fractionWithinChapter: Float)

/**
 * Maps a whole-book [fraction] back to a chapter and a position within it, weighting chapters by
 * [weights] exactly as [bookProgress] does. Pure.
 *
 * Zero-weight chapters are never landed on: they occupy no span, so the scan passes straight over
 * them to the next chapter with real content. That matters because an empty chapter paginates to
 * nothing, and a scrubber that could land on one would show a blank page.
 *
 * Defensive on the same terms as [bookProgress]: an empty or zero-total [weights] returns the start
 * of the book, negative weights are floored at zero, and [fraction] is clamped to `[0f, 1f]` so a
 * finger dragged past either end lands on the first or last page rather than throwing.
 */
fun locateByFraction(weights: List<Long>, fraction: Float): BookLocation {
    if (weights.isEmpty()) return BookLocation(0, 0f)
    val total = weights.sumOf { maxOf(0L, it) }
    if (total <= 0L) return BookLocation(0, 0f)

    val target = fraction.coerceIn(0f, 1f) * total
    var consumed = 0f
    for (index in weights.indices) {
        val weight = maxOf(0L, weights[index]).toFloat()
        if (weight <= 0f) continue // an empty chapter occupies no span — never land on one
        if (target <= consumed + weight) {
            return BookLocation(index, ((target - consumed) / weight).coerceIn(0f, 1f))
        }
        consumed += weight
    }
    // Fraction 1.0, or float drift past the final boundary: the end of the last non-empty chapter.
    val lastNonEmpty = weights.indices.lastOrNull { maxOf(0L, weights[it]) > 0L } ?: 0
    return BookLocation(lastNonEmpty, 1f)
}
