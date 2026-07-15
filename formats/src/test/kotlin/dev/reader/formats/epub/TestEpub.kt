package dev.reader.formats.epub

import dev.reader.formats.ZipResourceSource
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TestEpubBuilder {
    private val entries = LinkedHashMap<String, String>()

    fun entry(path: String, content: String) {
        entries[path] = content
    }

    fun writeTo(file: File) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}

fun buildEpub(file: File, block: TestEpubBuilder.() -> Unit): ZipResourceSource {
    TestEpubBuilder().apply(block).writeTo(file)
    return ZipResourceSource(file)
}

const val CONTAINER_XML = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

/** A well-formed EPUB 3 with two chapters, a nav doc and a cover image. */
fun standardEpub(file: File): ZipResourceSource = buildEpub(file) {
    entry("mimetype", "application/epub+zip")
    entry("META-INF/container.xml", CONTAINER_XML)
    entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>The Test Book</dc:title>
    <dc:creator>A. Author</dc:creator>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cover" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>""")
    entry("OEBPS/text/ch1.xhtml", "<html><body><h1>One</h1><p>First chapter.</p></body></html>")
    entry("OEBPS/text/ch2.xhtml", "<html><body><h1>Two</h1><p>Second chapter.</p></body></html>")
    entry("OEBPS/nav.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol>
  <li><a href="text/ch1.xhtml">One</a></li>
  <li><a href="text/ch2.xhtml">Two</a></li>
</ol></nav></body></html>""")
    entry("OEBPS/images/cover.jpg", "not-really-a-jpeg")
}
