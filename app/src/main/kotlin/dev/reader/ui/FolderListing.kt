package dev.reader.ui

import dev.reader.data.BookEntity
import java.io.File

/**
 * One row in the unified library for a single directory: either a subfolder to descend into or a
 * book to open. Task 8c renders these; this file only projects them.
 */
sealed interface LibraryRow {
    /**
     * An immediate subdirectory of the directory being listed. [bookCount] is the *recursive*
     * number of in-root books anywhere beneath [path] — folders with none never get a row (see
     * [folderListing]), so this is always ≥ 1.
     */
    data class Folder(val name: String, val path: String, val bookCount: Int) : LibraryRow

    /** A book whose parent directory is exactly the directory being listed. */
    data class Book(val entity: BookEntity) : LibraryRow
}

enum class BookStatus { IN_PROGRESS, NOT_STARTED, UNREADABLE }

/**
 * The reading status a book badge should show. `unreadable` wins over everything (a corrupt book is
 * broken whether or not it was ever opened); otherwise `lastOpenedAtMs` — not `charOffset` —
 * decides IN_PROGRESS. Offset can't: the open-time position write-back stamps a coordinate at the
 * first non-empty chapter, so `charOffset > 0` does not distinguish "opened once" from "never
 * opened", whereas a non-null `lastOpenedAtMs` does.
 */
fun statusOf(book: BookEntity): BookStatus = when {
    book.unreadable -> BookStatus.UNREADABLE
    book.lastOpenedAtMs != null -> BookStatus.IN_PROGRESS
    else -> BookStatus.NOT_STARTED
}

/**
 * Project the SQL-sorted [books] into the rows for one directory of a folder-aware library. Pure
 * over its inputs: no filesystem, clock, or I/O — the same inputs always yield the same output, so
 * genuinely empty on-disk folders (no book beneath them) can never appear, which is intended.
 *
 * Books whose path is not under [root] are excluded entirely. Ancestry is segment-correct — a path
 * is under [root] only when it equals it or is prefixed by `root + File.separator` — so a `/Document`
 * root never claims `/Documents/x.epub` (the same rule Task 8a's indexer uses). A trailing separator
 * on [root] or [currentDir] is tolerated.
 *
 * When [flatten] is true the result is every in-root book as a [LibraryRow.Book] in input order,
 * with no folders and [currentDir] ignored.
 *
 * Otherwise the listing is scoped to [currentDir], clamped back to [root] whenever it is not under
 * [root] (a stale `lastFolderPath` after a root change, or a since-deleted folder — non-ancestry is
 * "outside"). A [currentDir] that happens to equal a book's *file* path yields an empty listing, not
 * a clamp: with no filesystem access this function cannot tell a file from a directory of the same
 * name, and in production `lastFolderPath` only ever holds directories the user tapped into. It
 * contains, in order:
 *  1. one [LibraryRow.Folder] per immediate child directory of the scope that has ≥ 1 in-root book
 *     anywhere beneath it, sorted by [NATURAL_NAME_ORDER] (so `page2` precedes `page10`), each with
 *     a recursive [LibraryRow.Folder.bookCount]; then
 *  2. one [LibraryRow.Book] per book whose parent directory is exactly the scope, preserving the
 *     input (SQL) order. Folders always precede books.
 */
fun folderListing(
    books: List<BookEntity>,
    root: String,
    currentDir: String,
    flatten: Boolean,
): List<LibraryRow> {
    val sep = File.separator
    val normalizedRoot = root.removeTrailing(sep)

    // Root filtering, done once and preserving input order for everything downstream.
    val inRoot = books.filter { isUnderOrEqual(it.path, normalizedRoot, sep) }

    if (flatten) return inRoot.map { LibraryRow.Book(it) }

    // Clamp a scope that has wandered outside the root back to the root itself. Same helper the
    // Activity calls to normalize its own currentFolder field, so the two never disagree about
    // what "the scope" is (see [clampToRoot]).
    val scope = clampToRoot(currentDir, root)

    val prefix = scope + sep
    // Immediate-child folder name -> recursive book count. LinkedHashMap only for deterministic
    // iteration before the natural-order sort; the sort defines the final order regardless.
    val childCounts = LinkedHashMap<String, Int>()
    val directBooks = mutableListOf<BookEntity>()

    for (b in inRoot) {
        if (!b.path.startsWith(prefix)) continue // not beneath the current scope
        val relative = b.path.substring(prefix.length)
        val firstSep = relative.indexOf(sep)
        if (firstSep < 0) {
            directBooks += b // no further separator: the book sits directly in the scope
        } else {
            val child = relative.substring(0, firstSep)
            childCounts[child] = (childCounts[child] ?: 0) + 1
        }
    }

    val folderRows = childCounts.entries
        .sortedWith(compareBy(NATURAL_NAME_ORDER) { it.key })
        .map { (name, count) -> LibraryRow.Folder(name = name, path = scope + sep + name, bookCount = count) }

    return folderRows + directBooks.map { LibraryRow.Book(it) }
}

/**
 * Normalize a scope [currentDir] to a value guaranteed to be [root] itself or beneath it: return
 * [currentDir] (trailing separator stripped) when it is under [root] by the same segment-correct
 * ancestry rule [folderListing] uses, otherwise [root] (trailing separator stripped). This is the
 * one place that clamp lives — [folderListing] scopes its listing with it, and [LibraryActivity]
 * normalizes its own `currentFolder` field with it, so the rendered listing, the toolbar title, and
 * the Back behavior can never disagree about whether the current folder is really the root. A stale
 * `lastFolderPath` after a root change, or a since-deleted folder, collapses back to [root] here.
 */
fun clampToRoot(currentDir: String, root: String): String {
    val sep = File.separator
    val normalizedRoot = root.removeTrailing(sep)
    val requested = currentDir.removeTrailing(sep)
    return if (isUnderOrEqual(requested, normalizedRoot, sep)) requested else normalizedRoot
}

/** True when [path] is [root] itself or lives beneath it, matched at a separator boundary. */
private fun isUnderOrEqual(path: String, root: String, sep: String): Boolean =
    path == root || path.startsWith(root + sep)

/** Drop a single trailing [sep], tolerating the caller passing one where none is expected. */
private fun String.removeTrailing(sep: String): String =
    if (this.endsWith(sep) && this.length > sep.length) this.dropLast(sep.length) else this
