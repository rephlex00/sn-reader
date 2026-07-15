package dev.reader.engine

/** Where the reader currently is. */
data class ReadingState(val spineIndex: Int, val pageIndex: Int) {

    init {
        require(spineIndex >= 0) { "spineIndex must not be negative, was $spineIndex" }
        require(pageIndex >= 0) { "pageIndex must not be negative, was $pageIndex" }
    }
}

/**
 * The result of a page turn. [LastPageOf] exists because the previous chapter's page
 * count is unknown until it is paginated — the caller resolves it after measuring.
 *
 * A chapter with zero pages (`dev.reader.formats.epub.EpubDocument.chapter()`, in the `:formats`
 * module, returns `pages = emptyList()` for a chapter whose file is missing or empty) has no last
 * page. Resolving [LastPageOf] as `ReadingState(spineIndex,
 * pageCount - 1)` yields a bogus `pageIndex = -1` when that chapter is empty. The caller must check
 * the resolved chapter's page count and, if it is zero, skip it and continue calling
 * `previous()`/resolving backward rather than computing `pageCount - 1` on an empty chapter.
 */
sealed interface NavTarget {
    data class Page(val spineIndex: Int, val pageIndex: Int) : NavTarget
    data class LastPageOf(val spineIndex: Int) : NavTarget
    data object AtStart : NavTarget
    data object AtEnd : NavTarget
}

class PageNavigator(private val spineSize: Int) {

    init {
        require(spineSize > 0) { "spineSize must be positive, was $spineSize" }
    }

    /**
     * Returns the next [NavTarget] given the current [state] and the page count of the chapter
     * [state] is currently in.
     *
     * IMPORTANT — caller contract: the returned `Page(spineIndex + 1, 0)` is **not guaranteed to
     * contain any pages**. This method only knows the current chapter's page count (that is its
     * whole signature), so it structurally cannot tell whether the *next* chapter is empty (a
     * chapter whose backing file is missing or empty resolves to zero pages — see
     * `dev.reader.formats.epub.EpubDocument.chapter()` in the `:formats` module). Even a single
     * empty chapter triggers this: `next(ReadingState(0, 4), 5)` returns `Page(1, 0)` regardless of
     * whether chapter 1 has zero pages.
     *
     * The caller MUST check the page count of the chapter it lands on, and if it is zero, call
     * `next` again from that landed-on state — looping until it reaches a chapter with at least
     * one page, or receives [NavTarget.AtEnd]. Do not treat a single `next()` call as sufficient to
     * skip past an empty chapter.
     */
    fun next(state: ReadingState, pagesInCurrentChapter: Int): NavTarget {
        if (state.pageIndex + 1 < pagesInCurrentChapter) {
            return NavTarget.Page(state.spineIndex, state.pageIndex + 1)
        }
        val nextChapter = state.spineIndex + 1
        return if (nextChapter < spineSize) NavTarget.Page(nextChapter, 0) else NavTarget.AtEnd
    }

    fun previous(state: ReadingState): NavTarget = when {
        state.pageIndex > 0 -> NavTarget.Page(state.spineIndex, state.pageIndex - 1)
        state.spineIndex > 0 -> NavTarget.LastPageOf(state.spineIndex - 1)
        else -> NavTarget.AtStart
    }
}
