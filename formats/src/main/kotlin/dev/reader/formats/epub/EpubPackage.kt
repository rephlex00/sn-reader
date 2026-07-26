package dev.reader.formats.epub

import dev.reader.engine.BookMetadata
import dev.reader.formats.ResourceSource
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

/**
 * `encryption.xml` algorithms that mean "font obfuscation", not DRM. InDesign, Sigil and
 * calibre all emit one of these routinely to obscure embedded font files. This check only
 * looks at the algorithm, not at which entry `<CipherReference URI>` names — it trusts the
 * producer convention that tools emitting these algorithms apply them to font files only.
 * That convention is not enforced: a file that (mis)declared one of these algorithms over
 * an XHTML chapter instead of a font would open "successfully" and render as garbage,
 * since nothing here checks the referenced entry.
 */
private val FONT_OBFUSCATION_ALGORITHMS = setOf(
    "http://www.idpf.org/2008/embedding",
    "http://ns.adobe.com/pdf/enc#RC4",
)

class EpubPackageParser {

    /**
     * Parses [source] into an [EpubPackage].
     *
     * This method does not take ownership of [source]: it never closes it, on any path,
     * including every throw path. The caller opened the source (typically via
     * `ZipResourceSource(file).use { parser.parse(it) }`) and the caller remains
     * responsible for closing it.
     */
    fun parse(source: ResourceSource): EpubPackage {
        if (source.exists(ENCRYPTION_PATH)) {
            checkEncryption(source)
        }

        val containerXml = readTextChecked(source, CONTAINER_PATH)
            ?: throw EpubException.NotAnEpub("No $CONTAINER_PATH — this is not an EPUB.")

        val opfPath = parseContainer(containerXml)
        val opfXml = readTextChecked(source, opfPath)
            ?: throw EpubException.Malformed("Container points at $opfPath, which is missing.")

        val opf = Jsoup.parse(opfXml, "", Parser.xmlParser())
        val manifest = parseManifest(opf, opfPath)
        val spine = parseSpine(opf, manifest)

        if (spine.isEmpty()) {
            throw EpubException.Malformed("The spine is empty — the book has no readable content.")
        }

        val ncxId = opf.firstByLocalName("spine")?.attr("toc")?.takeIf { it.isNotEmpty() }

        return EpubPackage(
            opfPath = opfPath,
            metadata = parseMetadata(opf, manifest),
            manifest = manifest,
            spine = spine,
            navItemId = manifest.values.firstOrNull { "nav" in it.properties }?.id,
            // Symmetric with navItemId: a dangling toc idref is possible in the wild,
            // same as a dangling spine idref, so validate it against the manifest too.
            ncxItemId = ncxId?.takeIf { manifest.containsKey(it) },
        )
    }

    /**
     * Fails closed on anything that isn't recognisably an `encryption.xml`: unparseable
     * bytes, the wrong file entirely, or a document whose *root* element isn't
     * `<encryption>` — that is exactly the case where refusing to guess is the safe
     * choice, same as an algorithm outside the known-benign font-obfuscation set.
     *
     * A real `<encryption>` root that declares neither an `EncryptedData` nor an
     * `EncryptionMethod` anywhere beneath it is different, and opens. Some producers
     * emit a vestigial `encryption.xml` that declares nothing at all; that describes a
     * book with zero encrypted entries, not DRM. Everything else fails closed: any
     * declared algorithm outside the benign set, an algorithm that didn't survive
     * attribute lookup (blank, missing, or namespace-prefixed), or an `EncryptedData`/
     * `CipherReference` with no `EncryptionMethod` at all to vouch for it —
     * `EncryptionMethod` is optional per the XML Encryption spec, so its absence doesn't
     * mean the entry isn't encrypted. See [extractEncryptionAlgorithms] for how the
     * `null` vs. list distinction carries this.
     */
    private fun checkEncryption(source: ResourceSource) {
        val algorithms = readTextChecked(source, ENCRYPTION_PATH)?.let { extractEncryptionAlgorithms(it) }
            ?: throw EpubException.DrmProtected("This book is DRM-protected and cannot be opened.")
        if (algorithms.any { it !in FONT_OBFUSCATION_ALGORITHMS }) {
            throw EpubException.DrmProtected("This book is DRM-protected and cannot be opened.")
        }
    }

