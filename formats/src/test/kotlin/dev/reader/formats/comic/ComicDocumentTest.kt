package dev.reader.formats.comic

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class ComicDocumentTest {
    private fun tmp(name: String) = File.createTempFile(name, ".cbz").also { it.deleteOnExit() }

    @Test fun `pages come out in natural order, not lexicographic`() {
        val f = tmp("nat")
        buildCbz(f, mapOf(
            "p10.jpg" to PAGE_BYTES, "p9.jpg" to PAGE_BYTES, "p1.jpg" to PAGE_BYTES,
        ))
        ComicDocument.open(f).use { doc ->
            assertThat(doc.spineSize).isEqualTo(3)
            assertThat((0 until 3).map { doc.pagePath(it) })
                .containsExactly("p1.jpg", "p9.jpg", "p10.jpg").inOrder()
        }
    }

    @Test fun `non-image entries and junk are skipped`() {
        val f = tmp("filter")
        buildCbz(f, mapOf(
            "dir/" to ByteArray(0), "0001.png" to PAGE_BYTES, "ComicInfo.xml" to "<ComicInfo/>".toByteArray(),
            "__MACOSX/._0001.png" to PAGE_BYTES, "Thumbs.db" to PAGE_BYTES, ".hidden" to PAGE_BYTES,
            "0002.jpeg" to PAGE_BYTES,
        ))
        ComicDocument.open(f).use { doc ->
            assertThat((0 until doc.spineSize).map { doc.pagePath(it) })
                .containsExactly("0001.png", "0002.jpeg").inOrder()
        }
    }

    @Test fun `nested chapter folders stay in order`() {
        val f = tmp("nested")
        buildCbz(f, mapOf(
            "ch10/001.jpg" to PAGE_BYTES, "ch2/001.jpg" to PAGE_BYTES, "ch2/002.jpg" to PAGE_BYTES,
        ))
        ComicDocument.open(f).use { doc ->
            assertThat((0 until 3).map { doc.pagePath(it) })
                .containsExactly("ch2/001.jpg", "ch2/002.jpg", "ch10/001.jpg").inOrder()
        }
    }

    @Test fun `a zip with no images is NoImages`() {
        val f = tmp("empty")
        buildCbz(f, mapOf("ComicInfo.xml" to "<ComicInfo/>".toByteArray(), "readme.txt" to PAGE_BYTES))
        assertThrows(ComicException.NoImages::class.java) { ComicDocument.open(f) }
    }

    @Test fun `a RAR archive is RarUnsupported`() {
        val f = tmp("rar")
        f.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
        assertThrows(ComicException.RarUnsupported::class.java) { ComicDocument.open(f) }
    }

    @Test fun `a foreign file is NotAComic`() {
        val f = tmp("pdf")
        f.writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D))
        assertThrows(ComicException.NotAComic::class.java) { ComicDocument.open(f) }
    }

    @Test fun `ComicInfo series and number form the title, Manga sets direction`() {
        val f = tmp("meta")
        buildCbz(f, mapOf(
            "001.jpg" to PAGE_BYTES,
            "ComicInfo.xml" to ("<ComicInfo><Series>Berserk</Series><Number>3</Number>" +
                "<Writer>Miura</Writer><Manga>YesAndRightToLeft</Manga></ComicInfo>").toByteArray(),
        ))
        ComicDocument.open(f).use { doc ->
            assertThat(doc.metadata.title).isEqualTo("Berserk #3")
            assertThat(doc.metadata.author).isEqualTo("Miura")
            assertThat(doc.readingDirectionRtl).isTrue()
        }
    }

    @Test fun `no ComicInfo falls back to the filename and left-to-right`() {
        val f = File.createTempFile("Spider-Man 001", ".cbz").also { it.deleteOnExit() }
        buildCbz(f, mapOf("001.jpg" to PAGE_BYTES))
        ComicDocument.open(f).use { doc ->
            assertThat(doc.metadata.title).startsWith("Spider-Man 001")
            assertThat(doc.readingDirectionRtl).isFalse()
        }
    }

    @Test fun `lowercase comicinfo_xml is matched case-insensitively`() {
        val f = tmp("lowercase")
        buildCbz(f, mapOf(
            "001.jpg" to PAGE_BYTES,
            "comicinfo.xml" to ("<ComicInfo><Series>Naruto</Series><Number>25</Number>" +
                "<Writer>Kishimoto</Writer><Manga>YesAndRightToLeft</Manga></ComicInfo>").toByteArray(),
        ))
        ComicDocument.open(f).use { doc ->
            assertThat(doc.metadata.title).isEqualTo("Naruto #25")
            assertThat(doc.metadata.author).isEqualTo("Kishimoto")
            assertThat(doc.readingDirectionRtl).isTrue()
        }
    }
}
