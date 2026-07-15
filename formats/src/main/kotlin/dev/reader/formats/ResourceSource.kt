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
}

/** Legitimate container/OPF/chapter files are kilobytes; this is a generous ceiling. */
private const val MAX_TEXT_SIZE = 16 * 1024 * 1024L

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
        if (entry.size > MAX_TEXT_SIZE) {
            throw IOException(
                "Entry \"$path\" declares size ${entry.size} bytes, exceeding the " +
                    "$MAX_TEXT_SIZE byte cap.",
            )
        }
        val bytes = zip.getInputStream(entry).use { input -> readCapped(input, path) }
        return bytes.toString(Charsets.UTF_8)
    }

    override fun exists(path: String): Boolean = zip.getEntry(path) != null

    override fun close() = zip.close()
}

/**
 * Reads [input] fully but aborts as soon as more than [MAX_TEXT_SIZE] bytes have come
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
        if (total > MAX_TEXT_SIZE) {
            throw IOException(
                "Entry \"$path\" exceeded the $MAX_TEXT_SIZE byte cap while reading " +
                    "(declared size was within the cap, or unknown).",
            )
        }
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}
