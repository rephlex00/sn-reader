package dev.reader.formats

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Random access to the entries of a container. Books are read in place and lazily —
 * only the chapter being read is decompressed.
 */
interface ResourceSource : Closeable {
    fun open(path: String): InputStream?
    fun readText(path: String): String?
    fun exists(path: String): Boolean

    /**
     * Uncompressed byte size of [path]'s entry, or `0L` if it is absent or the source does not
     * track sizes. For a zip this is a central-directory read — no decompression, no stream —
     * which is what lets the reader weigh chapters for a progress estimate without paginating
     * them. The default returns `0L` (a weightless entry) so non-zip / test sources need not
     * implement it.
     */
    fun size(path: String): Long = 0L
}

/**
 * Shared entry-size cap for both [readText] and the binary reader in the epub package
 * ([dev.reader.formats.epub.readBytesChecked]). Legitimate container/OPF/chapter files are
 * kilobytes, so this looks generous for text — but a cover image is the only entry type
 * that plausibly approaches it, and this is the actual gate that keeps a hostile or
 * oversized cover from being fully decoded downstream. Keep it tight rather than generous:
 * [readCapped]'s `ByteArrayOutputStream` doubles its backing array as it grows and then
 * `toByteArray()` copies it again, so even a cover comfortably under this cap carries a
 * real transient cost — a 5 MB JPEG peaks around 13 MB in memory before decoding even
 * starts. A rejected cover only degrades to the generated placeholder, so a tight cap here
 * costs almost nothing.
 */
private const val MAX_ENTRY_SIZE = 16 * 1024 * 1024L

/**
 * Random access into a zip archive (e.g. an EPUB container).
 *
 * Constructing this eagerly reads the zip's central directory and throws a raw
 * `java.util.zip.ZipException` (or `IOException`) for a non-zip file — a `.txt` renamed
 * to `.epub`, a truncated download — before any format-specific `parse` call is ever
 * entered. This class is format-neutral and has no notion of a typed EPUB exception
 * hierarchy; translating that construction failure into a typed, user-facing error is
 * the caller's responsibility.
 */
class ZipResourceSource(file: File) : ResourceSource {
    private val zip = ZipFile(file)

    override fun open(path: String): InputStream? =
        zip.getEntry(path)?.let { zip.getInputStream(it) }

    override fun readText(path: String): String? {
        val entry = zip.getEntry(path) ?: return null
        // Fast path: a lying/unknown declared size (-1) never trips this, but an honest
        // large declared size lets us reject before spending any time decompressing.
        if (entry.size > MAX_ENTRY_SIZE) {
            throw IOException(
                "Entry \"$path\" declares size ${entry.size} bytes, exceeding the " +
                    "$MAX_ENTRY_SIZE byte cap.",
            )
        }
        val bytes = zip.getInputStream(entry).use { input -> readCapped(input, path) }
        return bytes.toString(Charsets.UTF_8)
    }

    override fun exists(path: String): Boolean = zip.getEntry(path) != null

    override fun size(path: String): Long = zip.getEntry(path)?.size ?: 0L

    override fun close() = zip.close()
}

/**
 * Reads [input] fully but aborts as soon as more than [MAX_ENTRY_SIZE] bytes have come
 * through — a backstop for a decompression bomb whose declared size understates (or
 * omits) how much data it actually inflates to.
 *
 * Internal (not private) so it can be unit-tested directly against a hand-built
 * unbounded [InputStream], independent of the declared-size fast path in [readText]
 * above, which a truthful zip entry size will always trip first.
 */
internal fun readCapped(input: InputStream, path: String): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = input.read(chunk)
        if (read == -1) break
        total += read
        if (total > MAX_ENTRY_SIZE) {
            throw IOException(
                "Entry \"$path\" exceeded the $MAX_ENTRY_SIZE byte cap while reading " +
                    "(declared size was within the cap, or unknown).",
            )
        }
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}
