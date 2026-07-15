package dev.reader.formats

import dev.reader.engine.BookMetadata
import dev.reader.engine.TocEntry
import java.io.Closeable

/**
 * What every format offers. Deliberately narrow: rendering differs enough between text
 * and image formats that a shared render method would be a fiction until a second
 * format exists to constrain it.
 */
interface Document : Closeable {
    val metadata: BookMetadata
    val toc: List<TocEntry>

    /** Chapters for EPUB; pages for paged formats. */
    val spineSize: Int
}
