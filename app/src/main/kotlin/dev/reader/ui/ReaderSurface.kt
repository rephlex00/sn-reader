package dev.reader.ui

import androidx.annotation.StringRes
import dev.reader.engine.Page
import dev.reader.engine.ReadingState
import dev.reader.engine.TocEntry

/**
 * Everything an overlay panel is allowed to ask of the reader — and, just as importantly, the
 * limit of it.
 *
 * The Contents, Bookmarks and Highlights panels each need to know roughly the same three things:
 * where the reader is, how the book paginates, and how to go somewhere. Before this interface they
 * got that by reaching straight into [ReaderActivity]'s private `document`/`config`/`navigator`
 * fields, which is why every panel had to know that a jump means "resolve a char offset to a page,
 * then `showPage`, then `flushPosition`", and why all of it had to live in one 1786-line file.
 *
 * The methods are deliberately phrased as questions about the BOOK, not as access to the objects
 * that answer them. A panel cannot reach the [dev.reader.formats.epub.EpubDocument], so it cannot
 * paginate off the main thread, call `chapter()` under the wrong config, or mutate reading state
 * behind [ReaderActivity]'s back — the three ways this code has actually broken before.
 *
 * Implementations may throw [dev.reader.formats.epub.EpubException] from any method that
 * paginates ([pageCountFor], [pageIndexForOffset], [firstNonEmptyFrom], [goTo]): chapter bytes are
 * read lazily, so a corrupt or truncated chapter surfaces at the moment a panel first reaches it.
 * Callers handle it where they can report it, exactly as the reading surface does.
 */
internal interface ReaderSurface {

    /** False before a book is open, and after one fails to open. Every other member is only
     *  meaningful when this is true; panels guard on it rather than assuming. */
    val isBookOpen: Boolean

    /** The open book's parsed table of contents, empty when there is none (or no book). */
    val toc: List<TocEntry>

    /** Where the reader is right now. */
    val currentState: ReadingState

    /** The page on screen, or null when nothing has been shown yet. */
    val currentPage: Page?

    /** Whole-book progress in `[0,1]` for the page on screen — kept current regardless of whether
     *  the progress bar is being displayed, so a bookmark records the same number either way. */
    val currentProgress: Float

    /** Filesystem path of the open book, or null for a book opened outside the library. */
    val bookPath: String?

    /** How many pages chapter [spineIndex] paginates into under the current typography. Zero for a
     *  chapter whose file is missing or empty. */
    fun pageCountFor(spineIndex: Int): Int

    /** The page within [spineIndex] whose range contains [charOffset] — the stable anchor a
     *  bookmark, highlight or TOC entry stores, resolved through the CURRENT pagination. */
    fun pageIndexForOffset(spineIndex: Int, charOffset: Int): Int

    /** The first readable position at or after chapter [spineIndex], skipping chapters that
     *  paginate to nothing, or null when everything from there on is empty. */
    fun firstNonEmptyFrom(spineIndex: Int): ReadingState?

    /** The full text of the chapter on screen, for resolving highlight offsets to quotations. */
    fun currentChapterText(): String?

    /** Whole-book progress in `[0,1]` for an arbitrary anchor — what a bookmark or highlight stores
     *  so the library can show where in the book it sits. */
    fun progressFor(spineIndex: Int, charOffset: Int): Float

    /** Navigates to [target] and records the new position, the same way a page turn does. */
    fun goTo(target: ReadingState)

    /** Closes the reading chrome down to the bare page — what a panel does after a successful jump. */
    fun closeOverlay()

    /** Shows a transient message. */
    fun message(@StringRes messageId: Int)

    /** Shows a transient message and logs [cause]. */
    fun error(@StringRes messageId: Int, cause: Throwable)
}

/**
 * Goes to the page containing a stored `(spineIndex, charOffset)` anchor — how a Contents entry, a
 * bookmark and a highlight all navigate. Returns false when that chapter and everything after it
 * paginate to nothing, so the caller can say so in its own words.
 *
 * The offset, not the page number, is what is stored and resolved: page numbers change whenever the
 * typography or the screen shape does, so landing on "page 4" would drift while landing on the page
 * CONTAINING the offset does not. [tocTarget] also skips forward past chapters that paginate to
 * nothing rather than showing a blank page.
 *
 * The chrome is closed BEFORE the page is drawn so a jump costs one clean e-ink redraw rather than
 * a page draw followed by an overlay-dismiss redraw.
 *
 * Throws [dev.reader.formats.epub.EpubException] if the chapter cannot be read; every caller is a
 * panel that reports it against its own string.
 */
internal fun ReaderSurface.jumpToAnchor(spineIndex: Int, charOffset: Int): Boolean {
    val target = tocTarget(
        spineIndex = spineIndex,
        charOffset = charOffset,
        pageCountFor = ::pageCountFor,
        offsetToPageIndex = ::pageIndexForOffset,
        firstNonEmptyFrom = ::firstNonEmptyFrom,
    ) ?: return false
    closeOverlay()
    goTo(target)
    return true
}
