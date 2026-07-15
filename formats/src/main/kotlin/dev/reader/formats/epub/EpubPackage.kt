package dev.reader.formats.epub

import dev.reader.engine.BookMetadata
import dev.reader.formats.ResourceSource
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

data class ManifestItem(
    val id: String,
    /** Archive path, already resolved against the OPF directory. */
    val href: String,
    val mediaType: String,
    val properties: Set<String> = emptySet(),
)

data class EpubPackage(
    val opfPath: String,
    val metadata: BookMetadata,
    val manifest: Map<String, ManifestItem>,
    /** Manifest ids in reading order. */
    val spine: List<String>,
    val navItemId: String?,
    val ncxItemId: String?,
)

private const val CONTAINER_PATH = "META-INF/container.xml"
private const val ENCRYPTION_PATH = "META-INF/encryption.xml"

class EpubPackageParser {

    fun parse(source: ResourceSource): EpubPackage {
        if (source.exists(ENCRYPTION_PATH)) {
            throw EpubException.DrmProtected("This book is DRM-protected and cannot be opened.")
        }

        val containerXml = source.readText(CONTAINER_PATH)
            ?: throw EpubException.NotAnEpub("No $CONTAINER_PATH — this is not an EPUB.")

        val opfPath = parseContainer(containerXml)
        val opfXml = source.readText(opfPath)
            ?: throw EpubException.Malformed("Container points at $opfPath, which is missing.")

        val opf = Jsoup.parse(opfXml, "", Parser.xmlParser())
        val manifest = parseManifest(opf, opfPath)
        val spine = parseSpine(opf, manifest)

        if (spine.isEmpty()) {
            throw EpubException.Malformed("The spine is empty — the book has no readable content.")
        }

        return EpubPackage(
            opfPath = opfPath,
            metadata = parseMetadata(opf, manifest, opfPath),
            manifest = manifest,
            spine = spine,
            navItemId = manifest.values.firstOrNull { "nav" in it.properties }?.id,
            ncxItemId = opf.selectFirst("spine")?.attr("toc")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseContainer(xml: String): String {
        val rootfile = Jsoup.parse(xml, "", Parser.xmlParser()).selectFirst("rootfile")
            ?: throw EpubException.Malformed("$CONTAINER_PATH has no <rootfile>.")
        val fullPath = rootfile.attr("full-path")
        if (fullPath.isEmpty()) {
            throw EpubException.Malformed("<rootfile> has no full-path attribute.")
        }
        return normalizePath(fullPath)
    }

    private fun parseManifest(opf: Document, opfPath: String): Map<String, ManifestItem> =
        opf.select("manifest > item").mapNotNull { el ->
            val id = el.attr("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val href = el.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            id to ManifestItem(
                id = id,
                href = resolveHref(opfPath, href),
                mediaType = el.attr("media-type"),
                properties = el.attr("properties").split(' ').filter { it.isNotEmpty() }.toSet(),
            )
        }.toMap()

    private fun parseSpine(opf: Document, manifest: Map<String, ManifestItem>): List<String> =
        opf.select("spine > itemref")
            .map { it.attr("idref") }
            // A dangling idref is common in the wild; skip it rather than reject the book.
            .filter { it.isNotEmpty() && manifest.containsKey(it) }

    private fun parseMetadata(
        opf: Document,
        manifest: Map<String, ManifestItem>,
        opfPath: String,
    ): BookMetadata = BookMetadata(
        title = opf.selectFirst("metadata > dc|title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Untitled",
        author = opf.selectFirst("metadata > dc|creator")?.text()?.trim()?.takeIf { it.isNotEmpty() },
        language = opf.selectFirst("metadata > dc|language")?.text()?.trim()?.takeIf { it.isNotEmpty() },
        coverHref = findCover(opf, manifest, opfPath),
    )

    private fun findCover(
        opf: Document,
        manifest: Map<String, ManifestItem>,
        opfPath: String,
    ): String? {
        // EPUB 3: a manifest item carrying the cover-image property.
        manifest.values.firstOrNull { "cover-image" in it.properties }?.let { return it.href }

        // EPUB 2: <meta name="cover" content="<manifest id>"/>.
        val coverId = opf.select("metadata > meta")
            .firstOrNull { it.attr("name") == "cover" }
            ?.attr("content")
            ?: return null
        return manifest[coverId]?.href
    }
}
