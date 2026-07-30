package dev.reader.formats.mobi

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Builds synthetic MOBI files in-test, the way `TestEpub`/`TestComic` build zips: nothing binary
 * is checked in, and every fixture states its own shape in code where a test can read it.
 *
 * Writes a real PalmDB — 78-byte header, record offset table, record 0 with PalmDOC + MOBI + EXTH
 * headers, then text records — so the parser under test is exercised end to end rather than
 * against a mock.
 */
internal object TestMobi {

    /** PalmDOC-compresses [data] the simple way: literals only, which is a valid encoding. */
    fun compressLiterals(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xff
            when {
                // 0x09..0x7f encode as themselves.
                b in 0x09..0x7f -> { out.write(b); i++ }
                // Everything else goes in a literal run, which may hold up to eight bytes.
                else -> {
                    val run = ByteArrayOutputStream()
                    while (i < data.size && run.size() < 8) {
                        val c = data[i].toInt() and 0xff
                        if (c in 0x09..0x7f) break
                        run.write(c)
                        i++
                    }
                    if (run.size() > 0) {
                        out.write(run.size())
                        out.write(run.toByteArray())
                    }
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Writes a MOBI containing [html] as its text, to [file].
     *
     * @param compressed PalmDOC-compress the text records (compression 2) rather than store them
     *   raw (compression 1) — both are legal and both are read.
     * @param encryption written into the header verbatim, so a test can build a DRM'd book.
     * @param recordSize uncompressed bytes per text record; small values make multi-record
     *   fixtures without megabytes of text.
     * @param exth extra EXTH records by type, on top of the title and author.
     * @param images appended after the text records, so `recindex`/cover lookups have somewhere
     *   to point.
     */
    fun write(
        file: File,
        html: String,
        title: String = "Test Book",
        author: String? = "A. Author",
        compressed: Boolean = true,
        encryption: Int = 0,
        recordSize: Int = 4096,
        exth: Map<Int, ByteArray> = emptyMap(),
        images: List<ByteArray> = emptyList(),
    ): File {
        val text = html.toByteArray(Charsets.UTF_8)
        val textRecords = text.toList().chunked(recordSize) { chunk ->
            val raw = chunk.toByteArray()
            if (compressed) compressLiterals(raw) else raw
        }

        val exthAll = LinkedHashMap<Int, ByteArray>()
        exthAll[100] = (author ?: "").toByteArray(Charsets.UTF_8)
        exthAll[503] = title.toByteArray(Charsets.UTF_8)
        exthAll.putAll(exth)

        val firstImageIndex = 1 + textRecords.size
        val record0 = buildRecord0(
            compression = if (compressed) 2 else 1,
            textLength = text.size,
            textRecordCount = textRecords.size,
            recordSize = recordSize,
            encryption = encryption,
            firstNonBookIndex = firstImageIndex,
            firstImageIndex = if (images.isEmpty()) 0 else firstImageIndex,
            exth = exthAll,
        )

        val records = buildList {
            add(record0)
            addAll(textRecords)
            addAll(images)
        }

        val out = ByteArrayOutputStream()
        // --- PalmDB header ---
        val name = title.take(31).replace(' ', '_').toByteArray(Charsets.US_ASCII)
        out.write(name); repeat(32 - name.size) { out.write(0) }
        out.write(ByteArray(28)) // attributes, version, dates, modNum, appInfo, sortInfo
        out.write("BOOK".toByteArray(Charsets.US_ASCII))
        out.write("MOBI".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(8)) // uniqueIdSeed, nextRecordListId
        writeShort(out, records.size)

        var offset = 78 + records.size * 8
        for (r in records) {
            writeInt(out, offset)
            out.write(0) // attributes
            out.write(0); out.write(0); out.write(0) // uniqueId
            offset += r.size
        }
        for (r in records) out.write(r)

        file.writeBytes(out.toByteArray())
        return file
    }

    private fun buildRecord0(
        compression: Int,
        textLength: Int,
        textRecordCount: Int,
        recordSize: Int,
        encryption: Int,
        firstNonBookIndex: Int,
        firstImageIndex: Int,
        exth: Map<Int, ByteArray>,
    ): ByteArray {
        val exthBlock = buildExth(exth)
        // A 232-byte MOBI header is what real files carry, and it is long enough to reach every
        // field the parser reads (first-image index at 108, EXTH flags at 128).
        val mobiHeaderLength = 232
        val out = ByteArrayOutputStream()

        // --- PalmDOC header (16 bytes) ---
        writeShort(out, compression)
        writeShort(out, 0)
        writeInt(out, textLength)
        writeShort(out, textRecordCount)
        writeShort(out, recordSize)
        writeShort(out, encryption)
        writeShort(out, 0)

        // --- MOBI header ---
        val mobi = ByteArrayOutputStream()
        mobi.write("MOBI".toByteArray(Charsets.US_ASCII)) // 16
        writeInt(mobi, mobiHeaderLength) // 20
        writeInt(mobi, 2) // 24: mobiType = book
        writeInt(mobi, 65001) // 28: UTF-8
        writeInt(mobi, 0) // 32: uid
        writeInt(mobi, 6) // 36: file version
        repeat(10) { writeInt(mobi, 0) } // 40..79
        writeInt(mobi, firstNonBookIndex) // 80
        repeat(6) { writeInt(mobi, 0) } // 84..107
        writeInt(mobi, firstImageIndex) // 108
        repeat(4) { writeInt(mobi, 0) } // 112..127
        writeInt(mobi, if (exth.isEmpty()) 0 else 0x40) // 128: EXTH flags
        // Pad to the declared header length. extraDataFlags sits at 0xF2 and stays zero, which is
        // what a fixture wants: no trailing entries to strip.
        while (mobi.size() < mobiHeaderLength) mobi.write(0)
        out.write(mobi.toByteArray())
        if (exth.isNotEmpty()) out.write(exthBlock)
        return out.toByteArray()
    }

    private fun buildExth(records: Map<Int, ByteArray>): ByteArray {
        if (records.isEmpty()) return ByteArray(0)
        val body = ByteArrayOutputStream()
        for ((type, value) in records) {
            writeInt(body, type)
            writeInt(body, value.size + 8)
            body.write(value)
        }
        val out = ByteArrayOutputStream()
        out.write("EXTH".toByteArray(Charsets.US_ASCII))
        writeInt(out, 12 + body.size())
        writeInt(out, records.size)
        out.write(body.toByteArray())
        while (out.size() % 4 != 0) out.write(0)
        return out.toByteArray()
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xff); out.write(v and 0xff)
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xff); out.write((v shr 16) and 0xff)
        out.write((v shr 8) and 0xff); out.write(v and 0xff)
    }

    /** A minimal JPEG-looking blob: enough bytes to be stored and fetched back. */
    val IMAGE_BYTES: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
    ) + ByteArray(64) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
}
