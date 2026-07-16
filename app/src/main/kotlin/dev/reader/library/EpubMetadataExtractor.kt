package dev.reader.library

import android.content.Context
import dev.reader.data.BookMetadataResult
import dev.reader.data.MetadataExtractor
import dev.reader.formats.ZipResourceSource
import dev.reader.formats.epub.EpubCoverExtractor
import dev.reader.formats.epub.EpubDocument
import dev.reader.formats.epub.EpubException
import dev.reader.formats.render.AndroidTextMeasurer
import dev.reader.formats.render.SpannedChapterBuilder
import dev.reader.formats.render.TypefaceProvider
import java.io.File
import java.security.MessageDigest

/**
 * The seam Plan 2 Tasks 3/4 left unwired: `:data`'s [LibraryIndexer][dev.reader.data.LibraryIndexer]
 * needs a [MetadataExtractor], and this composes it out of `:formats`' EPUB parsing
 * ([EpubDocument]) and cover extraction ([EpubCoverExtractor]) — the first place either has been
 * driven from production code rather than a test. Until this class existed, `coverPath` was null
 * everywhere: nothing outside a test had ever called [EpubCoverExtractor.extract].
 *
 * [LibraryIndexer.sync] runs this on [kotlinx.coroutines.Dispatchers.IO] (its own contract, not
 * this class's), calling [extract] once per new/changed file, never concurrently for the same
 * file. Each call opens a fresh [EpubDocument] (and a second, independent [ZipResourceSource] for
 * the cover — see [extractCover]) local to that one call and closes both before returning, so
 * [EpubDocument.chapter]'s documented non-thread-safe cache is never even reached: title/author
 * come from [EpubDocument.metadata], and the cover comes from the raw archive, not from paginated
 * content, so `chapter()` is never called here at all.
 */
class EpubMetadataExtractor(private val context: Context) : MetadataExtractor {

    private val coverExtractor = EpubCoverExtractor()

    // AndroidTextMeasurer is required to open an EpubDocument at all (EpubDocument.open's
    // signature demands one), even though this extractor never paginates a single chapter —
    // metadata and the cover are both read straight from the package/archive.
    private val measurer = AndroidTextMeasurer(SpannedChapterBuilder(), TypefaceProvider.Platform)

    /**
     * A malformed or DRM-protected book returns [BookMetadataResult.Failure] with
     * [EpubException]'s own message, per the "malformed books never crash" contract — this must
     * never throw. Any exception that isn't [EpubException] is still allowed to propagate: it
     * would indicate a bug in `:formats` rather than a bad book, and
     * [LibraryIndexer][dev.reader.data.LibraryIndexer]'s own catch-all around
     * `extractor.extract(file)` is the second line of defense that turns it into a Failure anyway
     * (see its KDoc), so nothing here needs to duplicate that.
     */
    override fun extract(file: File): BookMetadataResult {
        val doc = try {
            EpubDocument.open(file, measurer)
        } catch (e: EpubException) {
            return BookMetadataResult.Failure(e.message ?: e.javaClass.simpleName)
        }
        return doc.use {
            val metadata = doc.metadata
            BookMetadataResult.Success(
                title = metadata.title,
                author = metadata.author,
                coverPath = extractCover(file, metadata),
            )
        }
    }

    /**
     * Opens a second, independent [ZipResourceSource] over the same file rather than reaching
     * into the already-open [EpubDocument] for its source: that field is a private constructor
     * parameter with no accessor, and adding one would widen `:formats`' public surface for this
     * one caller. Cover extraction happens once per new/changed book (never on every scan, and
     * never while a page is displayed), so a second, short-lived zip open costs nothing worth
     * avoiding that.
     *
     * Returns null — never throws — on any failure: a bad cover write (a full disk, a denied
     * `filesDir`) must degrade the book to "no cover" rather than fail the whole sync entry, since
     * [EpubCoverExtractor.extract] itself is documented as not unconditionally exception-free.
     */
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

/**
 * A stable, filesystem-safe cover thumbnail filename for [bookPath].
 *
 * Book paths contain `/` and arbitrary characters that are not valid filename components, so the
 * path can't be reused directly. Hashed (not passed through directly, and not [String.hashCode] —
 * only 32 bits, not collision-resistant across a real library) so:
 * - the same book always resolves to the same cover file across re-syncs, letting a re-extracted
 *   cover overwrite the old one in place rather than accumulating a new file per sync, and
 * - two different books never collide.
 *
 * Pure and JVM-testable without Robolectric — no Android import, unlike the rest of this file.
 */
internal fun coverFileName(bookPath: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bookPath.toByteArray(Charsets.UTF_8))
    val hex = buildString(digest.size * 2) { digest.forEach { append("%02x".format(it)) } }
    return "$hex.png"
}
