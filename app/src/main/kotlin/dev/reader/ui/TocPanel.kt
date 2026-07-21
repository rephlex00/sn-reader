package dev.reader.ui

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.reader.R
import dev.reader.formats.epub.EpubException

/**
 * The Contents panel: the book's chapters, the current one in bold, tap to go there.
 *
 * Owns its own views, adapter and jump logic; [ReaderActivity] owns only whether the panel is
 * visible, and calls [refresh] before showing it. Everything it needs about the book comes through
 * [ReaderSurface] — it cannot reach the document itself.
 *
 * No standing observer and no animation: the list is rebuilt on open and the panel is a single
 * `visibility` flip, so an idle reader pays nothing whether it is open or closed.
 */
internal class TocPanel(
    overlay: View,
    private val reader: ReaderSurface,
) {

    private val list: RecyclerView = overlay.findViewById(R.id.toc_list)
    private val empty: View = overlay.findViewById(R.id.toc_empty)
    private val adapter = TocAdapter(::jumpTo)

    init {
        list.layoutManager = LinearLayoutManager(overlay.context)
        list.itemAnimator = null // e-ink: a rebind is one redraw, never an animated shuffle.
        list.stopScrollAnimations()
        list.adapter = adapter
    }

    /**
     * Rebuilds the list from the already-parsed [ReaderSurface.toc] and the current chapter (for the
     * bold marker). An empty or malformed TOC shows the "No contents" state with the list hidden —
     * never an empty clickable void. Pure View work; nothing is re-parsed.
     */
    fun refresh() {
        val rows = tocRows(reader.toc, reader.currentState.spineIndex)
        adapter.submit(rows)
        val isEmpty = rows.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    /**
     * Jumps to a tapped [row] through the same restore machinery the open path and the Aa sheet use:
     * [tocTarget] resolves the target page (an anchored entry lands on the page containing its char
     * offset, NOT blindly page 0; an entry at a missing or empty chapter skips forward to the nearest
     * readable one), then the jump is an ordinary navigation.
     *
     * The lazy chapter read can throw [EpubException] here for the first time, so this is wrapped as
     * the reading surface's own turn handler is: a failure reports itself and leaves the reader on
     * the page it was already showing.
     */
    private fun jumpTo(row: TocRow) {
        if (!reader.isBookOpen) return
        try {
            // The tapped chapter and everything after it paginate to zero pages: nothing to show.
            if (!reader.jumpToAnchor(row.spineIndex, row.charOffset)) {
                reader.message(R.string.error_book_no_text)
            }
        } catch (e: EpubException) {
            reader.error(R.string.error_open_section, e)
        } catch (e: Exception) {
            reader.error(R.string.error_open_section, e)
        }
    }
}
