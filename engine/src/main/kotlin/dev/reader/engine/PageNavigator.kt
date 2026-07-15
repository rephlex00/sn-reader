package dev.reader.engine

/** Where the reader currently is. */
data class ReadingState(val spineIndex: Int, val pageIndex: Int)

/**
 * The result of a page turn. [LastPageOf] exists because the previous chapter's page
 * count is unknown until it is paginated — the caller resolves it after measuring.
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
