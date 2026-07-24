package dev.reader.library

import dev.reader.data.BookMetadataResult
import dev.reader.data.MetadataExtractor
import java.io.File

/** Which reader a book opens in. Decided by extension — the container's own bytes decide zip-vs-RAR later. */
enum class BookFormat { EPUB, COMIC }

fun bookFormatOf(path: String): BookFormat? = when (path.substringAfterLast('.', "").lowercase()) {
    "epub" -> BookFormat.EPUB
    "cbz", "cbr" -> BookFormat.COMIC
    else -> null
}

/** Routes each file to the format-specific extractor. A non-book extension is treated as EPUB's
 *  problem to reject — the indexer's walk filter already excludes non-book files, so this is only
 *  ever called with a book file. */
class DispatchingMetadataExtractor(
    private val epub: MetadataExtractor,
    private val comic: MetadataExtractor,
) : MetadataExtractor {
    override fun extract(file: File): BookMetadataResult =
        when (bookFormatOf(file.path)) {
            BookFormat.COMIC -> comic.extract(file)
            else -> epub.extract(file)
        }
}
