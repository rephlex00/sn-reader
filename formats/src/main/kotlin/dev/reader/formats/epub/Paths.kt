package dev.reader.formats.epub

import dev.reader.formats.ResourceSource
import java.io.IOException

/**
 * [ResourceSource.readText] throws a raw, format-neutral [IOException] when an entry
 * trips the size cap (a zip-bombed container/OPF/nav/NCX/etc. document). `EpubException`
 * does not extend `IOException`, so left uncaught that would escape a `parse` call and
 * break the documented `catch (e: EpubException)` contract. This is the format layer's
 * job to translate — [ResourceSource] itself must stay ignorant of the EPUB exception
 * hierarchy. Shared here (rather than duplicated per caller) because every reader of an
 * untrusted zip entry in this package needs the identical translation.
 */
internal fun readTextChecked(source: ResourceSource, path: String): String? =
    try {
        source.readText(path)
    } catch (e: IOException) {
        throw EpubException.Malformed("Failed to read \"$path\": ${e.message}")
    }

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
        if (isEscapeAt(s, i)) {
            val v = s.substring(i + 1, i + 3).toInt(16)
            out.write(v)
            i += 3
            continue
        }
        // Batch a run of consecutive literal (non-escape) characters and encode it as
        // one unit, rather than converting one UTF-16 code unit at a time: a surrogate
        // pair (an astral-plane character, e.g. an emoji) is two adjacent chars with
        // nothing that could split them, so the pair always survives intact here — a
        // lone surrogate encoded to UTF-8 by itself is invalid and becomes '?'.
        val start = i
        do {
            i++
        } while (i < s.length && !isEscapeAt(s, i))
        out.write(s.substring(start, i).toByteArray(Charsets.UTF_8))
    }
    return out.toString(Charsets.UTF_8)
}

/**
 * True if `s[i]` begins a valid two-hex-digit percent escape. Deliberately stricter
 * than `toIntOrNull(16)`, which accepts a leading '+'/'-' sign — without this check
 * `%-1` and `%+a` would parse as valid escapes instead of degrading to literals as
 * documented above.
 */
private fun isEscapeAt(s: String, i: Int): Boolean =
    s[i] == '%' && i + 3 <= s.length && isHexDigit(s[i + 1]) && isHexDigit(s[i + 2])

private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

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
