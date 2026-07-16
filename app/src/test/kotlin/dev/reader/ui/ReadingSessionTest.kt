package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.engine.Locator
import dev.reader.engine.ReadingState
import org.junit.Test

/**
 * Pure JVM coverage for [ReadingSession] — no Robolectric, no real [dev.reader.formats.epub.EpubDocument].
 * The document is faked entirely through the lambdas [ReadingSession.resolveStart] takes, so the
 * clamp / out-of-range fallback / empty-chapter fallback / offset-to-page mapping rules are all
 * exercised here rather than in a slow Activity test.
 */
class ReadingSessionTest {

    // A single-chapter book where chapter 0 has pages: the fake offset->page mapping just returns
    // the offset itself, so a test can assert exactly which offset was consulted.
    private val identityOffsetToPage: (Int, Int) -> Int = { _, charOffset -> charOffset }

    // -- resolveStart: mid-chapter restore ------------------------------------------------------

    @Test
    fun `restore maps a mid-chapter char offset to its page index`() {
        val session = ReadingSession()

        val start = session.resolveStart(
            stored = Locator(spineIndex = 2, charOffset = 500),
            spineSize = 5,
            pageCountFor = { 10 }, // every chapter has pages
            offsetToPageIndex = { spineIndex, charOffset ->
                // Fake: page = offset / 100, so offset 500 in chapter 2 maps to page 5.
                assertThat(spineIndex).isEqualTo(2)
                charOffset / 100
            },
            firstNonEmptyFrom = { error("must not skip — chapter 2 has pages") },
        )

        assertThat(start).isEqualTo(ReadingState(spineIndex = 2, pageIndex = 5))
    }

    // -- resolveStart: out-of-range stored spineIndex → clamp to fresh start ---------------------

    @Test
    fun `an out-of-range stored spineIndex falls back to first non-empty from chapter 0`() {
        val session = ReadingSession()
        // The file shrank under us: chapter 7 no longer exists (spineSize 3). Treat as no stored
        // position — a fresh start from chapter 0. Chapter 0 paginates to zero pages here (a cover
        // the parser renders no block for — an SVG/<image> cover — or a chapter that lost its
        // text; an <img> cover now renders and is NOT zero-page), so the skip fires.
        var skippedFrom = -1

        val start = session.resolveStart(
            stored = Locator(spineIndex = 7, charOffset = 900),
            spineSize = 3,
            pageCountFor = { chapter -> if (chapter == 0) 0 else 4 }, // chapter 0 has no pages
            offsetToPageIndex = { _, _ -> error("must not map an offset on the clamp path") },
            firstNonEmptyFrom = { from ->
                skippedFrom = from
                ReadingState(spineIndex = 1, pageIndex = 0)
            },
        )

        assertThat(skippedFrom).isEqualTo(0) // skipped from chapter 0, not the bogus stored 7
        assertThat(start).isEqualTo(ReadingState(spineIndex = 1, pageIndex = 0))
    }

    // -- resolveStart: stored chapter now empty → first non-empty at page 0 ----------------------

    @Test
    fun `a stored chapter that is now empty falls back to first non-empty at page 0`() {
        val session = ReadingSession()
        var skippedFrom = -1

        val start = session.resolveStart(
            stored = Locator(spineIndex = 2, charOffset = 300),
            spineSize = 5,
            pageCountFor = { chapter -> if (chapter == 2) 0 else 4 }, // chapter 2 became empty
            offsetToPageIndex = { _, _ -> error("must not map an offset on the empty-chapter path") },
            firstNonEmptyFrom = { from ->
                skippedFrom = from
                ReadingState(spineIndex = 3, pageIndex = 0)
            },
        )

        assertThat(skippedFrom).isEqualTo(2) // skips forward starting from the stored chapter
        assertThat(start).isEqualTo(ReadingState(spineIndex = 3, pageIndex = 0))
    }

    // -- resolveStart: stored (0,0) with a non-empty chapter 0 → no spurious skip ----------------

    @Test
    fun `stored 0,0 with a non-empty chapter 0 shows 0,0 with no skip`() {
        val session = ReadingSession()

        val start = session.resolveStart(
            stored = Locator(spineIndex = 0, charOffset = 0),
            spineSize = 4,
            pageCountFor = { 6 }, // chapter 0 has pages
            offsetToPageIndex = identityOffsetToPage,
            firstNonEmptyFrom = { error("must not skip — chapter 0 has pages") },
        )

        assertThat(start).isEqualTo(ReadingState(spineIndex = 0, pageIndex = 0))
    }

    // -- resolveStart: null stored (never-opened book) starts at chapter 0 -----------------------

    @Test
    fun `a null stored position starts a fresh read from chapter 0`() {
        val session = ReadingSession()

        val start = session.resolveStart(
            stored = null,
            spineSize = 4,
            pageCountFor = { 6 },
            offsetToPageIndex = { spineIndex, charOffset ->
                assertThat(spineIndex).isEqualTo(0)
                assertThat(charOffset).isEqualTo(0) // no stored offset -> 0
                0
            },
            firstNonEmptyFrom = { error("must not skip — chapter 0 has pages") },
        )

        assertThat(start).isEqualTo(ReadingState(spineIndex = 0, pageIndex = 0))
    }

    // -- resolveStart: whole book empty → returns the start chapter for the caller to reject ------

    @Test
    fun `a book that is empty all the way through returns the start chapter unshown`() {
        val session = ReadingSession()

        val start = session.resolveStart(
            stored = null,
            spineSize = 2,
            pageCountFor = { 0 }, // every chapter empty
            offsetToPageIndex = { _, _ -> error("must not map an offset") },
            firstNonEmptyFrom = { null }, // advance() found nothing non-empty
        )

        // The Activity re-checks this chapter's emptiness and shows "no readable text".
        assertThat(start).isEqualTo(ReadingState(spineIndex = 0, pageIndex = 0))
    }

    // -- storedLocator: a corrupt (negative) row is clamped, never throws ------------------------

    @Test
    fun `storedLocator clamps negative columns instead of throwing`() {
        val session = ReadingSession()

        // Locator's init require(>= 0) would throw on these raw values; storedLocator clamps first.
        assertThat(session.storedLocator(spineIndex = -3, charOffset = -7))
            .isEqualTo(Locator(0, 0))
        assertThat(session.storedLocator(spineIndex = 4, charOffset = -1))
            .isEqualTo(Locator(4, 0))
    }

    // -- recordPageTurn / drainPending: coalescing + one-shot drain ------------------------------

    @Test
    fun `recordPageTurn coalesces to the latest and drainPending is one-shot`() {
        val session = ReadingSession()

        session.recordPageTurn(Locator(0, 10))
        session.recordPageTurn(Locator(1, 20))
        session.recordPageTurn(Locator(2, 30))

        // Three records collapse to only the last — the in-memory debounce.
        assertThat(session.drainPending()).isEqualTo(Locator(2, 30))
        // Draining clears it: a second flush writes nothing.
        assertThat(session.drainPending()).isNull()
    }

    @Test
    fun `drainPending is null before any page turn`() {
        assertThat(ReadingSession().drainPending()).isNull()
    }
}
