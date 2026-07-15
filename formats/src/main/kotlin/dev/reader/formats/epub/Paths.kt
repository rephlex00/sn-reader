package dev.reader.formats.epub

import java.net.URLDecoder

/**
 * Resolves a manifest/TOC href against the file that declared it, producing a path
 * usable as a zip entry name. Parent traversal is clamped at the archive root — a
 * malicious href must never address anything outside the container.
 */
internal fun resolveHref(basePath: String, href: String): String {
    val withoutFragment = href.substringBefore('#')
    val decoded = URLDecoder.decode(withoutFragment, "UTF-8")
    val baseDir = basePath.substringBeforeLast('/', missingDelimiterValue = "")
    val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
    return normalizePath(combined)
}

internal fun normalizePath(path: String): String {
    val parts = ArrayDeque<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(segment)
        }
    }
    return parts.joinToString("/")
}
