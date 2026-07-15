package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionTest {
    @Test
    fun `engine reports its version`() {
        assertThat(ENGINE_VERSION).isEqualTo("0.1.0")
    }
}
