package dev.reader.ui

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R
import dev.reader.data.BookDao
import dev.reader.data.BookmarkDao
import dev.reader.data.BookmarkEntity
import dev.reader.formats.epub.EpubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Bookmarks panel: this book's saved pages, plus the "bookmark this page" toggle.
 *
 * Owns its views, adapter, and every database call bookmarks need; [ReaderActivity] owns only
 * whether the panel is visible, and calls [refresh] when it opens. What it knows about the book
 * comes through [ReaderSurface].
 *
 * No standing observer: the list is re-read each time the panel opens, and again after any
 * add/remove/delete. A reader sitting on a page — panel open or closed — costs nothing.
 */
internal class BookmarksPanel(
    overlay: View,
    private val reader: ReaderSurface,
    private val scope: CoroutineScope,
    private val bookmarks: BookmarkDao,
    private val books: BookDao,
) {

    private val list: RecyclerView = overlay.findViewById(R.id.bookmarks_list)
    private val empty: View = overlay.findViewById(R.id.bookmarks_empty)
    private val toggle: TextView = overlay.findViewById(R.id.bookmark_toggle)
    private val adapter = BookmarkAdapter(onJump = ::jumpTo, onDelete = ::delete)

    init {
        list.layoutManager = LinearLayoutManager(overlay.context)
        list.itemAnimator = null // e-ink: a rebind is one redraw, never an animated shuffle.
        list.stopScrollAnimations()
        list.adapter = adapter
        toggle.setOnClickListener { toggleCurrentPage() }
    }

    /**
     * Re-reads this book's bookmarks off the main thread and rebinds the panel: the list (chapter ·
     * %, in reading order), the empty state, and the toggle's label — which comes from range-based
     * [currentPageBookmark] detection, so it stays correct after a re-paginate.
     */
    fun refresh() {
        val path = reader.bookPath ?: return
        scope.launch {
            val marks = withContext(Dispatchers.IO) { bookmarks.bookmarksFor(path) }
            val rows = bookmarkRows(marks, reader.toc)
            adapter.submit(rows)
            val isEmpty = rows.isEmpty()
            empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            list.visibility = if (isEmpty) View.GONE else View.VISIBLE

            val onThisPage = bookmarkOnCurrentPage(marks)
            toggle.setText(if (onThisPage != null) R.string.bookmark_remove else R.string.bookmark_add)
        }
    }

    /** The bookmark on the page currently drawn, if any (range-based). Null before a page is shown. */
    private fun bookmarkOnCurrentPage(marks: List<BookmarkEntity>): BookmarkEntity? {
        val page = reader.currentPage ?: return null
        return currentPageBookmark(marks, reader.currentState.spineIndex, page)
    }

    /**
     * Adds a bookmark for the current page, or removes the one already on it — a pure data action
     * that never re-paginates or moves the reading position. It stores the page's top char offset
     * and the current progress fraction (independent of whether the progress bar is displayed, so
     * the number is the same either way).
     *
     * The library-membership check comes first and is load-bearing: [BookmarkEntity.bookPath] is a
     * foreign key to `books.path` with enforcement ON, but the reader also opens books with no
     * `books` row — a sideloaded EPUB launched directly, or one the indexer has not reached. For
     * such a book an INSERT would violate the foreign key and throw. Unlike a position write (an
     * `UPDATE ... WHERE path` that harmlessly matches no rows) there is no graceful fallback, so
     * membership is checked before attempting anything and the reader is told instead. A book with
     * no row also cannot have existing bookmarks, so there is nothing to remove either.
     *
     * The try/catch is a backstop for the race where a library sync deletes the row between that
     * check and this write.
     */
    private fun toggleCurrentPage() {
        val page = reader.currentPage ?: return
        val path = reader.bookPath ?: return
        val spineIndex = reader.currentState.spineIndex
        scope.launch {
            val inLibrary = withContext(Dispatchers.IO) { books.getByPath(path) != null }
            if (!inLibrary) {
                reader.message(R.string.error_book_not_indexed)
                return@launch
            }
            val existing = withContext(Dispatchers.IO) {
                currentPageBookmark(bookmarks.bookmarksFor(path), spineIndex, page)
            }
            try {
                withContext(Dispatchers.IO) {
                    if (existing != null) {
                        bookmarks.deleteById(existing.id)
                    } else {
                        bookmarks.insert(
                            BookmarkEntity(
                                bookPath = path,
                                spineIndex = spineIndex,
                                charOffset = page.startOffset,
                                progressFraction = reader.currentProgress,
                                createdAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
                refresh()
            } catch (e: CancellationException) {
                // The Activity was destroyed mid-write: let structured-concurrency cancellation
                // propagate rather than swallowing it into a "couldn't save" toast on a dying
                // screen — the same rule the open and prefetch coroutines hold.
                throw e
            } catch (e: Exception) {
                reader.error(R.string.error_save_bookmark, e)
            }
        }
    }

    /** Deletes a bookmark from the list's ✕ and reloads the panel. */
    private fun delete(row: BookmarkRow) {
        scope.launch {
            withContext(Dispatchers.IO) { bookmarks.deleteById(row.id) }
            refresh()
        }
    }

    /** Jumps to a tapped bookmark by its stored char offset — see [jumpToAnchor]. */
    private fun jumpTo(row: BookmarkRow) {
        if (!reader.isBookOpen) return
        try {
            if (!reader.jumpToAnchor(row.spineIndex, row.charOffset)) {
                reader.message(R.string.error_book_no_text)
            }
        } catch (e: EpubException) {
            reader.error(R.string.error_open_bookmark, e)
        } catch (e: Exception) {
            reader.error(R.string.error_open_bookmark, e)
        }
    }
}
