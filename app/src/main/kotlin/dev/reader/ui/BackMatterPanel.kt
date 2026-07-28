package dev.reader.ui

import android.view.View
import android.widget.TextView
import dev.reader.R

/**
 * **Back matter** — the reader's first surface: chapters, marks and notes behind one header, one
 * segment and one dismiss.
 *
 * These were three panels, and they are all answers to the same question: *where am I, or where
 * have I been, in this book*. In print they are all back matter and they all use the same device —
 * a name on the left, a figure on the right. Three separate panels meant three entries in the
 * toolbar, three headers and a dismiss that moved; one segmented surface means the reader learns
 * the surface once and afterwards only chooses which list is in it.
 *
 * The header does not move between segments and neither does the ‹. The segment last used is
 * remembered per book, so returning to a book you were annotating opens on Notes rather than
 * making you find it again.
 *
 * This class owns only the shell — the segment, the shared empty state, and which body is visible.
 * The bodies themselves stay where they were: [TocPanel], [BookmarksPanel] and
 * [HighlightsController] each still own their list, adapter, jumps and database work.
 */
internal class BackMatterPanel(
    private val overlay: View,
    private val toc: TocPanel,
    private val bookmarks: BookmarksPanel,
    private val highlights: HighlightsController,
    private val prefs: () -> ReaderPrefs,
    private val bookKey: () -> String,
    private val onDismiss: () -> Unit,
) {

    /** Which list is in the surface. Ordinals are persisted, so do not reorder. */
    enum class Segment { CHAPTERS, MARKS, NOTES }

    private val context get() = overlay.context

    private val panel: View = overlay.findViewById(R.id.back_matter_panel)
    private val segmentCells: CellRowView = overlay.findViewById(R.id.back_matter_segment)
    private val bookLabel: TextView = overlay.findViewById(R.id.back_matter_book)

    private val chaptersBody: View = overlay.findViewById(R.id.toc_list)
    private val marksBody: View = overlay.findViewById(R.id.marks_body)
    private val notesBody: View = overlay.findViewById(R.id.highlights_list)

    private val emptyBody: View = overlay.findViewById(R.id.back_matter_empty)
    private val emptyKicker: TextView = overlay.findViewById(R.id.back_matter_empty_kicker)
    private val emptyTitle: TextView = overlay.findViewById(R.id.back_matter_empty_title)
    private val emptyHint: TextView = overlay.findViewById(R.id.back_matter_empty_hint)

    /**
     * The comic case: a comic has no chapters, and a three-cell segment with one live cell is
     * worse than no segment at all. Set false by [ComicActivity], which shows Marks alone.
     */
    var hasChapters: Boolean = true

    private var segment: Segment = Segment.CHAPTERS
    private var currentBodyEmpty: Boolean = false

    val isVisible: Boolean get() = panel.visibility == View.VISIBLE

    init {
        overlay.findViewById<View>(R.id.back_matter_close).setOnClickListener { hide() }
        overlay.findViewById<View>(R.id.back_matter_empty_action).setOnClickListener { onDismiss() }

        overlay.findViewById<SideheadView>(R.id.marks_sidehead).apply {
            label = context.getString(R.string.marks_sidehead)
            form = SideheadView.Form.RULED
        }
        overlay.findViewById<SideheadView>(R.id.marks_remove_sidehead).apply {
            label = context.getString(R.string.remove_sidehead)
            form = SideheadView.Form.RULED
        }

        segmentCells.onChoice = { index -> show(segmentsOffered()[index]) }
    }

    /** Names the book whose back matter this is — on a device with four books half-read, that
     *  matters more than a panel title repeating the word CONTENTS. */
    fun setBookTitle(title: String) {
        bookLabel.text = title
    }

    fun hide() {
        panel.visibility = View.GONE
    }

    /** Opens on the segment this book was left on, rebuilding whichever list that is. */
    fun open() {
        show(rememberedSegment())
        panel.visibility = View.VISIBLE
    }

    /** Switches the body, rebuilds it, and remembers the choice for this book. */
    fun show(target: Segment) {
        val offered = segmentsOffered()
        segment = if (target in offered) target else offered.first()
        prefs().setLastBackMatterSegment(bookKey(), segment.ordinal)

        segmentCells.setCells(
            labels = offered.map { context.getString(labelFor(it)) },
            chosen = offered.indexOf(segment),
        )
        // A single-cell segment is a label, not a choice: a comic's Marks-only header should not
        // invite a tap that can do nothing.
        segmentCells.visibility = if (offered.size > 1) View.VISIBLE else View.GONE

        when (segment) {
            Segment.CHAPTERS -> toc.refresh()
            Segment.MARKS -> bookmarks.refresh()
            Segment.NOTES -> highlights.refresh()
        }
        applyBodyVisibility()
    }

    /**
     * Called by each body when its list turns out to be empty (or stops being).
     *
     * The empty state lives INSIDE this surface rather than replacing it: the header and the
     * segment stay put and the ‹ is exactly where it was. Hiding the chrome would strand the
     * reader on a device with no hardware Back — and an empty list is not a different screen.
     */
    fun onBodyEmpty(source: Segment, isEmpty: Boolean) {
        if (source != segment) return
        currentBodyEmpty = isEmpty
        if (isEmpty) {
            emptyKicker.text = context.getString(labelFor(segment))
            emptyTitle.setText(emptyTitleFor(segment))
            emptyHint.setText(emptyHintFor(segment))
        }
        applyBodyVisibility()
    }

    private fun applyBodyVisibility() {
        // Marks keeps its own body visible when empty: the "Mark this page" cell at its head is
        // the very thing that fixes the emptiness, so replacing it with an empty state would hide
        // the way out of it.
        val showEmpty = currentBodyEmpty && segment != Segment.MARKS
        emptyBody.visibility = if (showEmpty) View.VISIBLE else View.GONE
        chaptersBody.visibility = visibleIf(segment == Segment.CHAPTERS && !showEmpty)
        marksBody.visibility = visibleIf(segment == Segment.MARKS)
        notesBody.visibility = visibleIf(segment == Segment.NOTES && !showEmpty)
    }

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private fun segmentsOffered(): List<Segment> =
        if (hasChapters) listOf(Segment.CHAPTERS, Segment.MARKS, Segment.NOTES)
        else listOf(Segment.MARKS)

    private fun rememberedSegment(): Segment {
        val stored = prefs().lastBackMatterSegment(bookKey())
        return Segment.entries.getOrNull(stored) ?: segmentsOffered().first()
    }

    private fun labelFor(segment: Segment) = when (segment) {
        Segment.CHAPTERS -> R.string.segment_chapters
        Segment.MARKS -> R.string.segment_marks
        Segment.NOTES -> R.string.segment_notes
    }

    private fun emptyTitleFor(segment: Segment) = when (segment) {
        Segment.CHAPTERS -> R.string.contents_empty
        Segment.MARKS -> R.string.marks_empty
        Segment.NOTES -> R.string.notes_empty
    }

    private fun emptyHintFor(segment: Segment) = when (segment) {
        Segment.CHAPTERS -> R.string.contents_empty_hint
        Segment.MARKS -> R.string.marks_empty_hint
        // The one place in the app the pen can be explained. A reader who does not know that
        // highlights are made with the stylus, and that their palm is ignored, will never make one.
        Segment.NOTES -> R.string.notes_empty_hint
    }
}
