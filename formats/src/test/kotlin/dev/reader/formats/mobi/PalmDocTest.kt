package dev.reader.formats.mobi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PalmDocTest {

    @Test
    fun `a literal-only encoding round-trips`() {
        val text = "The quick brown fox jumps over the lazy dog.".toByteArray()
        val encoded = TestMobi.compressLiterals(text)
        assertThat(PalmDoc.decompress(encoded)).isEqualTo(text)
    }

    @Test
    fun `a back-reference repeats earlier output`() {
        // "abcabc": literal 'a','b','c' then a back-reference of distance 3, length 3.
        val pair = (3 shl 3) or (3 - 3) // distance 3, length 3
        val encoded = byteArrayOf(
            'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            (0x80 or ((pair shr 8) and 0x3f)).toByte(), (pair and 0xff).toByte(),
        )
        assertThat(String(PalmDoc.decompress(encoded))).isEqualTo("abcabc")
    }

    @Test
    fun `an overlapping back-reference reads bytes it is still writing`() {
        // Distance 1, length 5 over a single 'x' must yield "xxxxxx" — the run reads bytes this
        // same copy is emitting. A block-copy implementation gets this wrong.
        val pair = (1 shl 3) or (5 - 3)
        val encoded = byteArrayOf(
            'x'.code.toByte(),
            (0x80 or ((pair shr 8) and 0x3f)).toByte(), (pair and 0xff).toByte(),
        )
        assertThat(String(PalmDoc.decompress(encoded))).isEqualTo("xxxxxx")
    }

    @Test
    fun `the high range emits a space and the unmasked character`() {
        assertThat(String(PalmDoc.decompress(byteArrayOf((0xc0 or 'A'.code).toByte()))))
            .isEqualTo(" A")
    }

    @Test
    fun `a truncated record yields what decoded rather than throwing`() {
        // A back-reference whose second byte was cut off, and a literal run promising more bytes
        // than remain: a damaged record must cost a fragment, not the chapter.
        assertThat(String(PalmDoc.decompress(byteArrayOf('a'.code.toByte(), 0x81.toByte()))))
            .isEqualTo("a")
        assertThat(String(PalmDoc.decompress(byteArrayOf(0x05, 'h'.code.toByte(), 'i'.code.toByte()))))
            .isEqualTo("hi")
    }

    @Test
    fun `a back-reference pointing before the record stops the record`() {
        // Distance 500 with nothing emitted yet cannot be satisfied: records decompress
        // independently, so there is no earlier output to reach into.
        val pair = (500 shl 3) or 0
        val encoded = byteArrayOf((0x80 or ((pair shr 8) and 0x3f)).toByte(), (pair and 0xff).toByte())
        assertThat(PalmDoc.decompress(encoded)).isEmpty()
    }

    @Test
    fun `no flags leaves a record untouched`() {
        val data = byteArrayOf(1, 2, 3, 4)
        assertThat(PalmDoc.trimTrailingEntries(data, 0)).isSameInstanceAs(data)
    }

    @Test
    fun `the multibyte-overlap flag strips the count in the last two bits`() {
        // Bit 0 set: the final byte's low two bits say how many extra bytes precede it (n+1).
        val data = byteArrayOf(9, 9, 9, 7, 7, 0b01)
        // (0b01 & 3) + 1 == 2, so the last two bytes come off.
        assertThat(PalmDoc.trimTrailingEntries(data, 1)).isEqualTo(byteArrayOf(9, 9, 9, 7))
    }

    @Test
    fun `a trailing entry is measured by its own backwards varint`() {
        // Bit 1 set: one trailing entry whose total size is written in its last byte, high bit
        // set to terminate. 0x83 == size 3, so three bytes come off.
        val data = byteArrayOf(1, 2, 3, 4, 0x11, 0x22, 0x83.toByte())
        assertThat(PalmDoc.trimTrailingEntries(data, 1 shl 1)).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun `a corrupt trailing length is ignored rather than eating the record`() {
        // A varint claiming more bytes than the record holds must not truncate it to nothing.
        val data = byteArrayOf(1, 2, 3, 0xFF.toByte())
        val trimmed = PalmDoc.trimTrailingEntries(data, 1 shl 1)
        assertThat(trimmed.size).isAtLeast(3)
    }
}
