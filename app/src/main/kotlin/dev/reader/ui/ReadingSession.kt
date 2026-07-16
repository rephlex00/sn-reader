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
 *  - **Hand each page-turn position to the writer** ([recordPageTurn] / [drainPending]): hold the
 *    latest position in one in-memory field so the Activity can persist it off the main thread. The
 *    Activity flushes after every turn (and once on exit); this class just decouples "where we are"
 *    from "write it," and never itself does I/O or schedules anything — the idle promise (0% CPU at
 *    rest) is kept because writes are triggered by the user's page turns, not by any timer here.
 */
class ReadingSession {

    /**
     * The latest position the user has turned to but not yet handed to the writer, or null if there
     * is nothing pending. A single field, overwritten on every turn: if two turns happen before the
     * writer drains (rare — the Activity drains after each turn), only the newer survives, which is
     * exactly what should be written. Setting it does no I/O and schedules nothing, so it never wakes
     * the process; the Activity is what launches the actual DB write, off the main thread.
     */
    private var pending: Locator? = null

    /** Records a page turn, overwriting any earlier pending value. Pure memory, no I/O. */
    fun recordPageTurn(locator: Locator) {
        pending = locator
    }

    /**
     * Returns the pending position and clears it. The drain is one-shot, so a flush with no
     * intervening page turn (e.g. onStop right after a turn already persisted) writes nothing rather
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
