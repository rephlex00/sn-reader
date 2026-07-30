package dev.reader.formats.mobi

/**
 * PalmDOC decompression, and the trailing bytes that have to come off a MOBI text record first.
 *
 * Both halves are pure functions over one record. A MOBI's text is the concatenation of its
 * decompressed text records, so a chapter that spans records 12..15 needs exactly those four
 * decompressed and joined — nothing earlier, nothing later.
 *
 * The two easy ways to get this wrong, both of which produce plausible-looking output that is
 * subtly corrupt rather than an outright failure:
 *
 *  * Forgetting [trimTrailingEntries]. MOBI appends per-record extras (an inline index, a
 *    multibyte-character overlap) after the compressed data, and the flags saying which are
 *    present live in the MOBI header, not the record. Feed those bytes to the decompressor and
 *    it happily emits garbage at the end of every record.
 *  * Treating the back-reference window as reaching across records. It does not: each record
 *    decompresses independently, so a distance may never point before the start of *this*
 *    record's output.
 */
internal object PalmDoc {

    /** The compression value in the PalmDOC header meaning "records are stored as-is". */
    const val COMPRESSION_NONE = 1

    /** The compression value meaning PalmDOC's LZ77 variant — the one implemented here. */
    const val COMPRESSION_PALMDOC = 2

    /** The compression value meaning HUFF/CDIC, which this reader does not implement. */
    const val COMPRESSION_HUFF_CDIC = 17480

    /**
     * Strips the trailing data entries [flags] says record [data] carries.
     *
     * The flags are a bitfield from the MOBI header. Bits 1..15 each mean "one more trailing
     * entry, whose length is written as a backwards variable-width integer in its own last
     * bytes" — so they must be stripped from the end inwards, high bit first. Bit 0 is different:
     * it means the record ends with up to four bytes of a multibyte character that overlaps into
     * the next record, and the count is in the low two bits of the final byte.
     *
     * Returns [data] unchanged when no flags are set, which is the common case for a book with a
     * single text stream and no inline index.
     */
    fun trimTrailingEntries(data: ByteArray, flags: Int): ByteArray {
        if (flags == 0 || data.isEmpty()) return data
        var end = data.size
        for (bit in 15 downTo 1) {
            if (flags and (1 shl bit) == 0) continue
            if (end <= 0) return ByteArray(0)
            val size = backwardsVarint(data, end)
            // A corrupt length that would eat the whole record is treated as "no entry" rather
            // than truncating to nothing — a damaged tail should cost a line, not the chapter.
            if (size <= 0 || size > end) continue
            end -= size
        }
        if (flags and 1 != 0 && end > 0) {
            val overlap = (data[end - 1].toInt() and 0x3) + 1
            if (overlap <= end) end -= overlap
        }
        return if (end == data.size) data else data.copyOf(end)
    }

    /**
     * Reads the backwards variable-width integer ending at [end] (exclusive) — the encoding MOBI
     * uses for trailing-entry lengths. Bytes are consumed from the end towards the start, seven
     * bits at a time; the byte with its high bit set is the last one read.
     */
    private fun backwardsVarint(data: ByteArray, end: Int): Int {
        var bitpos = 0
        var result = 0
        var index = end
        while (true) {
            if (index <= 0) return result
            val v = data[index - 1].toInt() and 0xff
            result = result or ((v and 0x7f) shl bitpos)
            bitpos += 7
            index--
            if (v and 0x80 != 0 || bitpos >= 28) return result
        }
    }

    /**
     * Decompresses one PalmDOC LZ77 record. The encoding, by leading byte:
     *
     *  * `0x00`         — a literal NUL.
     *  * `0x01..0x08`   — that many literal bytes follow.
     *  * `0x09..0x7f`   — the byte itself, a literal ASCII character.
     *  * `0x80..0xbf`   — a two-byte back-reference: 11 bits of distance, 3 bits of length + 3.
     *  * `0xc0..0xff`   — a space, then the byte with its high bit cleared.
     *
     * Malformed input stops the record rather than throwing: a truncated or corrupt tail yields
     * the text decoded so far, so one bad record costs a paragraph instead of the book.
     */
    fun decompress(record: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(record.size * 2)
        // The back-reference window looks into bytes already emitted for THIS record, so a copy
        // of the output so far is what distances index into.
        val window = ArrayList<Byte>(record.size * 2)
        var i = 0
        while (i < record.size) {
            val b = record[i].toInt() and 0xff
            i++
            when {
                b == 0 -> emit(out, window, 0)
                b < 9 -> {
                    // b literal bytes follow; a truncated run copies what is actually there.
                    val available = minOf(b, record.size - i)
                    for (k in 0 until available) emit(out, window, record[i + k].toInt() and 0xff)
                    i += available
                    if (available < b) break
                }
                b < 0x80 -> emit(out, window, b)
                b < 0xc0 -> {
                    if (i >= record.size) break
                    val pair = (b shl 8) or (record[i].toInt() and 0xff)
                    i++
                    val distance = (pair shr 3) and 0x07ff
                    val length = (pair and 0x07) + 3
                    if (distance == 0 || distance > window.size) break
                    // Copied one byte at a time on purpose: an overlapping run (distance < length)
                    // is legal and must read bytes this same loop is writing.
                    for (k in 0 until length) emit(out, window, window[window.size - distance].toInt() and 0xff)
                }
                else -> {
                    emit(out, window, ' '.code)
                    emit(out, window, b xor 0x80)
                }
            }
        }
        return out.toByteArray()
    }

    private fun emit(out: java.io.ByteArrayOutputStream, window: ArrayList<Byte>, value: Int) {
        out.write(value)
        window.add(value.toByte())
    }
}
