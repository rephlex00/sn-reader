package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure coverage for the chooser's natural folder-name ordering — no device needed. */
class DirectoryChooserTest {

    @Test
    fun `numbers sort by value, not lexically`() {
        val sorted = listOf("page10", "page2", "page1").sortedWith(NATURAL_NAME_ORDER)
        assertThat(sorted).containsExactly("page1", "page2", "page10").inOrder()
    }

    @Test
    fun `ordering is case-insensitive`() {
        val sorted = listOf("Zeta", "alpha", "Beta").sortedWith(NATURAL_NAME_ORDER)
        assertThat(sorted).containsExactly("alpha", "Beta", "Zeta").inOrder()
    }

    @Test
    fun `leading zeros do not change numeric order`() {
        val sorted = listOf("v009", "v10", "v08").sortedWith(NATURAL_NAME_ORDER)
        assertThat(sorted).containsExactly("v08", "v009", "v10").inOrder()
    }

    @Test
    fun `a plain prefix sorts before the same prefix with more characters`() {
        val sorted = listOf("Bookshelf", "Book").sortedWith(NATURAL_NAME_ORDER)
        assertThat(sorted).containsExactly("Book", "Bookshelf").inOrder()
    }
}
