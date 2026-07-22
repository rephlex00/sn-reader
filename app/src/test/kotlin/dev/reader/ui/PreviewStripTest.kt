package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.engine.RenderConfig
import org.junit.Test

class PreviewStripTest {

    private fun config(textSizePx: Float = 40f) = RenderConfig(
        fontFamily = "literata", textSizePx = textSizePx, lineSpacingMultiplier = 1.4f,
        marginPx = 72, justified = true, hyphenated = true,
        viewportWidthPx = 1404, viewportHeightPx = 1872,
    )

    @Test
    fun `sample plan covers every chapter's first page`() {
        val plan = samplePlan(pageCounts = listOf(1, 12, 30, 0, 5))
        // Every non-empty chapter contributes its page 0; empty chapters contribute nothing.
        assertThat(plan).containsAtLeast(0 to 0, 1 to 0, 2 to 0, 4 to 0)
        assertThat(plan.none { it.first == 3 }).isTrue()
    }

    @Test
    fun `long chapters get evenly spaced fills, short ones only their opening`() {
        val plan = samplePlan(pageCounts = listOf(2, 40))
        assertThat(plan.count { it.first == 0 }).isEqualTo(1)
        val ch1 = plan.filter { it.first == 1 }.map { it.second }
        assertThat(ch1.size).isGreaterThan(2)
        assertThat(ch1).contains(0)
        assertThat(ch1).isInOrder()
        assertThat(ch1.all { it < 40 }).isTrue()
    }

    @Test
    fun `sample plan respects the cap and stays deduplicated and ordered`() {
        val plan = samplePlan(pageCounts = List(300) { 10 }, cap = 120)
        assertThat(plan.size).isAtMost(300) // every chapter still gets its opening even past cap
        assertThat(plan).isInOrder(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        assertThat(plan.toSet().size).isEqualTo(plan.size)
    }

    @Test
    fun `a many-chapter book stays within the cap`() {
        // 91 chapters, 4-9 pages each — a realistic novel. Openings (91) plus fills must not exceed cap.
        val counts = List(91) { 4 + (it % 6) }
        val plan = samplePlan(counts, cap = 120)
        assertThat(plan.size).isAtMost(120)
        // Every chapter still gets its opening.
        assertThat(counts.indices.all { ch -> plan.any { it == ch to 0 } }).isTrue()
    }

    @Test
    fun `config hash changes with any rendering field and is stable otherwise`() {
        assertThat(configHash(config())).isEqualTo(configHash(config()))
        assertThat(configHash(config(textSizePx = 42f))).isNotEqualTo(configHash(config()))
    }

    @Test
    fun `index survives a serialize-parse round trip`() {
        val index = StripIndex(
            configHash = "abc", bookSizeBytes = 123L, bookModifiedAtMs = 456L, totalPages = 50,
            entries = listOf(StripEntry(0f, 0, 0, "000.webp"), StripEntry(0.5f, 3, 2, "001.webp")),
        )
        assertThat(parseStripIndex(index.serialize())).isEqualTo(index)
    }

    @Test
    fun `parse rejects garbage rather than throwing`() {
        assertThat(parseStripIndex("")).isNull()
        assertThat(parseStripIndex("not an index")).isNull()
        assertThat(parseStripIndex("v1\nabc 1 2")).isNull()
    }

    @Test
    fun `nearest entry is by fraction distance with clamped ends`() {
        val entries = listOf(
            StripEntry(0.0f, 0, 0, "a"), StripEntry(0.4f, 1, 0, "b"), StripEntry(0.9f, 2, 0, "c"),
        )
        assertThat(nearestEntry(entries, -1f)!!.fileName).isEqualTo("a")
        assertThat(nearestEntry(entries, 0.19f)!!.fileName).isEqualTo("a")
        assertThat(nearestEntry(entries, 0.21f)!!.fileName).isEqualTo("b")
        assertThat(nearestEntry(entries, 2f)!!.fileName).isEqualTo("c")
        assertThat(nearestEntry(emptyList(), 0.5f)).isNull()
    }

    @Test
    fun `entryForChapterOpening finds the page-0 entry for a chapter`() {
        val entries = listOf(
            StripEntry(0.0f, 0, 0, "a"), StripEntry(0.1f, 0, 4, "b"),
            StripEntry(0.4f, 2, 0, "c"), StripEntry(0.6f, 2, 3, "d"),
        )
        assertThat(entryForChapterOpening(entries, 0)!!.fileName).isEqualTo("a")
        assertThat(entryForChapterOpening(entries, 2)!!.fileName).isEqualTo("c")
        assertThat(entryForChapterOpening(entries, 1)).isNull() // chapter 1 has no sampled pages
    }

    @Test
    fun `generatedChaptersOf is the distinct spine indices present`() {
        val index = StripIndex("h", 1L, 2L, 10, listOf(
            StripEntry(0f, 0, 0, "a"), StripEntry(0.2f, 0, 3, "b"), StripEntry(0.5f, 3, 0, "c"),
        ))
        assertThat(generatedChaptersOf(index)).containsExactly(0, 3)
    }
}
