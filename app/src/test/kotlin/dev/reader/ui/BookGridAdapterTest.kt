package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookGridAdapterTest {

    // -- progressLabel --------------------------------------------------------------------

    @Test
    fun `a never-opened book has no progress label`() {
        assertThat(progressLabel(lastOpenedAtMs = null, spineIndex = 5, charOffset = 200)).isNull()
    }

    @Test
    fun `an opened book at the very start reads Just started`() {
        assertThat(progressLabel(lastOpenedAtMs = 1_000L, spineIndex = 0, charOffset = 0))
            .isEqualTo("Just started")
    }

    @Test
    fun `an opened book past the start names its spine section, one-based, not a chapter`() {
        // spineIndex is a SPINE index, not a chapter ordinal: real EPUBs routinely carry
        // cover/title/nav as spine items 0-2 (ReaderActivity skips a zero-page spine item 0 for
        // exactly this reason), so "Chapter 5" here could actually be the book's chapter 2.
        // "Section N" reports only what the index actually has, honestly.
        assertThat(progressLabel(lastOpenedAtMs = 1_000L, spineIndex = 4, charOffset = 0))
            .isEqualTo("Section 5")
    }

    @Test
    fun `a nonzero charOffset counts as progress even at spineIndex zero`() {
        // spineIndex 0 with a nonzero charOffset means partway through the first chapter,
        // which is progress, not "just started" — only (0, 0) exactly is the true start.
        assertThat(progressLabel(lastOpenedAtMs = 1_000L, spineIndex = 0, charOffset = 50))
            .isEqualTo("Section 1")
    }

    // -- coverCacheKey ----------------------------------------------------------------------

    @Test
    fun `the same path and mtime produce the same cache key`() {
        val a = coverCacheKey(coverPath = "/data/covers/a.png", modifiedAtMs = 42L)
        val b = coverCacheKey(coverPath = "/data/covers/a.png", modifiedAtMs = 42L)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `a changed mtime produces a different cache key for the same path`() {
        val a = coverCacheKey(coverPath = "/data/covers/a.png", modifiedAtMs = 42L)
        val b = coverCacheKey(coverPath = "/data/covers/a.png", modifiedAtMs = 43L)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `different paths produce different cache keys even with the same mtime`() {
        val a = coverCacheKey(coverPath = "/data/covers/a.png", modifiedAtMs = 42L)
        val b = coverCacheKey(coverPath = "/data/covers/b.png", modifiedAtMs = 42L)
        assertThat(a).isNotEqualTo(b)
    }
}
