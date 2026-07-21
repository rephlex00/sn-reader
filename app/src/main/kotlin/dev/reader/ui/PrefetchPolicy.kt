package dev.reader.ui

import dev.reader.engine.ReadingState

/**
 * Decides which adjacent chapter (if any) is worth paginating in the background right now, purely
 * from the settled reading position. Returns the neighbour's spineIndex, or null for "prefetch
 * nothing" — the mid-chapter case, the ends of the book, and the empty-chapter degenerate case.
 *
 * The rule mirrors the only two moves a boundary page turn can make:
 *  - On the LAST page of the chapter, the next turn crosses into the next chapter — so if a next
 *    chapter exists, prefetch it. Checked first, giving a forward bias: on a single-page chapter
 *    (first page == last page) the reader is far likelier to turn forward, so `next` wins over
 *    `previous` unless there is no next chapter.
 *  - On the FIRST page, a backward turn crosses into the previous chapter — so if a previous chapter
 *    exists, prefetch it. (After a normal forward read this neighbour is almost always still cached,
 *    so the caller skips the launch; it earns its keep on a TOC jump that lands on a fresh chapter.)
 *  - Anywhere in the interior, both neighbours of a turn stay within this already-paginated chapter,
 *    so there is nothing to prefetch.
 *
 * [pagesPerTurn] is how many pages one turn moves — 2 for a landscape spread, 1 otherwise. It is
 * what makes "the next turn leaves this chapter" mean the same thing in both orientations: a spread
 * showing the last two pages of an even-length chapter sits at `chapterPageCount - 2`, so a
 * single-page rule would call it mid-chapter, prefetch nothing, and hand the boundary turn a
 * main-thread pagination — precisely the hitch this exists to avoid.
 *
 * Pure and total: no I/O, no allocation, defined for every input. A non-positive [chapterPageCount]
 * (an empty/degenerate chapter, where "first" and "last" have no meaning) yields null rather than a
 * guess. The [pageIndex] comparisons use `<=`/`>=` so a value the caller has clamped past the real
 * bounds still resolves to the nearest boundary rather than falling through to null.
 */
internal fun chapterToPrefetch(
    state: ReadingState,
    chapterPageCount: Int,
    spineSize: Int,
    pagesPerTurn: Int = 1,
): Int? {
    if (chapterPageCount <= 0) return null

    val onLastPage = state.pageIndex + pagesPerTurn.coerceAtLeast(1) >= chapterPageCount
    val onFirstPage = state.pageIndex <= 0
    val nextIndex = state.spineIndex + 1
    val previousIndex = state.spineIndex - 1

    return when {
        onLastPage && nextIndex < spineSize -> nextIndex
        onFirstPage && previousIndex >= 0 -> previousIndex
        else -> null
    }
}
