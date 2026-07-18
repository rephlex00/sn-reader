package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HighlightsTest {

    // "The quick brown fox"  indices: The=0..3 quick=4..9 brown=10..15 fox=16..19
    private val text = "The quick brown fox"

    @Test
    fun `snapToWords expands a mid-word start left and a mid-word end right`() {
        // start inside "quick", end inside "brown"
        assertThat(snapToWords(text, 6, 12)).isEqualTo(HighlightRange(4, 15))
    }

    @Test
    fun `snapToWords leaves an already-word-aligned range unchanged`() {
        assertThat(snapToWords(text, 4, 9)).isEqualTo(HighlightRange(4, 9))
    }

    @Test
    fun `snapToWords on a single point returns the enclosing word`() {
        assertThat(snapToWords(text, 12, 12)).isEqualTo(HighlightRange(10, 15))
    }

    @Test
    fun `snapToWords over only whitespace returns null`() {
        assertThat(snapToWords("a   b", 1, 2)).isNull() // the run of spaces between a and b
    }

    @Test
    fun `snapToWords on empty text returns null`() {
        assertThat(snapToWords("", 0, 0)).isNull()
    }

    @Test
    fun `snapToWords on a point at a word's trailing boundary returns that word`() {
        assertThat(snapToWords(text, 9, 9)).isEqualTo(HighlightRange(4, 9))
    }

    @Test
    fun `snapToWords on a point at end of text returns the last word`() {
        assertThat(snapToWords("Hi", 2, 2)).isEqualTo(HighlightRange(0, 2))
    }

    @Test
    fun `mergeHighlights keeps a disjoint highlight separate`() {
        val existing = listOf(ExistingHighlight(1, 0, 3))
        val result = mergeHighlights(existing, HighlightRange(10, 15))
        assertThat(result.merged).isEqualTo(HighlightRange(10, 15))
        assertThat(result.removedIds).isEmpty()
    }

    @Test
    fun `mergeHighlights unions an overlapping highlight`() {
        val existing = listOf(ExistingHighlight(7, 10, 20))
        val result = mergeHighlights(existing, HighlightRange(15, 30))
        assertThat(result.merged).isEqualTo(HighlightRange(10, 30))
        assertThat(result.removedIds).containsExactly(7L)
    }

    @Test
    fun `mergeHighlights unions an abutting highlight`() {
        val existing = listOf(ExistingHighlight(7, 10, 20))
        val result = mergeHighlights(existing, HighlightRange(20, 30))
        assertThat(result.merged).isEqualTo(HighlightRange(10, 30))
        assertThat(result.removedIds).containsExactly(7L)
    }

    @Test
    fun `mergeHighlights transitively swallows a chain the widened range now touches`() {
        // new [15,25] overlaps B[20,30]; widening to [15,30] now abuts C[30,40].
        val existing = listOf(ExistingHighlight(1, 0, 5), ExistingHighlight(2, 20, 30), ExistingHighlight(3, 30, 40))
        val result = mergeHighlights(existing, HighlightRange(15, 25))
        assertThat(result.merged).isEqualTo(HighlightRange(15, 40))
        assertThat(result.removedIds).containsExactly(2L, 3L)
    }

    @Test
    fun `highlightContaining finds the highlight whose half-open range covers the offset`() {
        val list = listOf(ExistingHighlight(1, 10, 20), ExistingHighlight(2, 30, 40))
        assertThat(highlightContaining(list, 15)?.id).isEqualTo(1)
        assertThat(highlightContaining(list, 20)).isNull() // end is exclusive
        assertThat(highlightContaining(list, 25)).isNull()
    }

    @Test
    fun `highlightExcerpt collapses whitespace and ellipsizes past the cap`() {
        assertThat(highlightExcerpt("  hello   world  ")).isEqualTo("hello world")
        assertThat(highlightExcerpt("abcdefghij", maxChars = 5)).isEqualTo("abcd…")
    }
}
