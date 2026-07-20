package dev.reader.ui

import dev.reader.data.BookEntity

/** Reading-status choices the library can filter to. */
enum class StatusFilter { ALL, NOT_STARTED, IN_PROGRESS, FINISHED }

/** A book past this whole-book fraction is "Finished". */
private const val FINISHED_THRESHOLD = 0.98f

/**
 * A readable book's reading status for the filter, or null for an unreadable book (not a reading
 * state — excluded from every non-ALL filter). Mirrors [statusOf]'s opened/not-opened split and adds
 * FINISHED from [BookEntity.progressFraction]. Pure.
 */
fun bookStatusFilter(book: BookEntity): StatusFilter? = when {
    book.unreadable -> null
    book.lastOpenedAtMs == null -> StatusFilter.NOT_STARTED
    (book.progressFraction ?: 0f) >= FINISHED_THRESHOLD -> StatusFilter.FINISHED
    else -> StatusFilter.IN_PROGRESS
}

/** Whether a query or status makes the library switch to flat filtered results. Blank query = inactive. */
fun isFilterActive(query: String, status: StatusFilter): Boolean =
    query.isNotBlank() || status != StatusFilter.ALL

/**
 * The books matching [query] (case-insensitive substring over title OR author; blank = all) AND
 * [status] (ALL = all, incl. unreadable; else the book's [bookStatusFilter] must equal it, which
 * excludes unreadable books). Input order is preserved — the caller has already sorted. Pure.
 */
fun findBooks(books: List<BookEntity>, query: String, status: StatusFilter): List<BookEntity> {
    val q = query.trim()
    return books.filter { b ->
        val matchesQuery = q.isEmpty() ||
            b.title.contains(q, ignoreCase = true) ||
            (b.author?.contains(q, ignoreCase = true) == true)
        val matchesStatus = status == StatusFilter.ALL || bookStatusFilter(b) == status
        matchesQuery && matchesStatus
    }
}
