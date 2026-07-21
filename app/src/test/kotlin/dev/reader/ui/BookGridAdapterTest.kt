package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.R
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
        assertThat(progressLabel(lastOpenedAtMs = null, spineIndex = 5, charOffset = 200, progressFraction = 0.4f))
            .isNull()
    }

    @Test
    fun `an opened book shows the stored whole-book percentage, rounded`() {
        assertThat(progressLabel(lastOpenedAtMs = 1_000L, spineIndex = 4, charOffset = 0, progressFraction = 0.372f))
            .isEqualTo("37%")
        assertThat(progressLabel(lastOpenedAtMs = 1_000L, spineIndex = 9, charOffset = 10, progressFraction = 1f))
            .isEqualTo("100%")
        // Out-of-range input is clamped rather than printing an impossible percentage.
        assertThat(progressLabel(lastOpenedAtMs = 1_000L, spineIndex = 1, charOffset = 1, progressFraction = 1.4f))
            .isEqualTo("100%")
    }

    @Test
    fun `an opened book at the very start shows its percentage, not a special phrase`() {
        // The label vocabulary is percent-or-nothing; position (0,0) is 0%, not "Just started".
        assertThat(progressLabel(lastOpenedAtMs = 1_000L, spineIndex = 0, charOffset = 0, progressFraction = 0f))
            .isEqualTo("0%")
    }

    @Test
    fun `a row with no stored percentage shows nothing rather than a spine fallback`() {
        // progressFraction null = a row opened before that column shipped. Nothing backfills it, so
        // the old "Section N" label sat beside real percentages forever. Show nothing instead.
        assertThat(progressLabel(lastOpenedAtMs = 1_000L, spineIndex = 4, charOffset = 0, progressFraction = null))
            .isNull()
        assertThat(progressLabel(lastOpenedAtMs = 1_000L, spineIndex = 0, charOffset = 50, progressFraction = null))
            .isNull()
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

    // -- statusTextRes ----------------------------------------------------------------------

    @Test
    fun `a never-opened readable book reads Not started`() {
        assertThat(statusTextRes(book(lastOpenedAtMs = null))).isEqualTo(R.string.status_not_started)
    }

    @Test
    fun `an opened book reads In progress`() {
        assertThat(statusTextRes(book(lastOpenedAtMs = 5L))).isEqualTo(R.string.status_in_progress)
    }

    @Test
    fun `an unreadable book reports only that it cannot be opened, never the stored reason`() {
        // The reason is a wrapped exception message; it belongs in the log, not on the shelf.
        // Both a populated and an absent reason yield the same plain-language resource.
        assertThat(statusTextRes(book(unreadable = true, unreadableReason = "corrupt zip")))
            .isEqualTo(R.string.status_unreadable)
        assertThat(statusTextRes(book(unreadable = true, unreadableReason = null)))
            .isEqualTo(R.string.status_unreadable)
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
