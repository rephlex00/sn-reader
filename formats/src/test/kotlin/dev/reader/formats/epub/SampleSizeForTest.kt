package dev.reader.formats.epub

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Direct, table-driven tests for [sampleSizeFor] — the single most-emphasized invariant in
 * [EpubCoverExtractor] ("never decode a full-resolution cover") is entirely enforced by this
 * one function's return value. Without a test on this function itself, a full-resolution
 * decode followed by [scaleToFit] produces byte-identical output to a correctly-sampled one
 * and would pass [EpubCoverExtractorTest] identically — the "oversized cover" test there only
 * ever asserted the *output* bounds, never that a downsample actually happened. `internal`
 * visibility (not `private`) exists specifically so this function can be exercised directly,
 * without going through Robolectric's Skia shadow at all.
 */
class SampleSizeForTest {

    private data class Case(
        val width: Int,
        val height: Int,
        val reqWidth: Int,
        val reqHeight: Int,
        val expected: Int,
    )

    @Test
    fun `matches known-good values for representative inputs`() {
        val cases = listOf(
            Case(width = 3000, height = 4500, reqWidth = 100, reqHeight = 150, expected = 16),
            Case(width = 800, height = 1200, reqWidth = 100, reqHeight = 150, expected = 8),
            Case(width = 12000, height = 12000, reqWidth = 240, reqHeight = 360, expected = 32),
            // A source already smaller than the target must never be "downsampled" below 1.
            Case(width = 50, height = 50, reqWidth = 240, reqHeight = 360, expected = 1),
        )

        for (case in cases) {
            val actual = sampleSizeFor(case.width, case.height, case.reqWidth, case.reqHeight)
            assertThat(actual).isEqualTo(case.expected)
        }
    }

    @Test
    fun `never returns less than 1`() {
        val cases = listOf(
            Case(width = 1, height = 1, reqWidth = 240, reqHeight = 360, expected = 1),
            Case(width = 50, height = 50, reqWidth = 240, reqHeight = 360, expected = 1),
            Case(width = 240, height = 360, reqWidth = 240, reqHeight = 360, expected = 1),
        )

        for (case in cases) {
            assertThat(sampleSizeFor(case.width, case.height, case.reqWidth, case.reqHeight)).isAtLeast(1)
        }
    }

    @Test
    fun `result is always a power of two`() {
        val inputs = listOf(
            intArrayOf(10, 10, 100, 150),
            intArrayOf(3000, 4500, 100, 150),
            intArrayOf(800, 1200, 100, 150),
            intArrayOf(12000, 12000, 240, 360),
            intArrayOf(30000, 200, 240, 360),
            intArrayOf(200, 30000, 240, 360),
            intArrayOf(1, 1, 1, 1),
        )

        for ((width, height, reqWidth, reqHeight) in inputs.map { it.toList() }) {
            val result = sampleSizeFor(width, height, reqWidth, reqHeight)
            assertThat(result and (result - 1)).isEqualTo(0)
        }
    }

    /**
     * The regression this whole file exists to close: `sampleSizeFor`'s inner `while` loop
     * used `&&` to require *both* dimensions to still be oversized before doubling again.
     * For a hostile/degenerate aspect ratio where one dimension is already tiny and the
     * other is enormous (e.g. a 30000x200 cover against a 240x360 cell), the `&&` loop never
     * runs at all — the tiny dimension already fails its half of the test — so the huge
     * dimension goes completely unsampled and a full-resolution decode of that axis happens
     * anyway (measured at 22.9 MB ARGB for this exact input). `||` bounds *either* dimension
     * being oversized as a reason to keep sampling, closing that gap; `scaleToFit` already
     * handles the resulting overshoot in the other axis, same as it always has.
     */
    @Test
    fun `an extremely wide cover is still sampled down, not decoded at full width`() {
        val sampleSize = sampleSizeFor(width = 30000, height = 200, reqWidth = 240, reqHeight = 360)

        val decodedWidth = 30000 / sampleSize
        val decodedHeight = 200 / sampleSize
        val decodedArgbBytes = decodedWidth.toLong() * decodedHeight.toLong() * 4

        // Comfortably under the 22.9 MB the reviewer measured pre-fix, and consistent with
        // the sampled dimensions actually landing in the neighborhood of the target cell.
        assertThat(decodedArgbBytes).isLessThan(1_000_000L)
    }

    @Test
    fun `an extremely tall cover is still sampled down, not decoded at full height`() {
        val sampleSize = sampleSizeFor(width = 200, height = 30000, reqWidth = 240, reqHeight = 360)

        val decodedWidth = 200 / sampleSize
        val decodedHeight = 30000 / sampleSize
        val decodedArgbBytes = decodedWidth.toLong() * decodedHeight.toLong() * 4

        assertThat(decodedArgbBytes).isLessThan(1_000_000L)
    }
}
