package dev.reader.formats.mobi

import java.nio.charset.Charset

/**
 * Everything record 0 of a MOBI says about the book: how the text is compressed, how much of it
 * there is, where the images start, and the EXTH metadata Amazon's tools write there.
 *
 * Record 0 is three structures back to back — a 16-byte PalmDOC header, the MOBI header, then an
 * optional EXTH block — and this parses all three in one pass. Every field is read defensively:
 * MOBI headers vary in length by version, so a field is only read when the header is long enough
 * to contain it.
 */
internal class MobiHeader private constructor(
    val compression: Int,
    val textLength: Int,
    val textRecordCount: Int,
    val recordSize: Int,
    val encryption: Int,
    val fileVersion: Int,
    val firstNonBookIndex: Int,
    val firstImageIndex: Int,
    val extraDataFlags: Int,
    val charset: Charset,
    val exth: Map<Int, ByteArray>,
) {

    /** The book's title from EXTH, or null — the PalmDB name is a truncated fallback, not this. */
    val title: String? get() = exthString(EXTH_TITLE)

    /** The author from EXTH, or null. */
    val author: String? get() = exthString(EXTH_AUTHOR)

    /**
     * The record index of the cover image, or null when the book declares none. EXTH 201 holds an
     * offset *relative to [firstImageIndex]*, not an absolute record number — a distinction that
     * silently yields a random interior image if missed.
     */
    val coverRecordIndex: Int?
        get() {
            if (firstImageIndex <= 0) return null
            val offset = exthUInt(EXTH_COVER_OFFSET) ?: exthUInt(EXTH_THUMB_OFFSET) ?: return null
            // 0xFFFFFFFF is the documented "absent" marker, which reads back as -1.
            if (offset < 0) return null
            return firstImageIndex + offset
        }

    private fun exthString(type: Int): String? =
        exth[type]?.toString(charset)?.trim()?.takeIf { it.isNotEmpty() }

    private fun exthUInt(type: Int): Int? {
        val b = exth[type] ?: return null
        if (b.size < 4) return null
        return ((b[0].toInt() and 0xff) shl 24) or
            ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or
            (b[3].toInt() and 0xff)
    }

    companion object {
        private const val EXTH_AUTHOR = 100
        private const val EXTH_COVER_OFFSET = 201
        private const val EXTH_THUMB_OFFSET = 203
        private const val EXTH_TITLE = 503

        private const val CP1252 = 1252
        private const val UTF8 = 65001

        /**
         * Parses record 0. Rejects, in this order: a record too short to hold the PalmDOC header,
         * an encrypted book, a missing MOBI magic, HUFF/CDIC compression, and a KF8 payload.
         *
         * The ordering matters for the message the reader sees — an encrypted KF8 file should say
         * "encrypted", which is the thing they can act on, rather than "unsupported variant".
         */
        fun parse(record0: ByteArray): MobiHeader {
            if (record0.size < 16) throw MobiException.Malformed("MOBI header record is truncated")

            val compression = u16(record0, 0)
            val textLength = i32(record0, 4)
            val textRecordCount = u16(record0, 8)
            val recordSize = u16(record0, 10)
            val encryption = u16(record0, 12)

            if (encryption != 0) {
                throw MobiException.DrmProtected(
                    "This book is encrypted. Reader cannot open DRM-protected books.",
                )
            }
            if (record0.size < 24 || String(record0, 16, 4, Charsets.US_ASCII) != "MOBI") {
                throw MobiException.NotAMobi("MOBI header is missing")
            }
            if (compression == PalmDoc.COMPRESSION_HUFF_CDIC) {
                throw MobiException.UnsupportedVariant(
                    "This book uses HUFF/CDIC compression, which Reader does not read yet.",
                )
            }
            if (compression != PalmDoc.COMPRESSION_NONE && compression != PalmDoc.COMPRESSION_PALMDOC) {
                throw MobiException.UnsupportedVariant("Unknown MOBI compression ($compression).")
            }
            if (textLength < 0) throw MobiException.Malformed("MOBI declares a negative text length")

            val headerLength = i32(record0, 20)
            val headerEnd = 16 + headerLength
            fun has(offset: Int) = headerLength >= 0 && headerEnd >= offset + 4 && record0.size >= offset + 4

            val encodingCode = if (has(28)) i32(record0, 28) else CP1252
            val charset = when (encodingCode) {
                UTF8 -> Charsets.UTF_8
                else -> cp1252OrLatin1()
            }
            val fileVersion = if (has(36)) i32(record0, 36) else 0
            val firstNonBookIndex = if (has(80)) i32(record0, 80) else 0
            val firstImageIndex = if (has(108)) i32(record0, 108) else 0
            val extraDataFlags = if (headerEnd >= 0xF4 && record0.size >= 0xF4) u16(record0, 0xF2) else 0

            val exthFlags = if (has(128)) i32(record0, 128) else 0
            val exth = if (exthFlags and 0x40 != 0) parseExth(record0, headerEnd) else emptyMap()

            // EXTH 121 is the KF8 boundary: the record where an AZW3 payload begins inside what is
            // otherwise a MOBI container. Its presence means the interesting content is KF8, and
            // rendering only the mobi7 half would silently show an inferior copy of the book.
            val boundary = exth[121]
            if (boundary != null && boundary.size >= 4) {
                val value = ((boundary[0].toInt() and 0xff) shl 24) or
                    ((boundary[1].toInt() and 0xff) shl 16) or
                    ((boundary[2].toInt() and 0xff) shl 8) or
                    (boundary[3].toInt() and 0xff)
                if (value > 0 && value != -1) {
                    throw MobiException.UnsupportedVariant(
                        "This book is an AZW3 (KF8), which Reader does not read yet.",
                    )
                }
            }

            return MobiHeader(
                compression = compression,
                textLength = textLength,
                textRecordCount = textRecordCount,
                recordSize = if (recordSize > 0) recordSize else 4096,
                encryption = encryption,
                fileVersion = fileVersion,
                firstNonBookIndex = firstNonBookIndex,
                firstImageIndex = firstImageIndex,
                extraDataFlags = extraDataFlags,
                charset = charset,
                exth = exth,
            )
        }

        /**
         * windows-1252 is the MOBI default, but it is not guaranteed present on every JVM; ISO
         * 8859-1 is, and differs only in the 0x80..0x9F range. Falling back beats throwing.
         */
        private fun cp1252OrLatin1(): Charset =
            try {
                Charset.forName("windows-1252")
            } catch (e: Exception) {
                Charsets.ISO_8859_1
            }

        /** Reads the EXTH key/value block that follows the MOBI header, if it is intact. */
        private fun parseExth(record0: ByteArray, at: Int): Map<Int, ByteArray> {
            if (at < 0 || at + 12 > record0.size) return emptyMap()
            if (String(record0, at, 4, Charsets.US_ASCII) != "EXTH") return emptyMap()
            val count = i32(record0, at + 8)
            if (count <= 0 || count > 1024) return emptyMap()
            val out = HashMap<Int, ByteArray>(count)
            var p = at + 12
            repeat(count) {
                if (p + 8 > record0.size) return out
                val type = i32(record0, p)
                val length = i32(record0, p + 4)
                // A length under 8 would not advance p and would spin forever.
                if (length < 8 || p + length > record0.size) return out
                out.putIfAbsent(type, record0.copyOfRange(p + 8, p + length))
                p += length
            }
            return out
        }

        private fun u16(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

        private fun i32(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xff) shl 24) or
                ((b[at + 1].toInt() and 0xff) shl 16) or
                ((b[at + 2].toInt() and 0xff) shl 8) or
                (b[at + 3].toInt() and 0xff)
    }
}