    /**
     * Returns `null` when [xml] isn't recognisably an `encryption.xml` document —
     * unparseable, or its root element (Jsoup's [Document.children] under
     * `Parser.xmlParser()`, i.e. top-level elements only; an `<encryption>` nested
     * inside some other wrapper element doesn't count) isn't `encryption` by local
     * name — and a (possibly empty) list of `Algorithm` values otherwise. The `null`
     * vs. empty-list distinction is load-bearing: see [checkEncryption].
     *
     * The returned list is never filtered down to non-empty values. An `EncryptionMethod`
     * with a blank, missing, or namespace-prefixed `Algorithm` attribute still
     * contributes `""`, which is not in [FONT_OBFUSCATION_ALGORITHMS] and so still fails
     * closed — dropping such entries previously let an unstated algorithm read as "no
     * evidence", which [checkEncryption] then (wrongly) treated as benign.
     *
     * Likewise, an `EncryptedData`/`CipherReference` with no `EncryptionMethod` anywhere
     * under the root is not "no evidence" either — it contributes a synthetic `""` too,
     * so it fails closed like an unstated algorithm. A genuinely empty `<encryption/>`,
     * with no `EncryptedData` and no `EncryptionMethod` at all, still yields an empty
     * list and opens.
     *
     * All local-name comparisons (`encryption`, `EncryptionMethod`, `EncryptedData`,
     * `CipherReference`) are case-insensitive: XML is case-sensitive and Jsoup's
     * `xmlParser` preserves case, but a producer's casing choice shouldn't decide
     * whether this check applies.
     */
    private fun extractEncryptionAlgorithms(xml: String): List<String>? = runCatching {
        val root = Jsoup.parse(xml, "", Parser.xmlParser()).children()
            .firstOrNull { it.localTag().equals("encryption", ignoreCase = true) }
            ?: return@runCatching null
        val elements = root.allElements
        val algorithms = elements
            .filter { it.localTag().equals("EncryptionMethod", ignoreCase = true) }
            .map { it.attr("Algorithm") }
        val hasUnvouchedEncryptedEntry = algorithms.isEmpty() && elements.any {
            it.localTag().equals("EncryptedData", ignoreCase = true) ||
                it.localTag().equals("CipherReference", ignoreCase = true)
        }
        if (hasUnvouchedEncryptedEntry) listOf("") else algorithms
    }.getOrNull()

    private fun parseContainer(xml: String): String {
        val rootfile = Jsoup.parse(xml, "", Parser.xmlParser()).selectFirst("rootfile")
            ?: throw EpubException.Malformed("$CONTAINER_PATH has no <rootfile>.")
        val fullPath = rootfile.attr("full-path")
        if (fullPath.isEmpty()) {
            throw EpubException.Malformed("<rootfile> has no full-path attribute.")
        }
        return normalizePath(percentDecode(fullPath))
    }

    private fun parseManifest(opf: Document, opfPath: String): Map<String, ManifestItem> =
        opf.firstByLocalName("manifest")?.childrenByLocalName("item").orEmpty().mapNotNull { el ->
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
        opf.firstByLocalName("spine")?.childrenByLocalName("itemref").orEmpty()
            .map { it.attr("idref") }
            // A dangling idref is common in the wild; skip it rather than reject the book.
            .filter { it.isNotEmpty() && manifest.containsKey(it) }

    private fun parseMetadata(
        opf: Document,
        manifest: Map<String, ManifestItem>,
    ): BookMetadata {
        val metadata = opf.firstByLocalName("metadata")
        fun dcText(localName: String): String? = metadata?.childrenByLocalName(localName)
            ?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
        return BookMetadata(
            title = dcText("title") ?: "Untitled",
            author = dcText("creator"),
            language = dcText("language"),
            coverHref = findCover(opf, manifest),
        )
    }

    private fun findCover(opf: Document, manifest: Map<String, ManifestItem>): String? {
        // EPUB 3: a manifest item carrying the cover-image property.
        manifest.values.firstOrNull { "cover-image" in it.properties }?.let { return it.href }

        // EPUB 2: <meta name="cover" content="<manifest id>"/>.
        val coverId = opf.firstByLocalName("metadata")?.childrenByLocalName("meta")
            ?.firstOrNull { it.attr("name") == "cover" }
            ?.attr("content")
            ?: return null
        return manifest[coverId]?.href
    }
}

/**
 * OPF elements matched by local name, ignoring any namespace prefix: real-world producers
 * emit both `<manifest><item>` and `<opf:manifest><opf:item>`, and a prefix-blind match
 * is exactly how [EpubPackageParser.extractEncryptionAlgorithms] and the NCX parser
 * already handle the same variance. Attribute names (`id`, `href`, `idref`, `toc`, ...)
 * are conventionally unprefixed and are matched as-is.
 */
private fun Element.localTag(): String = tagName().substringAfter(':')

private fun Document.firstByLocalName(name: String): Element? =
    allElements.firstOrNull { it.localTag() == name }

private fun Element.childrenByLocalName(name: String): List<Element> =
    children().filter { it.localTag() == name }
