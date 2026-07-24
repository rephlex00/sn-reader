package dev.reader.library

import com.google.common.truth.Truth.assertThat
import dev.reader.data.BookMetadataResult
import dev.reader.data.MetadataExtractor
import java.io.File
import org.junit.Test

class FormatDispatchTest {
    @Test fun `extension maps to format, case-insensitively`() {
        assertThat(bookFormatOf("/a/b.EPUB")).isEqualTo(BookFormat.EPUB)
        assertThat(bookFormatOf("/a/b.cbz")).isEqualTo(BookFormat.COMIC)
        assertThat(bookFormatOf("/a/b.cbr")).isEqualTo(BookFormat.COMIC)
        assertThat(bookFormatOf("/a/b.txt")).isNull()
    }

    @Test fun `dispatcher routes by file extension`() {
        val calls = mutableListOf<String>()
        val epub = MetadataExtractor { calls += "epub:${it.name}"; BookMetadataResult.Failure("x") }
        val comic = MetadataExtractor { calls += "comic:${it.name}"; BookMetadataResult.Failure("x") }
        val d = DispatchingMetadataExtractor(epub, comic)
        d.extract(File("/x/a.epub"))
        d.extract(File("/x/b.cbz"))
        d.extract(File("/x/c.cbr"))
        assertThat(calls).containsExactly("epub:a.epub", "comic:b.cbz", "comic:c.cbr").inOrder()
    }
}
