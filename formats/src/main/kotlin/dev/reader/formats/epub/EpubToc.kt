package dev.reader.formats.epub

import dev.reader.engine.TocEntry
import dev.reader.formats.ResourceSource
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * No real TOC nests more than a handful of levels. This bounds recursion in
 * [collectNav]/[collectNcx] so a crafted, deeply-nested nav or NCX document degrades to
 * a truncated TOC rather than a [StackOverflowError] — an [Error], not an [Exception],
 * which the documented `catch (e: EpubException)` contract cannot hold.
 */
private const val MAX_TOC_DEPTH = 100

/**
 * Builds the table of contents, preferring an EPUB 3 nav document and falling back to
 * an EPUB 2 NCX. A book with neither gets a flat TOC synthesized from its spine, so the
 * chapter list is never empty.
 */
class EpubTocParser {

    fun parse(source: ResourceSource, pkg: EpubPackage): List<TocEntry> {
        // Spine paths are the target space: a TOC entry only counts if it lands in the
        // spine. First-wins: if two spine slots share an href, the earlier (correct
        // reading-order) occurrence must win, not whichever `toMap()` happens to keep.
        val spinePaths: Map<String, Int> = buildMap {
            pkg.spine.withIndex().forEach { (index, id) ->
                pkg.manifest[id]?.let { item -> putIfAbsent(item.href, index) }
            }
        }

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
            .firstOrNull { it.isTocNav() }
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
        if (depth >= MAX_TOC_DEPTH) return
        for (li in ol.children().filter { it.tagName() == "li" }) {
            // Direct-child lookup, not `selectFirst("a")`: EPUB 3's nav content model
            // permits an <li> with no direct anchor — just a heading and a nested
            // <ol> — and the descendant-scoped selectFirst would wrongly reach into
            // that nested <ol> and steal the child's anchor for this depth.
            li.children().firstOrNull { it.tagName() == "a" }?.let { anchor ->
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
        if (depth >= MAX_TOC_DEPTH) return
        for (point in parent.children().filter { it.tagName() == "navPoint" }) {
            // Direct-child lookups, not `selectFirst("navLabel > text")` /
            // `selectFirst("content")`: both are descendant-scoped and would wrongly
            // reach into a nested navPoint's label/content when this navPoint has
            // neither of its own.
            val navLabel = point.children().firstOrNull { it.tagName() == "navLabel" }
            val title = navLabel?.children()?.firstOrNull { it.tagName() == "text" }
                ?.text()?.trim().orEmpty()
            val src = point.children().firstOrNull { it.tagName() == "content" }
                ?.attr("src").orEmpty()
            val spineIndex = spinePaths[resolveHref(basePath, src)]
            if (spineIndex != null && title.isNotEmpty()) {
                out += TocEntry(title = title, spineIndex = spineIndex, depth = depth)
            }
            collectNcx(point, basePath, spinePaths, depth + 1, out)
        }
    }

    private fun synthesize(pkg: EpubPackage): List<TocEntry> =
        pkg.spine.indices.map { TocEntry(title = "${it + 1}", spineIndex = it, depth = 0) }
}

/**
 * `epub:type` is a space-separated token list (e.g. `epub:type="toc bodymatter"`), not
 * a single value — a plain string-equality check silently fails to match a spec-legal
 * multi-token nav and degrades the book to the NCX fallback or flat synthesis.
 */
private fun Element.isTocNav(): Boolean =
    "toc" in attr("epub:type").split(Regex("\\s+")).filter { it.isNotEmpty() } || attr("type") == "toc"
