package dev.reader.library

import dev.reader.data.BookMetadataResult
import dev.reader.data.MetadataExtractor
import java.io.File

/**
 * Which reader a book opens in. Decided by extension — the container's own bytes decide
 * zip-vs-RAR, and epub-vs-mobi, later.
 *
 * EPUB and MOBI share [BookFormat.TEXT] deliberately: they are both reflowable prose and both
 * open in the same reader, with the same chrome, marks and typography. Which of the two a file
 * turns out to be is `ReflowableDocuments`' business, and nothing in the app above it needs to
 * know — the whole point of the format being transparent to the reader.
 */
enum class BookFormat { TEXT, COMIC }

fun bookFormatOf(path: String): BookFormat? = when (path.substringAfterLast('.', "").lowercase()) {
    "epub", "mobi", "azw", "prc" -> BookFormat.TEXT
    "cbz", "cbr" -> BookFormat.COMIC
    else -> null
}

/**
 * Whether a path is one of the MOBI family. Only the *indexer* needs this distinction, to pick
 * which parser reads the metadata; the reader itself never asks, because both formats open in it
 * the same way.
 */
internal fun isMobiPath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in setOf("mobi", "azw", "prc")

/** Routes each file to the format-specific extractor. An unrecognised extension is treated as
 *  EPUB's problem to reject — the indexer's walk filter already excludes non-book files, so this
 *  is only ever called with a book file. */
class DispatchingMetadataExtractor(
    private val epub: MetadataExtractor,
    private val mobi: MetadataExtractor,
    private val comic: MetadataExtractor,
) : MetadataExtractor {
    override fun extract(file: File): BookMetadataResult = when {
        bookFormatOf(file.path) == BookFormat.COMIC -> comic.extract(file)
        isMobiPath(file.path) -> mobi.extract(file)
        else -> epub.extract(file)
    }
}
