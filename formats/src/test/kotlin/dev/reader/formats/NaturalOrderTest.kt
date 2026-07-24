package dev.reader.formats

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaturalOrderTest {
    @Test fun `page9 sorts before page10`() {
        val sorted = listOf("page10.jpg", "page9.jpg", "page1.jpg").sortedWith(NATURAL_ORDER)
        assertThat(sorted).containsExactly("page1.jpg", "page9.jpg", "page10.jpg").inOrder()
    }

    @Test fun `leading zeros do not change numeric order`() {
        val sorted = listOf("p008", "p8", "p10").sortedWith(NATURAL_ORDER)
        assertThat(sorted.last()).isEqualTo("p10")
    }

    @Test fun `nested paths sort segment by segment`() {
        val sorted = listOf("ch2/p1.jpg", "ch10/p1.jpg", "ch1/p2.jpg").sortedWith(NATURAL_ORDER)
        assertThat(sorted).containsExactly("ch1/p2.jpg", "ch2/p1.jpg", "ch10/p1.jpg").inOrder()
    }

    @Test fun `comparison is case-insensitive`() {
        assertThat(NATURAL_ORDER.compare("Page1", "page1")).isEqualTo(0)
    }
}
