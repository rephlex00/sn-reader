package dev.reader.formats.epub

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubTocParserTest {

    @get:Rule val temp = TemporaryFolder()

    private val packageParser = EpubPackageParser()
    private val tocParser = EpubTocParser()

    private fun parseToc(source: dev.reader.formats.ResourceSource) =
        source.use { tocParser.parse(it, packageParser.parse(it)) }

    @Test
    fun `reads an epub3 nav document`() {
        val toc = parseToc(standardEpub(temp.newFile()))

        assertThat(toc).hasSize(2)
        assertThat(toc[0].title).isEqualTo("One")
        assertThat(toc[0].spineIndex).isEqualTo(0)
        assertThat(toc[1].title).isEqualTo("Two")
        assertThat(toc[1].spineIndex).isEqualTo(1)
    }

    @Test
    fun `records nesting depth from the nav document`() {
        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
</package>""")
            entry("OEBPS/nav.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol>
  <li><a href="ch1.xhtml">Part One</a>
    <ol><li><a href="ch2.xhtml">Sub</a></li></ol>
  </li>
</ol></nav></body></html>""")
        }
        val toc = parseToc(source)

        assertThat(toc.map { it.depth }).containsExactly(0, 1).inOrder()
        assertThat(toc.map { it.title }).containsExactly("Part One", "Sub").inOrder()
    }

    @Test
    fun `reads an epub2 ncx when there is no nav document`() {
        val toc = parseToc(ncxEpub(temp.newFile()))

        assertThat(toc.map { it.title }).containsExactly("Chapter One", "Chapter Two").inOrder()
        assertThat(toc.map { it.spineIndex }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `prefers the nav document over the ncx`() {
        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="ch1"/></spine>
</package>""")
            entry("OEBPS/nav.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol><li><a href="ch1.xhtml">From Nav</a></li></ol></nav></body></html>""")
            entry("OEBPS/toc.ncx", """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
  <navPoint><navLabel><text>From NCX</text></navLabel><content src="ch1.xhtml"/></navPoint>
</navMap></ncx>""")
        }
        assertThat(parseToc(source).single().title).isEqualTo("From Nav")
    }

    @Test
    fun `synthesizes a flat toc when the book has neither nav nor ncx`() {
        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
</package>""")
        }
        val toc = parseToc(source)

        assertThat(toc.map { it.title }).containsExactly("1", "2").inOrder()
        assertThat(toc.map { it.spineIndex }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `drops toc entries that point outside the spine`() {
        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""")
            entry("OEBPS/nav.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol>
  <li><a href="ch1.xhtml">Real</a></li>
  <li><a href="nowhere.xhtml">Dangling</a></li>
</ol></nav></body></html>""")
        }
        assertThat(parseToc(source).map { it.title }).containsExactly("Real")
    }

    @Test
    fun `an unlinked heading li still lets its nested children through`() {
        // EPUB 3's nav content model permits an <li> with no direct <a> — just a
        // heading and a nested <ol>. `li.selectFirst("a")` is descendant-scoped and
        // would wrongly reach into the nested <ol>, stealing "Sub" for depth 0 and
        // then emitting it again at depth 1 via recursion, while "Part One" (which
        // never links anywhere, so correctly produces no entry) disappears.
        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch2"/></spine>
</package>""")
            entry("OEBPS/nav.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol>
  <li><span>Part One</span><ol><li><a href="ch2.xhtml">Sub</a></li></ol></li>
</ol></nav></body></html>""")
        }
        val toc = parseToc(source)

        assertThat(toc.map { it.title to it.depth }).containsExactly("Sub" to 1)
    }

    @Test
    fun `an ncx navPoint missing its own label still lets its nested child through`() {
        // Same descendant-scoping bug, latent in the NCX collector: a navPoint with
        // no direct navLabel/content of its own must not steal its nested navPoint's.
        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Old</dc:title></metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="ch1"/></spine>
</package>""")
            entry("OEBPS/toc.ncx", """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
  <navPoint><navPoint><navLabel><text>Sub</text></navLabel><content src="ch1.xhtml"/></navPoint></navPoint>
</navMap></ncx>""")
        }
        val toc = parseToc(source)

        assertThat(toc.map { it.title to it.depth }).containsExactly("Sub" to 1)
    }

    @Test
    fun `an oversized nav document surfaces as EpubException, not a raw IOException`() {
        val chunk = "a".repeat(1024)
        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""")
            entry("OEBPS/nav.xhtml", chunk.repeat(17 * 1024))
        }
        val e = runCatching { parseToc(source) }.exceptionOrNull()

        assertThat(e).isInstanceOf(EpubException.Malformed::class.java)
        assertThat(e).isNotInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun `first-wins when two spine slots share an href`() {
        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1a" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch1b" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1a"/><itemref idref="ch1b"/></spine>
</package>""")
            entry("OEBPS/nav.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol><li><a href="ch1.xhtml">Ch</a></li></ol></nav></body></html>""")
        }
        val toc = parseToc(source)

        // Two spine slots (index 0 and 1) share the href "ch1.xhtml" — reading order
        // says the first occurrence is the right target, not whichever toMap() keeps.
        assertThat(toc.single().spineIndex).isEqualTo(0)
    }

    @Test
    fun `matches a multi-token epub type attribute`() {
        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""")
            entry("OEBPS/nav.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc bodymatter"><ol><li><a href="ch1.xhtml">Ch</a></li></ol></nav></body></html>""")
        }
        assertThat(parseToc(source).single().title).isEqualTo("Ch")
    }

    @Test
    fun `caps recursion depth instead of overflowing the stack on a deeply nested nav`() {
        val depth = 5000
        val open = StringBuilder()
        val close = StringBuilder()
        repeat(depth) { i ->
            open.append("<li><a href=\"ch1.xhtml\">Level$i</a><ol>")
            close.append("</ol></li>")
        }
        val nav = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol>$open$close</ol></nav></body></html>"""

        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""")
            entry("OEBPS/nav.xhtml", nav)
        }

        // Without a depth cap, 5000 nested <ol>s recurses one stack frame per level
        // and overflows the JVM stack. With the cap, parsing completes and no entry
        // past the cap is emitted.
        val toc = parseToc(source)

        assertThat(toc.maxOf { it.depth }).isLessThan(100)
        assertThat(toc.map { it.title }).doesNotContain("Level150")
    }

    @Test
    fun `caps recursion depth instead of overflowing the stack on a deeply nested ncx`() {
        // jsoup's HTML tree builder self-limits nesting (empirically ~253 for the nav
        // case above), but jsoup's XML parser does not — a crafted NCX can reach the
        // full nesting depth of the source document. 5000 nested navPoints recurses
        // one stack frame per level with no cap and reliably overflows the JVM stack.
        val depth = 5000
        val open = StringBuilder()
        val close = StringBuilder()
        repeat(depth) { i ->
            open.append("<navPoint><navLabel><text>Level$i</text></navLabel><content src=\"ch1.xhtml\"/>")
            close.append("</navPoint>")
        }
        val ncx = """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>$open$close</navMap></ncx>"""

        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Old</dc:title></metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="ch1"/></spine>
</package>""")
            entry("OEBPS/toc.ncx", ncx)
        }

        val toc = parseToc(source)

        assertThat(toc.maxOf { it.depth }).isLessThan(100)
        assertThat(toc.map { it.title }).doesNotContain("Level150")
    }

    @Test
    fun `falls through to ncx when the nav document parses but yields no usable entries`() {
        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="ch1"/></spine>
</package>""")
            entry("OEBPS/nav.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol></ol></nav></body></html>""")
            entry("OEBPS/toc.ncx", """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
  <navPoint><navLabel><text>From NCX</text></navLabel><content src="ch1.xhtml"/></navPoint>
</navMap></ncx>""")
        }
        assertThat(parseToc(source).single().title).isEqualTo("From NCX")
    }

    @Test
    fun `toc hrefs with fragments still resolve to their spine chapter`() {
        val source = buildEpub(temp.newFile()) {
            entry("META-INF/container.xml", CONTAINER_XML)
            entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>""")
            entry("OEBPS/nav.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol><li><a href="ch1.xhtml#part2">Part Two</a></li></ol></nav>
</body></html>""")
        }
        assertThat(parseToc(source).single().spineIndex).isEqualTo(0)
    }
}

/** An EPUB 2 book whose TOC lives in an NCX. */
private fun ncxEpub(file: java.io.File) = buildEpub(file) {
    entry("META-INF/container.xml", CONTAINER_XML)
    entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Old</dc:title></metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine>
</package>""")
    entry("OEBPS/toc.ncx", """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
  <navPoint playOrder="1"><navLabel><text>Chapter One</text></navLabel><content src="ch1.xhtml"/></navPoint>
  <navPoint playOrder="2"><navLabel><text>Chapter Two</text></navLabel><content src="ch2.xhtml"/></navPoint>
</navMap></ncx>""")
}
