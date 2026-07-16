package dev.reader.formats.epub

import dev.reader.formats.ZipResourceSource
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TestEpubBuilder {
    private val entries = LinkedHashMap<String, ByteArray>()

    fun entry(path: String, content: String) {
        entries[path] = content.toByteArray(Charsets.UTF_8)
    }

    /** Binary variant, for entries that aren't text — a cover image, first and so far only. */
    fun entry(path: String, bytes: ByteArray) {
        entries[path] = bytes
    }

    fun writeTo(file: File) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
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

/**
 * A well-formed EPUB with [chapterCount] chapters, all listed in the spine — used by
 * cache-capacity tests that need more spine entries than [standardEpub]'s two.
 */
fun multiChapterEpub(file: File, chapterCount: Int): ZipResourceSource = buildEpub(file) {
    entry("META-INF/container.xml", CONTAINER_XML)
    val items = (0 until chapterCount).joinToString("\n") { i ->
        """<item id="ch$i" href="ch$i.xhtml" media-type="application/xhtml+xml"/>"""
    }
    val itemrefs = (0 until chapterCount).joinToString("\n") { i -> """<itemref idref="ch$i"/>""" }
    entry("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Multi</dc:title></metadata>
  <manifest>
$items
  </manifest>
  <spine>
$itemrefs
  </spine>
</package>""")
    for (i in 0 until chapterCount) {
        entry("OEBPS/ch$i.xhtml", "<html><body><p>Chapter $i content.</p></body></html>")
    }
}
