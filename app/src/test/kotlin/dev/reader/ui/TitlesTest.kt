package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TitlesTest {

    @Test
    fun `a filename title reads as words`() {
        assertThat(displayTitle("Codex_Seraphinius_1983")).isEqualTo("Codex Seraphinius 1983")
    }

    @Test
    fun `a double-hyphen subtitle separator becomes an em dash set open`() {
        assertThat(displayTitle("Artificial Condition--The Murderbot Diaries"))
            .isEqualTo("Artificial Condition — The Murderbot Diaries")
        // Already-spaced double hyphens don't double the air.
        assertThat(displayTitle("Artificial Condition -- The Murderbot Diaries"))
            .isEqualTo("Artificial Condition — The Murderbot Diaries")
    }

    @Test
    fun `a clean title passes through untouched`() {
        assertThat(displayTitle("Project Hail Mary")).isEqualTo("Project Hail Mary")
        assertThat(displayTitle("The Three-Body Problem")).isEqualTo("The Three-Body Problem")
    }

    @Test
    fun `whitespace collapses and ends trim`() {
        assertThat(displayTitle("  Rogue   Protocol ")).isEqualTo("Rogue Protocol")
    }

    @Test
    fun `null and blank yield empty`() {
        assertThat(displayTitle(null)).isEmpty()
        assertThat(displayTitle("   ")).isEmpty()
    }
}
