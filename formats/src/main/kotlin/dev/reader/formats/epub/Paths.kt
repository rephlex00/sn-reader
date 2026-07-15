package dev.reader.formats.epub

/**
 * Resolves a manifest/TOC href against the file that declared it, producing a path
 * usable as a zip entry name. Parent traversal is clamped at the archive root — a
 * malicious href must never address anything outside the container.
 */
internal fun resolveHref(basePath: String, href: String): String {
    val withoutFragment = href.substringBefore('#')
    // Decode BEFORE normalizing: this is what stops %2e%2e%2f from bypassing the
    // ".." clamp below — decode first, then walk the resulting segments.
    val decoded = percentDecode(withoutFragment)
    val baseDir = basePath.substringBeforeLast('/', missingDelimiterValue = "")
    val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
    return normalizePath(combined)
}

/**
 * Percent-decodes a URI path segment. Deliberately NOT [java.net.URLDecoder], which
 * implements form encoding: it throws on a stray '%' and turns '+' into a space, so
 * `C++ Primer.xhtml` would resolve to an entry that does not exist. A malformed escape
 * degrades to a literal here — a probably-missing chapter beats a crash.
 */
internal fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = java.io.ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 3 <= s.length) {
            val v = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (v != null) {
                out.write(v)
                i += 3
                continue
            }
        }
        out.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return out.toString(Charsets.UTF_8)
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
