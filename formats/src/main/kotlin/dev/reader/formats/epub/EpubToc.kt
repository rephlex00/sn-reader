package dev.reader.formats.epub

import dev.reader.engine.TocEntry
import dev.reader.formats.ResourceSource
import java.io.IOException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Builds the table of contents, preferring an EPUB 3 nav document and falling back to
 * an EPUB 2 NCX. A book with neither gets a flat TOC synthesized from its spine, so the
 * chapter list is never empty.
 */
class EpubTocParser {

    fun parse(source: ResourceSource, pkg: EpubPackage): List<TocEntry> {
        // Spine paths are the target space: a TOC entry only counts if it lands in the spine.
        val spinePaths: Map<String, Int> = pkg.spine
            .withIndex()
            .mapNotNull { (index, id) -> pkg.manifest[id]?.let { it.href to index } }
            .toMap()

        parseNav(source, pkg, spinePaths)?.takeIf { it.isNotEmpty() }?.let { return it }
        parseNcx(source, pkg, spinePaths)?.takeIf { it.isNotEmpty() }?.let { return it }
        return synthesize(pkg)
    }

    private fun parseNav(
        source: ResourceSource,
        pkg: EpubPackage,
        spinePaths: Map<String, Int>,
    ): List<TocEntry>? {
        val navItem = pkg.navItemId?.let { pkg.manifest[it] } ?: return null
        val xml = readTextChecked(source, navItem.href) ?: return null
        val nav = Jsoup.parse(xml, "", Parser.htmlParser())
            .select("nav")
            .firstOrNull { it.attr("epub:type") == "toc" || it.attr("type") == "toc" }
            ?: return null

        val out = mutableListOf<TocEntry>()
        nav.selectFirst("ol")?.let { collectNav(it, navItem.href, spinePaths, depth = 0, out = out) }
        return out
    }

    private fun collectNav(
        ol: Element,
        basePath: String,
        spinePaths: Map<String, Int>,
        depth: Int,
        out: MutableList<TocEntry>,
    ) {
        for (li in ol.children().filter { it.tagName() == "li" }) {
            li.selectFirst("a")?.let { anchor ->
                val target = resolveHref(basePath, anchor.attr("href"))
                val spineIndex = spinePaths[target]
                val title = anchor.text().trim()
                if (spineIndex != null && title.isNotEmpty()) {
                    out += TocEntry(title = title, spineIndex = spineIndex, depth = depth)
                }
            }
            li.children().filter { it.tagName() == "ol" }.forEach {
                collectNav(it, basePath, spinePaths, depth + 1, out)
            }
        }
    }

    private fun parseNcx(
        source: ResourceSource,
        pkg: EpubPackage,
        spinePaths: Map<String, Int>,
    ): List<TocEntry>? {
        val ncxItem = pkg.ncxItemId?.let { pkg.manifest[it] } ?: return null
        val xml = readTextChecked(source, ncxItem.href) ?: return null
        val navMap = Jsoup.parse(xml, "", Parser.xmlParser()).selectFirst("navMap") ?: return null

        val out = mutableListOf<TocEntry>()
        collectNcx(navMap, ncxItem.href, spinePaths, depth = 0, out = out)
        return out
    }

    private fun collectNcx(
        parent: Element,
        basePath: String,
        spinePaths: Map<String, Int>,
        depth: Int,
        out: MutableList<TocEntry>,
    ) {
        for (point in parent.children().filter { it.tagName() == "navPoint" }) {
            val title = point.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
            val src = point.selectFirst("content")?.attr("src").orEmpty()
            val spineIndex = spinePaths[resolveHref(basePath, src)]
            if (spineIndex != null && title.isNotEmpty()) {
                out += TocEntry(title = title, spineIndex = spineIndex, depth = depth)
            }
            collectNcx(point, basePath, spinePaths, depth + 1, out)
        }
    }

    private fun synthesize(pkg: EpubPackage): List<TocEntry> =
        pkg.spine.indices.map { TocEntry(title = "${it + 1}", spineIndex = it, depth = 0) }

    /**
     * [ResourceSource.readText] throws a raw, format-neutral [IOException] when an entry
     * trips the size cap (a zip-bombed nav/NCX document). A nav or NCX document is just as
     * attacker-controlled as the OPF Task 4 already guards this way, so the same translation
     * applies here: without it, an oversized TOC document would crash the reader instead of
     * surfacing as a typed, catchable failure. Mirrors `EpubPackageParser.readTextChecked`.
     */
    private fun readTextChecked(source: ResourceSource, path: String): String? =
        try {
            source.readText(path)
        } catch (e: IOException) {
            throw EpubException.Malformed("Failed to read \"$path\": ${e.message}")
        }
}
