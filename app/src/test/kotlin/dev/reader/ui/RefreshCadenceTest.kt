package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RefreshCadenceTest {
    @Test
    fun `with faster off every turn is a full refresh regardless of N`() {
        assertThat(shouldFullRefresh(faster = false, everyN = 6, turnsSinceRefresh = 1)).isTrue()
        assertThat(shouldFullRefresh(faster = false, everyN = 10, turnsSinceRefresh = 1)).isTrue()
    }

    @Test
    fun `with faster on a full refresh lands exactly on the Nth turn`() {
        assertThat(shouldFullRefresh(faster = true, everyN = 6, turnsSinceRefresh = 5)).isFalse()
        assertThat(shouldFullRefresh(faster = true, everyN = 6, turnsSinceRefresh = 6)).isTrue()
        assertThat(shouldFullRefresh(faster = true, everyN = 6, turnsSinceRefresh = 7)).isTrue()
    }

    @Test
    fun `a degenerate N of zero is treated as every turn, never a divide-by-nothing stall`() {
        assertThat(shouldFullRefresh(faster = true, everyN = 0, turnsSinceRefresh = 1)).isTrue()
    }
}
