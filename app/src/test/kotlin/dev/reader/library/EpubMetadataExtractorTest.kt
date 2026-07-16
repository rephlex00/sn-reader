package dev.reader.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpubMetadataExtractorTest {

    @Test
    fun `the same book path always produces the same cover file name`() {
        val a = coverFileName("/storage/emulated/0/Document/Dune.epub")
        val b = coverFileName("/storage/emulated/0/Document/Dune.epub")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `different book paths produce different cover file names`() {
        val a = coverFileName("/storage/emulated/0/Document/Dune.epub")
        val b = coverFileName("/storage/emulated/0/Document/Hyperion.epub")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `the cover file name is a filesystem-safe png filename`() {
        // The book path itself contains '/', which is not a valid filename component —
        // the whole point of hashing rather than reusing the path directly.
        val name = coverFileName("/storage/emulated/0/Document/Some Book (2024).epub")
        assertThat(name).endsWith(".png")
        assertThat(name).doesNotContain("/")
        assertThat(name).doesNotContain(" ")
    }
}
