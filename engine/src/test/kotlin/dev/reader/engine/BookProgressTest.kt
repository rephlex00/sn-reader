package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookProgressTest {

    @Test
    fun `an empty or weightless book reads zero, never divides by zero`() {
        assertThat(bookProgress(emptyList(), 0, 0, 0)).isEqualTo(0f)
        assertThat(bookProgress(listOf(0L, 0L), 1, 3, 10)).isEqualTo(0f)
    }

    @Test
    fun `a single chapter spans zero to one across its pages`() {
        // One chapter of any weight: fraction is purely the within-chapter page fraction.
        assertThat(bookProgress(listOf(100L), 0, 0, 4)).isEqualTo(0f)
        assertThat(bookProgress(listOf(100L), 0, 2, 4)).isEqualTo(0.5f)
        // pageIndex == pageCount (past the last page's start) clamps to a full chapter.
        assertThat(bookProgress(listOf(100L), 0, 4, 4)).isEqualTo(1f)
    }

    @Test
    fun `chapters are weighted by their byte sizes, not counted equally`() {
        // Chapter 0 weighs 1, chapter 1 weighs 3 (total 4). Standing at the very start of
        // chapter 1 means all of chapter 0 (weight 1) is behind us: 1/4 = 0.25 — NOT 0.5,
        // which equal-weighting would give.
        assertThat(bookProgress(listOf(1L, 3L), 1, 0, 8)).isEqualTo(0.25f)
        // Halfway through chapter 1: 1 + 3*0.5 = 2.5 of 4 = 0.625.
        assertThat(bookProgress(listOf(1L, 3L), 1, 4, 8)).isEqualTo(0.625f)
    }

    @Test
    fun `a zero-page chapter contributes no within-chapter overshoot`() {
        // pageCount 0 (an empty chapter navigation skips): pageFraction is 0, so we sit at
        // exactly the chapters-behind boundary rather than overshooting into it.
        assertThat(bookProgress(listOf(2L, 2L, 2L), 1, 0, 0)).isEqualTo(2f / 6f)
    }

    @Test
    fun `out-of-range indices are clamped, never crash`() {
        // Negative and past-the-end spineIndex both clamp into range.
        assertThat(bookProgress(listOf(1L, 1L), -5, 0, 4)).isEqualTo(0f)
        assertThat(bookProgress(listOf(1L, 1L), 99, 4, 4)).isEqualTo(1f)
    }

    @Test
    fun `the result never leaves the unit interval`() {
        // A pageIndex beyond pageCount and a huge weight can't push it past 1.
        val p = bookProgress(listOf(5L, 5L), 1, 999, 4)
        assertThat(p).isAtMost(1f)
        assertThat(p).isAtLeast(0f)
    }
}
