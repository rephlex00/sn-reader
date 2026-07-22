package dev.reader.ui

import dev.reader.engine.RenderConfig
import java.security.MessageDigest

/**
 * The pure heart of the scrubbing preview: which pages get thumbnails, how a strip is keyed to a
 * typography config, the on-disk index format, and the lookup a drag performs. Everything here is
 * Android-free and JVM-tested; PreviewStripStore owns the impure half (bitmaps, files, coroutines).
 *
 * Why sampled thumbnails at all: on a ~1240px track a 549-page book gives 2.3px per page against a
 * ~30px fingertip, so a drag can only target roughly every 10th page — a strip of ~100 samples
 * loses nothing a finger could actually have selected (measured on the Nomad, 2026-07-21).
 */

/** One thumbnail: where it sits in the book and which file holds it. */
data class StripEntry(
    val fraction: Float,
    val spineIndex: Int,
    val pageIndex: Int,
    val fileName: String,
)

/** The strip's manifest. [configHash]/[bookSizeBytes]/[bookModifiedAtMs] are the validity key. */
data class StripIndex(
    val configHash: String,
    val bookSizeBytes: Long,
    val bookModifiedAtMs: Long,
    val totalPages: Int,
    val entries: List<StripEntry>,
)

/**
 * Which (spineIndex, pageIndex) pairs get thumbnails: every non-empty chapter's first page, plus
 * evenly spaced fills inside long chapters, allocated proportionally to page count up to [cap]
 * total. Fills strictly respect the cap: each chapter's share is floored (no per-chapter minimum),
 * so the sum of fills never exceeds the remaining budget after openings — short and medium
 * chapters whose proportional share rounds to 0 get only their opening, and interior fills
 * concentrate in the longest chapters, where sampling within the chapter actually matters.
 * Chapter openings themselves are never sacrificed to the cap — recognising "which chapter is
 * this" is the preview's first job — so a pathological 300-chapter book may exceed [cap] by its
 * openings alone, which is fine: openings are the cheapest thumbnails to choose and the dearest
 * to lose.
 */
fun samplePlan(pageCounts: List<Int>, cap: Int = 120): List<Pair<Int, Int>> {
    val openings = pageCounts.indices.filter { pageCounts[it] > 0 }.map { it to 0 }
    val fillBudget = (cap - openings.size).coerceAtLeast(0)
    val totalPages = pageCounts.sumOf { it.coerceAtLeast(0) }
    if (fillBudget == 0 || totalPages == 0) return openings

    val fills = mutableListOf<Pair<Int, Int>>()
    for ((spine, count) in pageCounts.withIndex()) {
        // A 2-page chapter has exactly one non-opening slot (page 1); coercing any fill into
        // that single slot would just re-add page 1 next to the opening's page 0, not give a
        // meaningfully "evenly spaced" sample. Fills only earn their keep past 2 pages.
        if (count <= 2) continue
        // Proportional share of the fill budget, floored — no per-chapter minimum. Because
        // sum(floor(count/total * budget)) <= budget, this keeps total fills within fillBudget;
        // medium chapters whose share rounds to 0 get only their opening, so interior fills
        // concentrate in the longest chapters, where in-chapter sampling actually matters.
        val share = ((count.toFloat() / totalPages) * fillBudget).toInt()
        val step = count.toFloat() / (share + 1)
        for (k in 1..share) {
            val page = (step * k).toInt().coerceIn(1, count - 1)
            fills += spine to page
        }
    }
    return (openings + fills).distinct().sortedWith(compareBy({ it.first }, { it.second }))
}

/**
 * SHA-256 over every RenderConfig field, via the data class's toString. Deliberately brittle: ANY
 * field change — including a future field this code has never heard of — changes the hash and
 * invalidates the strip, which is exactly the safe failure mode (a stale strip showing wrong
 * pagination is the bug; a spurious regeneration is just a few background seconds).
 */
fun configHash(config: RenderConfig): String = sha256Hex(config.toString())

private fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) { digest.forEach { append("%02x".format(it)) } }
}

private const val INDEX_VERSION = "strip-v1"

/** Flat text, no JSON dependency: a version line, a header line, then one line per entry. */
fun StripIndex.serialize(): String = buildString {
    appendLine(INDEX_VERSION)
    appendLine("$configHash $bookSizeBytes $bookModifiedAtMs $totalPages")
    for (e in entries) appendLine("${e.fraction} ${e.spineIndex} ${e.pageIndex} ${e.fileName}")
}

/** Null for anything malformed — a partial write, a future version, garbage. Never throws. */
fun parseStripIndex(text: String): StripIndex? {
    val lines = text.trim().lines()
    if (lines.size < 2 || lines[0] != INDEX_VERSION) return null
    val header = lines[1].split(" ")
    if (header.size != 4) return null
    val entries = lines.drop(2).map { line ->
        val p = line.split(" ")
        if (p.size != 4) return null
        StripEntry(
            fraction = p[0].toFloatOrNull() ?: return null,
            spineIndex = p[1].toIntOrNull() ?: return null,
            pageIndex = p[2].toIntOrNull() ?: return null,
            fileName = p[3],
        )
    }
    return StripIndex(
        configHash = header[0],
        bookSizeBytes = header[1].toLongOrNull() ?: return null,
        bookModifiedAtMs = header[2].toLongOrNull() ?: return null,
        totalPages = header[3].toIntOrNull() ?: return null,
        entries = entries,
    )
}

/** The drag's lookup: the entry whose fraction is closest to [fraction]. Entries are sorted by
 *  fraction (the generator writes them that way); binary search, ties go to the earlier entry. */
fun nearestEntry(entries: List<StripEntry>, fraction: Float): StripEntry? {
    if (entries.isEmpty()) return null
    val f = fraction.coerceIn(0f, 1f)
    var lo = 0
    var hi = entries.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (entries[mid].fraction < f) lo = mid + 1 else hi = mid
    }
    // lo is the first entry >= f; the nearest is either it or its predecessor.
    if (lo > 0 && f - entries[lo - 1].fraction <= entries[lo].fraction - f) return entries[lo - 1]
    return entries[lo]
}
