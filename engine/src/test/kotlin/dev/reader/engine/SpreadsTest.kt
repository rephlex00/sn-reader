package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpreadsTest {

    private val nav = PageNavigator(spineSize = 3)

    /** Chapter 0 has 5 pages, chapter 1 is empty, chapter 2 has 4. */
    private val pageCounts: (Int) -> Int = { listOf(5, 0, 4)[it] }

    @Test
    fun `a spread starts on the even page that owns the index`() {
        assertThat(spreadStart(0)).isEqualTo(0)
        assertThat(spreadStart(1)).isEqualTo(0)
        assertThat(spreadStart(2)).isEqualTo(2)
        assertThat(spreadStart(7)).isEqualTo(6)
    }

    @Test
    fun `advancing moves two pages within the chapter`() {
        val next = advanceSpread(nav, ReadingState(0, 0), pageCounts)
        assertThat(next).isEqualTo(ReadingState(0, 2))
    }

    @Test
    fun `advancing from an odd page still lands on the next spread`() {
        // A page index can arrive odd from a bookmark or a TOC anchor. The spread containing page 3
        // is 2-3, so the next spread is 4-5 — NOT 5, which would pair pages 5 and 6 from then on.
        val next = advanceSpread(nav, ReadingState(0, 3), pageCounts)
        assertThat(next).isEqualTo(ReadingState(0, 4))
    }

    @Test
    fun `the last spread of an odd chapter is a lone left page, and advancing leaves the chapter`() {
        // Chapter 0's 5 pages make spreads 0-1, 2-3 and 4-(blank). Advancing from the last one must
        // cross into the next chapter with pages rather than to a sixth page that does not exist.
        val next = advanceSpread(nav, ReadingState(0, 4), pageCounts)
        assertThat(next).isEqualTo(ReadingState(2, 0)) // chapter 1 is empty and is skipped
    }

    @Test
    fun `advancing off the end of the book reports nowhere to go`() {
        assertThat(advanceSpread(nav, ReadingState(2, 2), pageCounts)).isNull()
    }

    @Test
    fun `retreating moves two pages back within the chapter`() {
        assertThat(retreatSpread(nav, ReadingState(0, 4), pageCounts)).isEqualTo(ReadingState(0, 2))
    }

    @Test
    fun `retreating out of a chapter lands on the previous chapter's last SPREAD, not its last page`() {
        // Chapter 2 page 0 back into chapter 0, whose last page is 4. Landing on page 4 is already
        // the spread start here; the alignment matters because retreat() returns pageCount - 1,
        // which for an even-length chapter is odd.
        assertThat(retreatSpread(nav, ReadingState(2, 0), pageCounts)).isEqualTo(ReadingState(0, 4))
    }

    @Test
    fun `retreating into an even-length chapter aligns its odd last page down to the spread start`() {
        val counts: (Int) -> Int = { listOf(4, 4, 4)[it] } // last page 3, an odd index
        assertThat(retreatSpread(nav, ReadingState(1, 0), counts)).isEqualTo(ReadingState(0, 2))
    }

    @Test
    fun `retreating from the start of the book reports nowhere to go`() {
        assertThat(retreatSpread(nav, ReadingState(0, 0), pageCounts)).isNull()
    }

    @Test
    fun `a full forward-then-backward pass visits the same spreads in reverse`() {
        // The property that matters in the hand: flipping forward through the book and back again
        // must pair the same pages both ways, never landing on a spread that forward never showed.
        val forward = generateSequence(ReadingState(0, 0)) { advanceSpread(nav, it, pageCounts) }.toList()
        val backward = generateSequence(forward.last()) { retreatSpread(nav, it, pageCounts) }.toList()
        assertThat(backward).isEqualTo(forward.reversed())
    }
}
