package dev.reader.ui

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R
import dev.reader.data.BookDao
import dev.reader.data.HighlightDao
import dev.reader.data.HighlightEntity
import dev.reader.engine.ExistingHighlight
import dev.reader.engine.highlightContaining
import dev.reader.engine.mergeHighlights
import dev.reader.engine.snapToWords
import dev.reader.formats.epub.EpubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything about highlights: making them with the pen, washing them onto the page, listing them
 * in the Highlights panel, and deleting them.
 *
 * A controller rather than a panel, because highlighting is not confined to a panel. Unlike Contents
 * and Bookmarks — which are lists that navigate — a highlight is created ON the page with the pen,
 * drawn ON the page as a wash, and removed through a chip anchored ON the page. Splitting the list
 * from the gesture would put one feature in two files that had to agree about the same cache and the
 * same pending-bracket state, so the whole interaction lives here.
 *
 * It owns [pageView]'s highlight decoration (washes and the pending-bracket caret) and the on-page
 * delete chip, which it creates and adds to the reader's container itself — the chip exists for no
 * other purpose. [ReaderActivity] keeps only the panel's visibility, forwards pen events, and tells
 * it when the chapter on screen changed.
 *
 * No standing observer anywhere: the chapter's highlights are loaded once per chapter change, the
 * panel list once per open, and edits reload explicitly.
 */
