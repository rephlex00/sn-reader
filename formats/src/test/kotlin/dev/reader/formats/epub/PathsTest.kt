package dev.reader.formats.epub

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PathsTest {

    @Test
    fun `resolves href relative to the opf directory`() {
        assertThat(resolveHref("OEBPS/content.opf", "text/ch1.xhtml"))
            .isEqualTo("OEBPS/text/ch1.xhtml")
    }

    @Test
    fun `resolves href when the opf is at the archive root`() {
        assertThat(resolveHref("content.opf", "ch1.xhtml")).isEqualTo("ch1.xhtml")
    }

    @Test
    fun `walks up parent segments`() {
        assertThat(resolveHref("OEBPS/text/ch1.xhtml", "../images/cover.jpg"))
            .isEqualTo("OEBPS/images/cover.jpg")
    }

    @Test
    fun `drops the fragment identifier`() {
        assertThat(resolveHref("OEBPS/content.opf", "text/ch1.xhtml#section-2"))
            .isEqualTo("OEBPS/text/ch1.xhtml")
    }

    @Test
    fun `percent-decodes the href`() {
        assertThat(resolveHref("OEBPS/content.opf", "text/chapter%20one.xhtml"))
            .isEqualTo("OEBPS/text/chapter one.xhtml")
    }

    @Test
    fun `cannot escape above the archive root`() {
        assertThat(resolveHref("content.opf", "../../etc/passwd")).isEqualTo("etc/passwd")
    }

    @Test
    fun `does not treat a literal plus as a space`() {
        assertThat(resolveHref("OEBPS/content.opf", "text/chapter+one.xhtml"))
            .isEqualTo("OEBPS/text/chapter+one.xhtml")
    }

    @Test
    fun `preserves a plus in a real filename`() {
        assertThat(resolveHref("OEBPS/content.opf", "text/C++ Primer.xhtml"))
            .isEqualTo("OEBPS/text/C++ Primer.xhtml")
    }

    @Test
    fun `tolerates a stray percent without throwing`() {
        assertThat(resolveHref("OEBPS/content.opf", "text/100%.xhtml"))
            .isEqualTo("OEBPS/text/100%.xhtml")
    }

    @Test
    fun `tolerates an invalid hex escape without throwing`() {
        assertThat(resolveHref("OEBPS/content.opf", "text/%zz.xhtml"))
            .isEqualTo("OEBPS/text/%zz.xhtml")
    }

    @Test
    fun `percent-encoded traversal is still clamped at the archive root`() {
        assertThat(resolveHref("content.opf", "%2e%2e%2f%2e%2e%2fetc%2fpasswd"))
            .isEqualTo("etc/passwd")
    }

    @Test
    fun `percentDecode leaves plus literal`() {
        assertThat(percentDecode("chapter+one")).isEqualTo("chapter+one")
    }

    @Test
    fun `percentDecode decodes percent20 to a space`() {
        assertThat(percentDecode("chapter%20one")).isEqualTo("chapter one")
    }

    @Test
    fun `percentDecode degrades a stray percent literally`() {
        assertThat(percentDecode("100%.xhtml")).isEqualTo("100%.xhtml")
    }

    @Test
    fun `percentDecode degrades an invalid hex escape literally`() {
        assertThat(percentDecode("%zz.xhtml")).isEqualTo("%zz.xhtml")
    }

    @Test
    fun `percentDecode degrades a signed hex escape literally instead of parsing the sign`() {
        // toIntOrNull(16) accepts a leading sign; a bare hex-digit check must reject these
        // as valid escapes so they degrade to literals like any other malformed escape.
        assertThat(percentDecode("%-1")).isEqualTo("%-1")
        assertThat(percentDecode("%+a")).isEqualTo("%+a")
    }

    @Test
    fun `percentDecode decodes a multi-byte utf-8 sequence`() {
        assertThat(percentDecode("%C3%A9")).isEqualTo("é")
    }

    @Test
    fun `percentDecode preserves an astral character alongside a percent escape`() {
        // A surrogate pair must survive being batched through the same literal run as
        // the rest of the string, not be encoded one UTF-16 code unit at a time.
        assertThat(percentDecode("📚 ch%20one")).isEqualTo("📚 ch one")
    }
}
