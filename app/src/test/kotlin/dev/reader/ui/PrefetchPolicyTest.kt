package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.engine.ReadingState
import org.junit.Test

/**
 * Exhaustive coverage of the pure prefetch policy: which neighbour to paginate ahead, or null.
 * No Android, no coroutines — just the boundary logic the wiring in [ReaderActivity] leans on.
 */
class PrefetchPolicyTest {

    // -- Last page → next chapter -------------------------------------------------------------

    @Test
    fun `on the last page with a next chapter, prefetch the next chapter`() {
        // Chapter 1 of 4, sitting on its last page (index 3 of 4): the next turn crosses to chapter 2.
        assertThat(chapterToPrefetch(ReadingState(1, 3), chapterPageCount = 4, spineSize = 4))
            .isEqualTo(2)
    }

    @Test
    fun `on the last page of the last chapter, prefetch nothing`() {
        // Chapter 3 is the final spine entry: there is no next chapter to prefetch.
        assertThat(chapterToPrefetch(ReadingState(3, 3), chapterPageCount = 4, spineSize = 4))
            .isNull()
    }

    // -- First page → previous chapter --------------------------------------------------------

    @Test
    fun `on the first page with a previous chapter, prefetch the previous chapter`() {
        // Chapter 2, first page: a backward turn crosses to chapter 1.
        assertThat(chapterToPrefetch(ReadingState(2, 0), chapterPageCount = 4, spineSize = 4))
            .isEqualTo(1)
    }

    @Test
    fun `on the first page of the first chapter, prefetch nothing`() {
        // Chapter 0, first page: no previous chapter exists.
        assertThat(chapterToPrefetch(ReadingState(0, 0), chapterPageCount = 4, spineSize = 4))
            .isNull()
    }

    // -- Interior → nothing (its pages are already paginated) ---------------------------------

    @Test
    fun `mid-chapter, prefetch nothing`() {
        // Page 2 of 5 in chapter 1: neither boundary, so both turn targets stay in this chapter.
        assertThat(chapterToPrefetch(ReadingState(1, 2), chapterPageCount = 5, spineSize = 4))
            .isNull()
    }

    // -- Single-page chapter: first page == last page, forward bias ---------------------------

    @Test
    fun `a single-page chapter mid-book prefetches the next chapter (forward bias)`() {
        // Page 0 is both first and last; forward reading is likelier, so next wins.
        assertThat(chapterToPrefetch(ReadingState(1, 0), chapterPageCount = 1, spineSize = 4))
            .isEqualTo(2)
    }

    @Test
    fun `a single-page last chapter falls back to the previous chapter`() {
        // No next chapter, so the single page's other boundary — previous — is prefetched instead.
        assertThat(chapterToPrefetch(ReadingState(3, 0), chapterPageCount = 1, spineSize = 4))
            .isEqualTo(2)
    }

    @Test
    fun `a single-page only chapter in the book prefetches nothing`() {
        // The whole book is one single-page chapter: no neighbour on either side.
        assertThat(chapterToPrefetch(ReadingState(0, 0), chapterPageCount = 1, spineSize = 1))
            .isNull()
    }

    // -- Degenerate inputs --------------------------------------------------------------------

    @Test
    fun `an empty chapter prefetches nothing`() {
        // Zero pages: "first"/"last" are meaningless, so refuse to guess.
        assertThat(chapterToPrefetch(ReadingState(1, 0), chapterPageCount = 0, spineSize = 4))
            .isNull()
    }

    @Test
    fun `a page index clamped past the last page still resolves as the last page`() {
        // A caller that over-clamps pageIndex must still be read as "on the last page" (>=), not
        // fall through to null.
        assertThat(chapterToPrefetch(ReadingState(1, 99), chapterPageCount = 4, spineSize = 4))
            .isEqualTo(2)
    }

    @Test
    fun `a two-page chapter prefetches next only on its last page`() {
        // First page (index 0) of a mid-book chapter with a previous → previous.
        assertThat(chapterToPrefetch(ReadingState(1, 0), chapterPageCount = 2, spineSize = 4))
            .isEqualTo(0)
        // Last page (index 1) → next.
        assertThat(chapterToPrefetch(ReadingState(1, 1), chapterPageCount = 2, spineSize = 4))
            .isEqualTo(2)
    }
}
