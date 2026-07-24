package dev.reader.library

import android.content.Context
import dev.reader.data.BookMetadataResult
import dev.reader.data.MetadataExtractor
import dev.reader.formats.ZipResourceSource
import dev.reader.formats.comic.ComicCoverExtractor
import dev.reader.formats.comic.ComicDocument
import dev.reader.formats.comic.ComicException
import java.io.File

/** Comic counterpart to [EpubMetadataExtractor]. Malformed/unsupported comics become Failure. */
class ComicMetadataExtractor(private val context: Context) : MetadataExtractor {

    private val coverExtractor = ComicCoverExtractor()

    override fun extract(file: File): BookMetadataResult {
        val doc = try {
            ComicDocument.open(file)
        } catch (e: ComicException) {
            return BookMetadataResult.Failure(e.message ?: e.javaClass.simpleName)
        }
        return doc.use {
            BookMetadataResult.Success(
                title = doc.metadata.title,
                author = doc.metadata.author,
                coverPath = extractCover(file, doc.metadata),
            )
        }
    }

    private fun extractCover(file: File, metadata: dev.reader.engine.BookMetadata): String? {
        val destination = File(context.filesDir, coverFileName(file.path))
        return try {
            ZipResourceSource(file).use { source ->
                coverExtractor.extract(source, metadata, destination)
            }
            destination.path
        } catch (e: Exception) {
            null
        }
    }
}
