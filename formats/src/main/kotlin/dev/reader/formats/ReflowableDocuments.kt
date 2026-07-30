package dev.reader.formats

import dev.reader.engine.TextMeasurer
import dev.reader.formats.epub.EpubDocument
import dev.reader.formats.mobi.MobiDocument
import java.io.File

/**
 * Opens whichever reflowable format a file turns out to be.
 *
 * The one place that decides between EPUB and MOBI, so the reader and the preview-strip renderer
 * can both say "open this book" without either of them learning what a PalmDB is.
 *
 * Extension first, bytes as the tiebreak — the same layering `ContainerFormat` uses for
 * zip-versus-RAR comics. Extension is what the library already routes on and what a reader
 * expects to be believed; the magic-byte fallback only covers a file whose extension says
 * nothing useful, which sideloaded books regularly do.
 */
object ReflowableDocuments {

    /**
     * @throws dev.reader.formats.epub.EpubException for an unreadable EPUB.
     * @throws dev.reader.formats.mobi.MobiException for an unreadable or unsupported MOBI.
     */
    fun open(file: File, measurer: TextMeasurer): ReflowableDocument =
        when (file.extension.lowercase()) {
            "mobi", "azw", "prc" -> MobiDocument.open(file, measurer)
            "epub" -> EpubDocument.open(file, measurer)
            else -> if (looksLikePalmDb(file)) {
                MobiDocument.open(file, measurer)
            } else {
                EpubDocument.open(file, measurer)
            }
        }

    /**
     * Whether [file] carries the PalmDB type/creator pair a MOBI book uses, at the fixed offset
     * the container puts them. Cheap — 68 bytes — and only consulted when the extension has
     * already failed to answer.
     */
    private fun looksLikePalmDb(file: File): Boolean =
        try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 68) return false
                val head = ByteArray(68)
                raf.readFully(head)
                String(head, 60, 8, Charsets.US_ASCII) == "BOOKMOBI"
            }
        } catch (e: java.io.IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
}
