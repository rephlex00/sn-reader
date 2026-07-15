package dev.reader.formats.epub

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubPackageParserTest {

    @get:Rule val temp = TemporaryFolder()

    private val parser = EpubPackageParser()
    private fun file(name: String = "book.epub") = temp.newFile(name)

    @Test
    fun `reads metadata from the opf`() {
        val pkg = standardEpub(file()).use(parser::parse)

        assertThat(pkg.metadata.title).isEqualTo("The Test Book")
        assertThat(pkg.metadata.author).isEqualTo("A. Author")
        assertThat(pkg.metadata.language).isEqualTo("en")
    }

    @Test
    fun `locates the opf via container xml`() {
        val pkg = standardEpub(file()).use(parser::parse)
        assertThat(pkg.opfPath).isEqualTo("OEBPS/content.opf")
    }

    @Test
    fun `reads the spine in reading order`() {
        val pkg = standardEpub(file()).use(parser::parse)
        assertThat(pkg.spine).containsExactly("ch1", "ch2").inOrder()
    }

    @Test
    fun `manifest hrefs are resolved to archive paths`() {
        val pkg = standardEpub(file()).use(parser::parse)
        assertThat(pkg.manifest.getValue("ch1").href).isEqualTo("OEBPS/text/ch1.xhtml")
    }

    @Test
    fun `identifies the nav document by its properties`() {
        val pkg = standardEpub(file()).use(parser::parse)
        assertThat(pkg.navItemId).isEqualTo("nav")
    }

    @Test
    fun `finds an epub3 cover-image property`() {
        val pkg = standardEpub(file()).use(parser::parse)
        assertThat(pkg.metadata.coverHref).isEqualTo("OEBPS/images/cover.jpg")
    }

    @Test
    fun `falls back to the epub2 cover meta tag`() {
        val source = buildEpub(file()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Old Book</dc:title>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
    <item id="cover-img" href="cover.png" media-type="image/png"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="ch1"/></spine>
</package>""")
        }
        val pkg = source.use(parser::parse)

        assertThat(pkg.metadata.coverHref).isEqualTo("OEBPS/cover.png")
        assertThat(pkg.ncxItemId).isEqualTo("ncx")
        assertThat(pkg.navItemId).isNull()
    }

    @Test
    fun `skips spine itemrefs that are not in the manifest`() {
        val source = buildEpub(file()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest><item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="ch1"/><itemref idref="ghost"/></spine>
</package>""")
        }
        assertThat(source.use(parser::parse).spine).containsExactly("ch1")
    }

    @Test
    fun `reports a missing container as not an epub`() {
        val source = buildEpub(file()) { entry("random.txt", "hello") }
        val e = runCatching { source.use(parser::parse) }.exceptionOrNull()

        assertThat(e).isInstanceOf(EpubException.NotAnEpub::class.java)
    }

    @Test
    fun `reports a missing opf as malformed`() {
        val source = buildEpub(file()) { entry("META-INF/container.xml", CONTAINER_XML) }
        val e = runCatching { source.use(parser::parse) }.exceptionOrNull()

        assertThat(e).isInstanceOf(EpubException.Malformed::class.java)
    }

    @Test
    fun `reports an empty spine as malformed`() {
        val source = buildEpub(file()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest/>
  <spine/>
</package>""")
        }
        val e = runCatching { source.use(parser::parse) }.exceptionOrNull()

        assertThat(e).isInstanceOf(EpubException.Malformed::class.java)
    }

    @Test
    fun `detects DRM by the presence of encryption xml`() {
        val source = buildEpub(file()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("META-INF/encryption.xml", "<encryption/>")
            entry("OEBPS/content.opf", "<package/>")
        }
        val e = runCatching { source.use(parser::parse) }.exceptionOrNull()

        assertThat(e).isInstanceOf(EpubException.DrmProtected::class.java)
    }

    @Test
    fun `titles an untitled book rather than failing`() {
        val source = buildEpub(file()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"/>
  <manifest><item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""")
        }
        val pkg = source.use(parser::parse)

        assertThat(pkg.metadata.title).isEqualTo("Untitled")
        assertThat(pkg.metadata.author).isNull()
    }
}
