package dev.reader.ui

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R
import kotlin.math.roundToInt
import dev.reader.data.BookmarkDao
import dev.reader.data.BookmarkEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The comic reader's Bookmarks panel, restyled to match the EPUB reader's [BookmarksPanel]: the
 * same list chrome — a RecyclerView of `item_bookmark.xml` rows, a ✕ close, an empty state — reused
 * as-is rather than approximated. It differs from [BookmarksPanel] in two ways that follow from
 * what a comic actually has, not from a shortcut:
 *
 * - No "bookmark this page" toggle row: that control already exists as the glyph in the top bar
 *   ([ComicActivity]'s `comic_bookmark_button`), so this panel is the list alone.
 * - A row's [BookmarkRow.label] is "page N", not "chapter · N%", and a tap jumps by page index —
 *   comics have no TOC to resolve a chapter from and no char offsets. [BookmarkRow.charOffset] is
 *   unused filler here (always the entity's raw 0). Reusing [BookmarkRow]/[BookmarkAdapter]/
 *   `item_bookmark.xml` unchanged, rather than a comic-only equivalent, is what keeps this panel
 *   pixel-identical to the reader's rather than a lookalike.
 *
 * [ComicActivity] already owns the current bookmark list — it reads it for the toggle glyph and the
 * scrubber's ticks — so this panel is handed that list on [show] instead of re-reading it itself.
 * A delete is the one write this panel makes on its own; it re-reads through [bookmarks] afterward
 * and reports the fresh list back via [onDeleted] so ComicActivity's own copy, the toggle glyph and
 * the scrubber's bookmark ticks stay in step — the same job [BookmarksPanel]'s `onBookmarksChanged`
 * does, minus a `ReaderSurface` to re-read the book path through.
 */
internal class ComicBookmarksPanel(
    overlay: View,
    private val scope: CoroutineScope,
    private val bookmarks: BookmarkDao,
    private val onJump: (Int) -> Unit,
    private val onDeleted: (List<BookmarkEntity>) -> Unit,
    private val onDeleteFailed: (Exception) -> Unit,
) {
    private val context = overlay.context
    private val list: RecyclerView = overlay.findViewById(R.id.comic_bookmarks_list)
    private val empty: View = overlay.findViewById(R.id.comic_bookmarks_empty)
    private val adapter = BookmarkAdapter(onJump = { onJump(it.spineIndex) }, onDelete = ::delete)

    /** The book this panel is currently showing rows for — remembered only so [delete] knows which
     *  book's list to re-read; comics have no [ReaderSurface] to ask for it on demand. */
    private var bookPath: String = ""

    init {
        list.layoutManager = LinearLayoutManager(context)
        list.itemAnimator = null // e-ink: a rebind is one redraw, never an animated shuffle.
        list.stopScrollAnimations()
        list.adapter = adapter
    }

    /** Rebinds the panel from an already-loaded bookmark list for [path], ordered by page. */
    fun show(path: String, marks: List<BookmarkEntity>) {
        bookPath = path
        bind(marks)
    }

    private fun bind(marks: List<BookmarkEntity>) {
        val rows = marks.sortedBy { it.spineIndex }.map { bm ->
            BookmarkRow(
                id = bm.id,
                spineIndex = bm.spineIndex,
                charOffset = bm.charOffset,
                // A comic counts in pages, never percentages — so the name says "Page 24" and the
                // figure column carries how far through that is, the one place a comic mark has a
                // second thing worth saying.
                chapter = context.getString(R.string.comic_bookmark_row, bm.spineIndex + 1),
                figure = context.getString(
                    R.string.toc_percent,
                    (bm.progressFraction.coerceIn(0f, 1f) * 100).roundToInt(),
                ),
            )
        }
        adapter.submit(rows)
        val isEmpty = rows.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    /**
     * Deletes a bookmark from the list's ✕ and reloads the panel. Deliberately NO `inLibrary`
     * pre-check: unlike an insert, a delete for a row the library sync already cascaded away
     * matches zero rows in the DB and is a harmless no-op, not a foreign-key violation. That guard
     * belongs only on the insert path (see [ComicActivity.toggleBookmark]).
     */
    private fun delete(row: BookmarkRow) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { bookmarks.deleteById(row.id) }
                val marks = withContext(Dispatchers.IO) { bookmarks.bookmarksFor(bookPath) }
                bind(marks)
                onDeleted(marks)
            } catch (e: CancellationException) {
                // The Activity was destroyed mid-write: let structured-concurrency cancellation
                // propagate rather than swallowing it into an error on a dying screen — the same
                // rule BookmarksPanel.delete and ComicActivity.toggleBookmark hold.
                throw e
            } catch (e: Exception) {
                // Guards BOTH calls: the re-read above can throw on a DB error just as the delete
                // itself can — mirrors BookmarksPanel.delete's own try/catch shape.
                onDeleteFailed(e)
            }
        }
    }
}