internal class HighlightsController(
    overlay: View,
    container: FrameLayout,
    private val pageView: PageView,
    private val reader: ReaderSurface,
    private val scope: CoroutineScope,
    private val highlights: HighlightDao,
    private val books: BookDao,
) {

    private val list: RecyclerView = overlay.findViewById(R.id.highlights_list)
    private val empty: View = overlay.findViewById(R.id.highlights_empty)
    private val adapter = HighlightAdapter(onJump = ::jumpTo, onDelete = ::deleteRow)

    /** The current chapter's highlights, cached so a pen tap can hit-test without a database read. */
    private var chapterHighlights: List<HighlightEntity> = emptyList()

    /** Which chapter [chapterHighlights] belongs to; -1 before anything is loaded. */
    private var chapterHighlightsSpine: Int = -1

    /** The armed bracket-start offset, if any — drawn on the page so the pending start is visible. */
    private var bracketAnchorOffset: Int? = null

    /** Which highlight the visible delete chip would remove. */
    private var pendingDeleteId: Long? = null

    /**
     * The on-page delete chip, revealed by a pen tap that lands inside an existing highlight. Added
     * last so it draws above the page; its own tap deletes, and a tap anywhere else dismisses it.
     */
    private val deleteChip: TextView = TextView(container.context).apply {
        val density = resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (10 * density).toInt()
        text = context.getString(R.string.highlight_delete_chip)
        setTextColor(Color.BLACK)
        textSize = 16f
        setBackgroundResource(R.drawable.delete_chip_bg)
        setPadding(padH, padV, padH, padV)
        minHeight = (44 * density).toInt()
        isClickable = true
        visibility = View.GONE
        setOnClickListener {
            val id = pendingDeleteId
            hideDeleteChip()
            if (id != null) delete(id) { }
        }
    }

    init {
        list.layoutManager = LinearLayoutManager(overlay.context)
        list.itemAnimator = null // e-ink: a rebind is one redraw, never an animated shuffle.
        list.stopScrollAnimations()
        list.adapter = adapter
        container.addView(
            deleteChip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        pageView.onStylusTap = ::onStylusTap
        pageView.onStylusDrag = ::onStylusDrag
    }

    /** Test-visible state: the on-page gesture machine has no production surface of its own. */
    internal val bracketAnchorForTest: Int? get() = bracketAnchorOffset
    internal val chapterHighlightsForTest: List<HighlightEntity> get() = chapterHighlights
    internal val deleteChipForTest: TextView get() = deleteChip

    /**
     * Re-reads this book's highlights off the main thread and rebinds the panel list and empty state.
     */
    fun refresh() {
        val path = reader.bookPath ?: return
        scope.launch {
            val hl = withContext(Dispatchers.IO) { highlights.highlightsForBook(path) }
            val rows = highlightRows(hl, reader.toc)
            adapter.submit(rows)
            val isEmpty = rows.isEmpty()
            empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            list.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    /**
     * Called when a page is drawn, so the washes on screen belong to the chapter on screen. Does
     * nothing while the reader stays within a chapter — the common case, and a page turn must not
     * cost a database read.
     *
     * A pending bracket cannot span chapters (its offsets are chapter-relative), so a chapter change
     * drops it and says so rather than silently committing something the reader did not mean.
     */
    fun onChapterShown(spineIndex: Int) {
        if (spineIndex == chapterHighlightsSpine) return
        if (bracketAnchorOffset != null) {
            reader.message(R.string.highlight_cancelled_chapter_change)
            clearBracketAnchor()
        }
        chapterHighlightsSpine = spineIndex
        chapterHighlights = emptyList()
        pageView.setHighlights(emptyList())
        loadChapterHighlights(spineIndex)
    }

    /**
     * Ends any pen selection in progress — called when the reading chrome opens over the page. A
     * pending bracket-start marker would otherwise sit under the overlay, still armed, and commit a
     * span the reader had visually moved on from.
     */
    fun cancelPendingSelection() {
        clearBracketAnchor()
        hideDeleteChip()
    }

    /** Hides the on-page delete chip and forgets its target. Safe to call when already hidden. */
    fun hideDeleteChip() {
        pendingDeleteId = null
        if (deleteChip.visibility != View.GONE) deleteChip.visibility = View.GONE
    }

    /**
     * A pen tap: if it lands inside an existing highlight, offer to remove it; otherwise it is a
     * bracket endpoint — the first tap arms a start marker, a second tap in a different word commits
     * the span, and a second tap in the same word cancels.
     */
    fun onStylusTap(offset: Int) {
        hideDeleteChip() // any pen tap dismisses a chip from a previous tap; a hit below re-shows it
        val existing = highlightContaining(chapterHighlights.toExisting(), offset)
        if (existing != null) {
            clearBracketAnchor() // a remove-tap also abandons any pending bracket
            showDeleteChipAt(existing.id, offset)
            return
        }

        val text = reader.currentChapterText() ?: return
        val anchor = bracketAnchorOffset
        if (anchor == null) {
            bracketAnchorOffset = offset
            pageView.setBracketAnchor(offset)
            return
        }
        // Tapping the same word as the anchor cancels; otherwise commit the span between them.
        if (snapToWords(text, anchor, anchor) == snapToWords(text, offset, offset)) {
            clearBracketAnchor()
        } else {
            clearBracketAnchor()
            commit(minOf(anchor, offset), maxOf(anchor, offset))
        }
    }

    /** A pen drag: clear any armed bracket and delete chip, then commit the swiped span. */
    fun onStylusDrag(startOffset: Int, endOffset: Int) {
        hideDeleteChip()
        clearBracketAnchor()
        commit(minOf(startOffset, endOffset), maxOf(startOffset, endOffset))
    }

    /**
     * Word-snaps `[rawStart, rawEnd]`, merges it with the chapter's existing highlights, and writes
     * one row — foreign-key guarded (a book the indexer has not reached has no `books` row) and
     * cancellation-safe. Reloads the chapter's washes on success.
     *
     * Everything is within the chapter on screen; a bracket can never cross chapters because
     * [onChapterShown] drops a pending one.
     */
    fun commit(rawStart: Int, rawEnd: Int) {
        val path = reader.bookPath ?: return
        val spineIndex = reader.currentState.spineIndex
        val text = reader.currentChapterText() ?: return
        val snapped = snapToWords(text, rawStart, rawEnd) ?: return // landed between words → no-op
        scope.launch {
            val inLibrary = withContext(Dispatchers.IO) { books.getByPath(path) != null }
            if (!inLibrary) {
                reader.message(R.string.error_book_not_indexed)
                return@launch
            }
            try {
                val existing = withContext(Dispatchers.IO) {
                    highlights.highlightsForChapter(path, spineIndex)
                }
                val merge = mergeHighlights(existing.toExisting(), snapped)
                val excerpt = text.substring(merge.merged.start, merge.merged.end)
                val fraction = reader.progressFor(spineIndex, merge.merged.start)
                withContext(Dispatchers.IO) {
                    highlights.replaceWithMerged(
                        merge.removedIds,
                        HighlightEntity(
                            bookPath = path,
                            spineIndex = spineIndex,
                            startOffset = merge.merged.start,
                            endOffset = merge.merged.end,
                            text = excerpt,
                            progressFraction = fraction,
                            createdAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
                reloadChapterHighlightsIfCurrent(spineIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reader.error(R.string.error_save_highlight, e)
            }
        }
    }

    /** Clears a pending bracket-start (both the field and the on-page marker). */
    private fun clearBracketAnchor() {
        bracketAnchorOffset = null
        pageView.setBracketAnchor(null)
    }

    /**
     * Reveals the delete chip anchored under the tapped highlight (via [PageView.caretPointFor]),
     * clamped to stay on screen. It is measured before being shown so it appears already in place —
     * no first-frame jump.
     */
    private fun showDeleteChipAt(highlightId: Long, offset: Int) {
        val at = pageView.caretPointFor(offset) ?: return
        pendingDeleteId = highlightId
        deleteChip.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxX = (pageView.width - deleteChip.measuredWidth).coerceAtLeast(0)
        val maxY = (pageView.height - deleteChip.measuredHeight).coerceAtLeast(0)
        deleteChip.translationX = at.x.coerceIn(0f, maxX.toFloat())
        deleteChip.translationY = at.y.coerceIn(0f, maxY.toFloat())
        deleteChip.visibility = View.VISIBLE
    }

    private fun deleteRow(row: HighlightRow) = delete(row.id) { refresh() }

    /** Deletes a highlight by id (off-main), reloads the chapter's washes, then runs [also]. */
    private fun delete(id: Long, also: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { highlights.deleteById(id) }
            reloadChapterHighlightsIfCurrent(reader.currentState.spineIndex)
            also()
        }
    }

    /** One-shot off-main load of a chapter's highlights into the cache and the page's washes. */
    private fun loadChapterHighlights(spineIndex: Int) {
        val path = reader.bookPath ?: return
        scope.launch {
            val hl = try {
                withContext(Dispatchers.IO) { highlights.highlightsForChapter(path, spineIndex) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (spineIndex != chapterHighlightsSpine) return@launch // a later chapter change won the race
            chapterHighlights = hl
            pageView.setHighlights(hl.map { it.startOffset to it.endOffset })
        }
    }

    /** Reloads the chapter cache after an edit, but only if that chapter is still the one on screen. */
    private fun reloadChapterHighlightsIfCurrent(spineIndex: Int) {
        if (spineIndex == chapterHighlightsSpine) loadChapterHighlights(spineIndex)
    }

    /** Jumps to a tapped highlight by its stored start offset — see [jumpToAnchor]. */
    private fun jumpTo(row: HighlightRow) {
        if (!reader.isBookOpen) return
        try {
            if (!reader.jumpToAnchor(row.spineIndex, row.startOffset)) {
                reader.message(R.string.error_book_no_text)
            }
        } catch (e: EpubException) {
            reader.error(R.string.error_open_highlight, e)
        } catch (e: Exception) {
            reader.error(R.string.error_open_highlight, e)
        }
    }
}

/** Maps cached entities to the engine's [ExistingHighlight] shape. */
private fun List<HighlightEntity>.toExisting(): List<ExistingHighlight> =
    map { ExistingHighlight(it.id, it.startOffset, it.endOffset) }
