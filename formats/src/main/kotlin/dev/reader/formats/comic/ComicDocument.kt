package dev.reader.formats.comic

import dev.reader.engine.BookMetadata
import dev.reader.engine.TocEntry
import dev.reader.formats.Document
import dev.reader.formats.NATURAL_ORDER
import dev.reader.formats.ResourceSource
import dev.reader.formats.ZipResourceSource
import java.io.File
import java.io.InputStream

/**
 * A comic archive as a paged [Document]. Pages are the archive's image entries in natural order;
 * there is no manifest, so they are discovered by enumeration ([ResourceSource.entries]). The
 * container is dispatched by content, never by extension — a `.cbr` is frequently a zip. A genuine
 * RAR archive is [ComicException.RarUnsupported] in v1; junrar drops in behind a RarResourceSource
 * later with no change here.
 */
class ComicDocument private constructor(
    private val source: ResourceSource,
    private val pages: List<String>,
    private val info: ComicInfo?,
    fileNameTitle: String,
) : Document {

    override val toc: List<TocEntry> = emptyList()
    override val spineSize: Int = pages.size

    val readingDirectionRtl: Boolean = info?.rightToLeft ?: false
    val isBlackAndWhite: Boolean = info?.blackAndWhite ?: false

    override val metadata: BookMetadata = BookMetadata(
        title = displayTitle(info, fileNameTitle),
        author = info?.writer,
        language = null,
        coverHref = pages.firstOrNull(),
    )

    fun pagePath(index: Int): String = pages[index]

    fun openPage(index: Int): InputStream? = source.open(pages[index])

    override fun close() = source.close()

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        private const val COMIC_INFO_ENTRY = "ComicInfo.xml"

        /** @throws ComicException if [file] is not a readable comic. */
        fun open(file: File): ComicDocument {
            when (detectContainer(file)) {
                ContainerFormat.RAR -> throw ComicException.RarUnsupported(
                    "This comic uses the RAR format, which isn't supported yet.",
                )
                ContainerFormat.UNKNOWN -> throw ComicException.NotAComic(
                    "This file isn't a comic archive Reader can open.",
                )
                ContainerFormat.ZIP -> Unit
            }
            val source = try {
                ZipResourceSource(file)
            } catch (e: Exception) {
                throw ComicException.Malformed("The comic archive could not be read: ${e.message}")
            }
            try {
                val pages = imagePagesOf(source.entries())
                if (pages.isEmpty()) throw ComicException.NoImages("This archive contains no images.")
                // Case-insensitive, root-level only (matches the full entry path, so
                // `subdir/comicinfo.xml` is NOT found — root-level is what the ComicRack
                // layout this format follows actually specifies): `comicinfo.xml` and
                // `ComicInfo.XML` are both common in the wild, and an exact-case lookup silently
                // dropped series, writer and reading direction for every one of them.
                val infoEntry = source.entries().firstOrNull { it.equals(COMIC_INFO_ENTRY, ignoreCase = true) }
                val info = infoEntry?.let { source.readText(it) }?.let { parseComicInfo(it) }
                return ComicDocument(source, pages, info, file.nameWithoutExtension)
            } catch (e: Throwable) {
                // Every throw path above lands here, so the source is closed regardless of
                // exception type — no invisible "nothing after this throws" contract to maintain.
                source.close()
                throw when (e) {
                    is ComicException -> e
                    // Latent only — there is no suspension point in the try block above today —
                    // but rethrowing identity-preserved rather than rewrapping as
                    // ComicException.Malformed is the correct shape the moment one is added, and
                    // keeps this in lockstep with EpubDocument.open's catch.
                    is java.util.concurrent.CancellationException -> e
                    else -> ComicException.Malformed("The comic archive could not be read: ${e.message}")
                }
            }
        }

        /** Image entries only, junk removed, in natural page order. Pure — unit-tested directly. */
        internal fun imagePagesOf(entries: List<String>): List<String> =
            entries.asSequence()
                .filterNot { it.endsWith("/") }                         // directory entries
                .filterNot { it.startsWith("__MACOSX/") }               // macOS resource forks
                .filterNot { it.substringAfterLast('/').startsWith(".") } // dotfiles, .DS_Store
                .filterNot { it.substringAfterLast('/').equals("Thumbs.db", ignoreCase = true) }
                .filter { it.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                .sortedWith(NATURAL_ORDER)
                .toList()

        private fun displayTitle(info: ComicInfo?, fileNameTitle: String): String {
            val series = info?.series
            if (series != null) {
                val number = info.number
                return if (number != null) "$series #$number" else series
            }
            return info?.title ?: fileNameTitle
        }
    }
}
