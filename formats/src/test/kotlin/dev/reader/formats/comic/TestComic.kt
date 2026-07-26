package dev.reader.formats.comic

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a synthetic .cbz. Values are the entry bytes; a 1x1 image is enough for structure tests. */
fun buildCbz(file: File, entries: Map<String, ByteArray>) {
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
        entries.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        }
    }
}

/** A tiny valid-enough JPEG-ish blob; ComicDocument never decodes pixels, only lists entries. */
val PAGE_BYTES: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
