package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookProgressTest {

    @Test
    fun `an empty or weightless book reads zero, never divides by zero`() {
        assertThat(bookProgress(emptyList(), 0, 0, 0)).isEqualTo(0f)
        assertThat(bookProgress(listOf(0L, 0L), 1, 3, 10)).isEqualTo(0f)
        // Genuinely negative-summing weights also read zero without division error.
        assertThat(bookProgress(listOf(-3L, -5L), 0, 0, 10)).isEqualTo(0f)
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

    @Test
    fun `turning forward never decreases progress — monotonic across all pages and chapters`() {
        // Walk a realistic multi-chapter book with unequal weights (exercising
        // byte-weighting), stepping through every page of every chapter, and collect
        // progress fractions. Assert the sequence is non-decreasing, stays within [0,1],
        // and reaches 1f at the end. This verifies the contract "Turning forward never
        // decreases it" and ensures byte-weighting is actually used across chapters.
        val weights = listOf(2L, 5L, 3L)  // Unequal weights: 2+5+3 = 10 total
        val pageCountsPerChapter = listOf(4, 6, 5)  // Different chapter lengths

        val progressSequence = mutableListOf<Float>()
        for (spineIdx in weights.indices) {
            val pageCount = pageCountsPerChapter[spineIdx]
            for (pageIdx in 0..pageCount) {  // Walk every page including the boundary
                val p = bookProgress(weights, spineIdx, pageIdx, pageCount)
                progressSequence.add(p)
            }
        }

        // Assert no step decreases progress.
        progressSequence.zipWithNext().forEach { (prev, next) ->
            assertThat(next).isAtLeast(prev)
        }

        // Assert all values stay in bounds.
        progressSequence.forEach { p ->
            assertThat(p).isAtLeast(0f)
            assertThat(p).isAtMost(1f)
        }

        // Assert we reach completion at the final page.
        assertThat(progressSequence.last()).isEqualTo(1f)
    }

    @Test
    fun `chapter end fraction is the cumulative weight through the chapter`() {
        val weights = listOf(10L, 30L, 60L)
        assertThat(chapterEndFraction(weights, 0)).isWithin(1e-6f).of(0.1f)
        assertThat(chapterEndFraction(weights, 1)).isWithin(1e-6f).of(0.4f)
        assertThat(chapterEndFraction(weights, 2)).isWithin(1e-6f).of(1.0f)
    }

    @Test
    fun `chapter end fraction survives degenerate weights`() {
        assertThat(chapterEndFraction(emptyList(), 0)).isEqualTo(0f)
        assertThat(chapterEndFraction(listOf(0L, 0L), 1)).isEqualTo(0f)
        assertThat(chapterEndFraction(listOf(-5L, 10L), 1)).isWithin(1e-6f).of(1.0f)
    }

    @Test
    fun `chapter end fraction clamps an out of range spine index`() {
        val weights = listOf(10L, 30L)
        assertThat(chapterEndFraction(weights, 99)).isWithin(1e-6f).of(1.0f)
        assertThat(chapterEndFraction(weights, -3)).isWithin(1e-6f).of(0.25f)
    }
}
