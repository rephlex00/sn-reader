package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.data.BookEntity
import org.junit.Test

class BookGridAdapterTest {

    // -- formatAuthor ---------------------------------------------------------------------

    @Test
    fun `a dangling separator is stripped from the byline`() {
        // The reported case: a creator list with an empty second entry leaves "Andy Weir;".
        assertThat(formatAuthor("Andy Weir;")).isEqualTo("Andy Weir")
        assertThat(formatAuthor("Andy Weir; ")).isEqualTo("Andy Weir")
        assertThat(formatAuthor("  Caroline Criado Perez ,")).isEqualTo("Caroline Criado Perez")
    }

    @Test
    fun `internal punctuation is preserved`() {
        assertThat(formatAuthor("Weir, Andy")).isEqualTo("Weir, Andy")
        assertThat(formatAuthor("Martha Wells")).isEqualTo("Martha Wells")
    }

    @Test
    fun `null or all-separator input yields empty`() {
        assertThat(formatAuthor(null)).isEmpty()
        assertThat(formatAuthor(" ; , ")).isEmpty()
    }

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

    // -- humanReadableSize ------------------------------------------------------------------

    @Test
    fun `a size under one kibibyte is shown in bytes`() {
        assertThat(humanReadableSize(0L)).isEqualTo("0 B")
        assertThat(humanReadableSize(1023L)).isEqualTo("1023 B")
    }

    @Test
    fun `exactly one kibibyte rolls over to one decimal KB`() {
        assertThat(humanReadableSize(1024L)).isEqualTo("1.0 KB")
    }

    @Test
    fun `sizes step up through KB MB GB with one decimal place`() {
        assertThat(humanReadableSize(1536L)).isEqualTo("1.5 KB")
        assertThat(humanReadableSize(1024L * 1024)).isEqualTo("1.0 MB")
        assertThat(humanReadableSize(5L * 1024 * 1024 + 512 * 1024)).isEqualTo("5.5 MB")
        assertThat(humanReadableSize(3L * 1024 * 1024 * 1024)).isEqualTo("3.0 GB")
    }

    // -- statusText -------------------------------------------------------------------------

    @Test
    fun `a never-opened readable book reads Not started`() {
        assertThat(statusText(book(lastOpenedAtMs = null))).isEqualTo("Not started")
    }

    @Test
    fun `an opened book reads In progress`() {
        assertThat(statusText(book(lastOpenedAtMs = 5L))).isEqualTo("In progress")
    }

    @Test
    fun `an unreadable book spells out its stored reason`() {
        assertThat(statusText(book(unreadable = true, unreadableReason = "corrupt zip")))
            .isEqualTo("Unreadable: corrupt zip")
    }

    @Test
    fun `an unreadable book with no stored reason falls back rather than printing null`() {
        assertThat(statusText(book(unreadable = true, unreadableReason = null)))
            .isEqualTo("Unreadable: unknown reason")
    }

    private fun book(
        lastOpenedAtMs: Long? = null,
        unreadable: Boolean = false,
        unreadableReason: String? = null,
    ) = BookEntity(
        path = "/Document/a.epub",
        sizeBytes = 1_000L,
        modifiedAtMs = 1_700_000_000_000L,
        title = "A Book",
        author = null,
        coverPath = null,
        spineIndex = 0,
        charOffset = 0,
        unreadable = unreadable,
        unreadableReason = unreadableReason,
        addedAtMs = 1_700_000_000_000L,
        lastOpenedAtMs = lastOpenedAtMs,
    )
}
