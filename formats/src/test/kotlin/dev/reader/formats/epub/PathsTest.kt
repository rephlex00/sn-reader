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
}
