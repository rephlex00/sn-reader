package dev.reader.library

import com.google.common.truth.Truth.assertThat
import dev.reader.data.BookMetadataResult
import dev.reader.data.MetadataExtractor
import java.io.File
import org.junit.Test

class FormatDispatchTest {
    @Test fun `extension maps to format, case-insensitively`() {
        assertThat(bookFormatOf("/a/b.EPUB")).isEqualTo(BookFormat.TEXT)
        assertThat(bookFormatOf("/a/b.cbz")).isEqualTo(BookFormat.COMIC)
        assertThat(bookFormatOf("/a/b.cbr")).isEqualTo(BookFormat.COMIC)
        assertThat(bookFormatOf("/a/b.txt")).isNull()
    }

    @Test fun `a mobi is text, so it opens in the same reader an epub does`() {
        // The routing distinction the app is allowed to make is text-vs-comic. EPUB-vs-MOBI is
        // not one of its business: both are reflowable prose in the same reader.
        assertThat(bookFormatOf("/a/b.mobi")).isEqualTo(BookFormat.TEXT)
        assertThat(bookFormatOf("/a/b.AZW")).isEqualTo(BookFormat.TEXT)
        assertThat(bookFormatOf("/a/b.prc")).isEqualTo(BookFormat.TEXT)
        assertThat(bookFormatOf("/a/b.azw3")).isNull() // KF8 is not read yet, so it is not indexed
    }

    @Test fun `isMobiPath separates the two text formats for the indexer only`() {
        assertThat(isMobiPath("/x/a.mobi")).isTrue()
        assertThat(isMobiPath("/x/a.MOBI")).isTrue()
        assertThat(isMobiPath("/x/a.epub")).isFalse()
        assertThat(isMobiPath("/x/a.cbz")).isFalse()
    }

    @Test fun `dispatcher routes by file extension`() {
        val calls = mutableListOf<String>()
        val epub = MetadataExtractor { calls += "epub:${it.name}"; BookMetadataResult.Failure("x") }
        val mobi = MetadataExtractor { calls += "mobi:${it.name}"; BookMetadataResult.Failure("x") }
        val comic = MetadataExtractor { calls += "comic:${it.name}"; BookMetadataResult.Failure("x") }
        val d = DispatchingMetadataExtractor(epub, mobi, comic)
        d.extract(File("/x/a.epub"))
        d.extract(File("/x/b.cbz"))
        d.extract(File("/x/c.cbr"))
        d.extract(File("/x/d.mobi"))
        assertThat(calls)
            .containsExactly("epub:a.epub", "comic:b.cbz", "comic:c.cbr", "mobi:d.mobi")
            .inOrder()
    }
}
