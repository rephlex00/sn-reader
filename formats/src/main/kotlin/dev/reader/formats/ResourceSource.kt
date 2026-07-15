package dev.reader.formats

import java.io.Closeable
import java.io.File
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

class ZipResourceSource(file: File) : ResourceSource {
    private val zip = ZipFile(file)

    override fun open(path: String): InputStream? =
        zip.getEntry(path)?.let { zip.getInputStream(it) }

    override fun readText(path: String): String? =
        open(path)?.use { it.readBytes().toString(Charsets.UTF_8) }

    override fun exists(path: String): Boolean = zip.getEntry(path) != null

    override fun close() = zip.close()
}
