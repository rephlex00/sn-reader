package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure geometry — no Robolectric needed, the view only calls this. */
class LeaderDotsViewTest {

    @Test
    fun `counts the dots that fit at the given spacing`() {
        assertThat(dotCountFor(100f, 10f)).isEqualTo(10)
        assertThat(dotCountFor(95f, 10f)).isEqualTo(9)
    }

    @Test
    fun `a width too small for one dot draws none`() {
        assertThat(dotCountFor(5f, 10f)).isEqualTo(0)
        assertThat(dotCountFor(0f, 10f)).isEqualTo(0)
    }

    @Test
    fun `degenerate inputs never loop forever or go negative`() {
        assertThat(dotCountFor(-50f, 10f)).isEqualTo(0)
        assertThat(dotCountFor(100f, 0f)).isEqualTo(0)
        assertThat(dotCountFor(100f, -4f)).isEqualTo(0)
    }
}
