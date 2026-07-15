package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the [advance] and [retreat] pure functions extracted from `ReaderActivity`'s
 * `advance()`/`retreat()` — the two empty-chapter loop fixes for `PageNavigator`'s two documented
 * traps. These are the riskiest new logic in Task 10 and were previously verified by inspection
 * only; this file exercises them directly via a fake `pageCountFor` lookup, no Android/document
 * dependency required.
 */
class PageTurnsTest {

    @Test
    fun `advance skips a single empty chapter`() {
        // spine: [0]=5 pages, [1]=EMPTY, [2]=3 pages. Landing on the empty chapter must roll
        // forward to the next non-empty one instead of stalling there.
        val nav = PageNavigator(spineSize = 3)
        val pageCounts = mapOf(0 to 5, 1 to 0, 2 to 3)
        val result = advance(nav, ReadingState(0, 4)) { pageCounts.getValue(it) }
        assertThat(result).isEqualTo(ReadingState(2, 0))
    }

    @Test
    fun `advance skips two consecutive empty chapters`() {
        // spine: [0]=2 pages, [1]=EMPTY, [2]=EMPTY, [3]=4 pages.
        val nav = PageNavigator(spineSize = 4)
        val pageCounts = mapOf(0 to 2, 1 to 0, 2 to 0, 3 to 4)
        val result = advance(nav, ReadingState(0, 1)) { pageCounts.getValue(it) }
        assertThat(result).isEqualTo(ReadingState(3, 0))
    }

    @Test
    fun `advance reaches AtEnd through a book of all-empty chapters and terminates`() {
        // Every chapter in a 5-chapter book is empty. Must terminate at null (AtEnd) rather
        // than looping forever or overflowing the stack/recursion.
        val nav = PageNavigator(spineSize = 5)
        val result = advance(nav, ReadingState(0, 0)) { 0 }
        assertThat(result).isNull()
    }

    @Test
    fun `retreat skips past an empty chapter`() {
        // spine: [0]=3 pages, [1]=EMPTY, [2]=2 pages. Retreating from chapter 2's first page
        // must land on chapter 0's last page, not chapter 1 (which has no last page).
        val nav = PageNavigator(spineSize = 3)
        val pageCounts = mapOf(0 to 3, 1 to 0, 2 to 2)
        val result = retreat(nav, ReadingState(2, 0)) { pageCounts.getValue(it) }
        assertThat(result).isEqualTo(ReadingState(0, 2))
    }

    @Test
    fun `retreat reaches the start of the book`() {
        val nav = PageNavigator(spineSize = 2)
        val result = retreat(nav, ReadingState(0, 0)) { 5 }
        assertThat(result).isNull()
    }

    @Test
    fun `retreat never yields a negative pageIndex`() {
        // Every chapter before the current one is empty; retreat must terminate at null instead
        // of computing pageCount - 1 == -1 on an empty chapter.
        val nav = PageNavigator(spineSize = 3)
        val result = retreat(nav, ReadingState(2, 0)) { 0 }
        assertThat(result).isNull()
        // Also confirm directly: no reachable ReadingState from this function ever carries a
        // negative pageIndex, since ReadingState's own init block would throw if it did.
    }
}
