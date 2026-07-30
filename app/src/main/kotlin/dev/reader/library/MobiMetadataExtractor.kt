package dev.reader.library

import android.content.Context
import dev.reader.data.BookMetadataResult
import dev.reader.data.MetadataExtractor
import dev.reader.formats.mobi.MobiDocument
import dev.reader.formats.mobi.MobiException
import dev.reader.formats.render.AndroidTextMeasurer
import dev.reader.formats.render.SpannedChapterBuilder
import dev.reader.formats.render.TypefaceProvider
import java.io.File

/**
 * Title, author and cover for one MOBI, for the library index. The MOBI twin of
 * [EpubMetadataExtractor], and deliberately its mirror image: same contract, same failure
 * behaviour, same cover filename scheme, so a shelf of MOBIs is indistinguishable from a shelf
 * of EPUBs.
 *
 * Each call opens a fresh document local to that call and closes it before returning, so the
 * chapter cache is never reached — metadata comes from the header's EXTH block and the cover
 * from an image record, and no chapter is ever paginated here.
 */
class MobiMetadataExtractor(private val context: Context) : MetadataExtractor {

    // Required to construct a document at all, even though this extractor never paginates.
    private val measurer = AndroidTextMeasurer(SpannedChapterBuilder(), TypefaceProvider.Platform)

    /**
     * A malformed, encrypted or unsupported book returns [BookMetadataResult.Failure] carrying
     * [MobiException]'s own message — which is written to be read by a person, so an AZW3 in the
     * folder says it is an AZW3 rather than "couldn't open". Anything that is not a
     * [MobiException] propagates: that would be a bug in `:formats`, and the indexer's catch-all
     * turns it into a Failure anyway.
     */
    override fun extract(file: File): BookMetadataResult {
        val doc = try {
            MobiDocument.open(file, measurer)
        } catch (e: MobiException) {
            return BookMetadataResult.Failure(e.message ?: e.javaClass.simpleName)
        }
        return doc.use {
            BookMetadataResult.Success(
                title = it.metadata.title,
                author = it.metadata.author,
                coverPath = extractCover(it, file),
            )
        }
    }

    /**
     * Returns null — never throws — on any failure: a bad cover write (a full disk, a denied
     * `filesDir`) must degrade the book to "no cover" rather than fail its whole index entry.
     */
    private fun extractCover(doc: MobiDocument, file: File): String? {
        val destination = File(context.filesDir, coverFileName(file.path))
        return try {
            doc.extractCover(destination)
            destination.path
        } catch (e: Exception) {
            null
        }
    }
}
