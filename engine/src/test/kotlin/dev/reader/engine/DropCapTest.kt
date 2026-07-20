package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DropCapTest {

    @Test
    fun `a leading letter gets a one-char drop cap`() {
        assertThat(dropCapLength("Hello there")).isEqualTo(1)
    }

    @Test
    fun `a leading quote gets no drop cap`() {
        assertThat(dropCapLength("“Hello”")).isEqualTo(0)
    }

    @Test
    fun `a leading digit gets no drop cap`() {
        assertThat(dropCapLength("1984 was")).isEqualTo(0)
    }

    @Test
    fun `empty gets no drop cap`() {
        assertThat(dropCapLength("")).isEqualTo(0)
    }

    @Test
    fun `a leading space gets no drop cap`() {
        assertThat(dropCapLength(" Hello")).isEqualTo(0)
    }

    @Test
    fun `a leading accented letter still caps`() {
        assertThat(dropCapLength("Éric ran")).isEqualTo(1)
    }
}
