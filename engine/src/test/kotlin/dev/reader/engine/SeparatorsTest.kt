package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeparatorsTest {

    @Test
    fun `asterisks are a separator line`() {
        assertThat(isSeparatorLine("***")).isTrue()
    }

    @Test
    fun `spaced asterisks are a separator line`() {
        assertThat(isSeparatorLine("* * *")).isTrue()
    }

    @Test
    fun `spaced dashes are a separator line`() {
        assertThat(isSeparatorLine("- - -")).isTrue()
    }

    @Test
    fun `middle dots are a separator line`() {
        assertThat(isSeparatorLine("···")).isTrue()
    }

    @Test
    fun `a lone em dash is a separator line`() {
        assertThat(isSeparatorLine("—")).isTrue()
    }

    @Test
    fun `a word is not a separator line`() {
        assertThat(isSeparatorLine("Hello")).isFalse()
    }

    @Test
    fun `empty string is not a separator line`() {
        assertThat(isSeparatorLine("")).isFalse()
    }

    @Test
    fun `whitespace only is not a separator line`() {
        assertThat(isSeparatorLine("   ")).isFalse()
    }

    @Test
    fun `a heading-like phrase is not a separator line`() {
        assertThat(isSeparatorLine("Chapter 1")).isFalse()
    }

    @Test
    fun `a digit with punctuation is not a separator line`() {
        assertThat(isSeparatorLine("1.")).isFalse()
    }

    @Test
    fun `a letter with punctuation is not a separator line`() {
        assertThat(isSeparatorLine("A.")).isFalse()
    }
}
