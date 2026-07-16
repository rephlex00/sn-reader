package dev.reader.ui

import dev.reader.engine.Locator
import dev.reader.engine.ReadingState

/**
 * The pure position-memory logic for [ReaderActivity], deliberately free of Android and of
 * [dev.reader.formats.epub.EpubDocument] so [ReadingSessionTest] runs as fast JVM JUnit rather than
 * under Robolectric. The Activity owns everything impure — the DAO calls, the clock, the write
 * scope, and every real `chapter()` pagination — and feeds this class the pieces it needs as
 * lambdas.
 *
 * Two responsibilities:
 *  - **Restore on open** ([resolveStart]): map a stored [Locator] back to a page, surviving a spine
 *    that shrank under us, a chapter that went empty, and a corrupt (negative) row.
 *  - **Debounce page turns without a timer** ([recordPageTurn] / [drainPending]): coalesce every
 *    turn into a single in-memory field and flush it once, when the reader is left. See
 *    [recordPageTurn]'s comment for why this is the only debounce mechanism that keeps the idle
 *    promise (0% CPU at rest — no timer, no postDelayed, no periodic write).
 */
class ReadingSession {

    /**
     * The latest position the user has turned to but not yet written to disk, or null if there is
     * nothing pending. A single field, overwritten on every turn — that overwrite *is* the debounce:
     * a hundred taps cost a hundred cheap memory writes and exactly one DB UPDATE at flush time. No
     * timer schedules that flush (that would wake the process and break the idle promise); the
     * Activity drains it in `onStop`, when the user is already leaving.
     */
    private var pending: Locator? = null

    /** Records a page turn, overwriting any earlier pending value (coalescing). Pure memory, no I/O. */
    fun recordPageTurn(locator: Locator) {
        pending = locator
    }

    /**
     * Returns the pending position and clears it. Called once from `onStop`: the drain is one-shot
     * so a second flush (e.g. a later `onStop` with no intervening page turn) writes nothing rather
     * than re-committing a stale position.
     */
    fun drainPending(): Locator? {
        val drained = pending
        pending = null
        return drained
    }

    /**
     * Builds a [Locator] from the two raw DB position columns, clamping each to `>= 0` first.
     * [Locator]'s init `require(>= 0)`s both fields, so a corrupt row with a negative value would
     * otherwise throw and crash the open; a reader must survive a bad row, so it is clamped here.
     * DB values should already be non-negative — this is defense, not an expected path.
     */
    fun storedLocator(spineIndex: Int, charOffset: Int): Locator =
        Locator(spineIndex.coerceAtLeast(0), charOffset.coerceAtLeast(0))

    /**
     * Resolves where to open the book, given the [stored] position (null for a never-opened book).
     * Everything about the real document is supplied as a lambda so this stays pure and testable:
     *  - [pageCountFor] — page count of a chapter (`chapter(i).pages.size`).
     *  - [offsetToPageIndex] — `pageIndexFor(chapter.pages, charOffset)` for a non-empty chapter.
     *  - [firstNonEmptyFrom] — `advance(...)` from a chapter, skipping empties; null if none remains.
     *
     * The rules, in order:
     *  1. **Out-of-range clamp.** A [stored] spineIndex outside `0 until spineSize` (the file was
     *     replaced and shrank) is treated as no stored position — a fresh start from chapter 0,
     *     exactly the cover-skip behavior. Never construct a state past the spine end.
     *  2. **Empty-chapter fallback.** If the start chapter (stored or 0) paginates to zero pages —
     *     a cover-image chapter, or content that changed — skip forward to the first chapter with
     *     pages, at page 0. If the whole book is empty, return `(startChapter, 0)` and let the
     *     caller re-check emptiness and show its "no readable text" message.
     *  3. **Offset mapping.** Otherwise map the stored (or 0) char offset to a page within the start
     *     chapter. This is what makes the restore survive a font-size/margin change: the offset is
     *     stable, the page number is not.
     */
    fun resolveStart(
        stored: Locator?,
        spineSize: Int,
        pageCountFor: (Int) -> Int,
        offsetToPageIndex: (spineIndex: Int, charOffset: Int) -> Int,
        firstNonEmptyFrom: (Int) -> ReadingState?,
    ): ReadingState {
        val valid = stored?.takeIf { it.spineIndex in 0 until spineSize }
        val startChapter = valid?.spineIndex ?: 0

        if (pageCountFor(startChapter) == 0) {
            return firstNonEmptyFrom(startChapter) ?: ReadingState(startChapter, 0)
        }

        val pageIndex = offsetToPageIndex(startChapter, valid?.charOffset ?: 0)
        return ReadingState(startChapter, pageIndex)
    }
}
