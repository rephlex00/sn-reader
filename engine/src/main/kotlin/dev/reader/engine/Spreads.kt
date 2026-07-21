package dev.reader.engine

/**
 * Spread navigation: moving through a book two pages at a time, the way a landscape screen shows
 * them (see [RenderConfig.columnCount]).
 *
 * A spread is two CONSECUTIVE pages of the SAME chapter, and its left page is always even. Nothing
 * below is a new pagination concept — pages are still pages, produced by the same [Paginator] over
 * a column-width layout. These functions only decide which page a turn lands on, and they delegate
 * every hard case (crossing a chapter boundary, skipping a chapter that paginates to nothing) to
 * the page-at-a-time [advance] and [retreat] that already solve it.
 *
 * A spread deliberately never straddles a chapter boundary: a chapter's layout is its own, so its
 * pages cannot be measured against the next chapter's. An odd-length chapter therefore ends with a
 * blank right column, exactly as a printed book does.
 */

/**
 * The left page of the spread that owns [pageIndex] — the page itself when even, the one before it
 * when odd. Every path that sets a page in two-column mode runs through this, so a TOC jump, a
 * bookmark, or a locator restored after rotation lands on a whole spread instead of splitting one.
 */
fun spreadStart(pageIndex: Int): Int = pageIndex - pageIndex.mod(2)

/**
 * The next spread's left page, or null at the end of the book.
 *
 * Inside the chapter this is simply two pages on. At the chapter's end it hands off to [advance]
 * FROM THE CHAPTER'S LAST PAGE — not from the current state, whose left page may be two short of
 * the end — because [advance] is what knows the next chapter starts at page 0 and what skips
 * chapters that paginate to zero pages. Page 0 is even, so the landing is already a spread start.
 */
fun advanceSpread(
    navigator: PageNavigator,
    state: ReadingState,
    pageCountFor: (Int) -> Int,
): ReadingState? {
    val left = spreadStart(state.pageIndex)
    val pageCount = pageCountFor(state.spineIndex)
    if (left + 2 < pageCount) return ReadingState(state.spineIndex, left + 2)
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    return advance(navigator, ReadingState(state.spineIndex, lastPage), pageCountFor)
}

/**
 * The previous spread's left page, or null at the start of the book.
 *
 * Inside the chapter this is two pages back. At the chapter's start it hands off to [retreat], which
 * resolves the previous non-empty chapter's LAST page — an arbitrary index, so it is aligned down to
 * its spread start here. Without that alignment, walking backwards out of a chapter with an odd page
 * count would leave every following spread off by one, pairing pages that were never paired forwards.
 */
fun retreatSpread(
    navigator: PageNavigator,
    state: ReadingState,
    pageCountFor: (Int) -> Int,
): ReadingState? {
    val left = spreadStart(state.pageIndex)
    if (left >= 2) return ReadingState(state.spineIndex, left - 2)
    val previous = retreat(navigator, ReadingState(state.spineIndex, 0), pageCountFor) ?: return null
    return ReadingState(previous.spineIndex, spreadStart(previous.pageIndex))
}
