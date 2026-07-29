package dev.reader.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import dev.reader.R

// Type's bounded value sets, kept beside the controls that offer them.
//
// Text size used to be a stepper over [TEXT_SIZE_MIN_PX, TEXT_SIZE_MAX_PX] with a "36px" readout —
// a developer's unit in the interface, and two taps plus two full redraws per adjustment. It is now
// five steps drawn as the letters they produce, so a reader picks a size by looking at it. The min
// and max survive because a pref stored by an older build can be any value in that range and still
// has to be clamped and shown as the nearest step.
internal const val TEXT_SIZE_MIN_PX = 24f
internal const val TEXT_SIZE_MAX_PX = 56f

/** The five steps, spanning the old stepper's range. The middle one is [ReaderPrefs]'s own default
 *  (34px) on purpose: an untouched install must render exactly as it did before this control
 *  existed AND fill the centre cell, rather than showing a size it is not set to. */
internal val TEXT_SIZE_STEPS_PX = listOf(26f, 30f, 34f, 39f, 45f)

/** The sizes the five Aa specimens are drawn at — the same progression, in sp, so the cell shows
 *  the shape of the choice rather than describing it. */
private val TEXT_SIZE_SPECIMEN_SP = listOf(13f, 16f, 20f, 25f, 31f)

internal const val MARGIN_NARROW_PX = 40
internal const val MARGIN_MEDIUM_PX = 72
internal const val MARGIN_WIDE_PX = 120

private val SPACING_STEPS = listOf(1.2f, 1.4f, 1.6f)
private val MARGIN_STEPS = listOf(MARGIN_NARROW_PX, MARGIN_MEDIUM_PX, MARGIN_WIDE_PX)
private val REFRESH_STEPS = listOf(3, 6, 10)
private val FONT_FAMILIES = listOf("literata", "bitter", "atkinson")

/** The bundled face for each entry in [FONT_FAMILIES], in the same order — the cells are drawn in
 *  these, so the picker previews its own options instead of naming them in a fourth typeface. */
private val FONT_RESOURCES = listOf(R.font.literata, R.font.bitter, R.font.atkinson)

/**
 * What Type asks the reader to do. Every method is an ACTION, never a widget: the surface knows
 * which control was tapped, the reader knows what tapping it means.
 *
 * That split is the point. Changing typography has to re-paginate the current chapter and land the
 * reader on the same words (see `ReaderActivity.applySettingsChange`), which needs the open
 * document, the measured viewport and the reading position — none of which this surface should
 * hold. The display-only switches, by contrast, must NOT re-paginate, and keeping both kinds behind
 * one interface is what stops a future control from quietly picking the wrong one.
 */
internal interface SettingsHost {

    /** Writes a typography preference and re-paginates, preserving the reader's place. */
    fun applyTypography(mutate: (ReaderPrefs) -> Unit)

    /** Sets the text size to one of [TEXT_SIZE_STEPS_PX], clamped to the supported range. */
    fun applyTextSize(px: Float)

    /** Applies a margin preset, clamped to what the current viewport can take. */
    fun applyMarginPreset(presetPx: Int)

    /** Flips the progress bar. Display only — never re-paginates. */
    fun toggleProgressBar()

    /** Flips rotation lock. Display only. */
    fun toggleRotationLock()

    /** Flips the fast-refresh mode. Display only. */
    fun toggleFasterTurns()

    /** Sets how many page turns pass between full clean refreshes. Display only. */
    fun applyRefreshFrequency(pages: Int)

    /** Flips whether the scrubber's thumbnail strip is generated and shown at all. Turning it on
     *  schedules generation if no strip exists yet; turning it off never deletes an existing one —
     *  only [deletePreviewsForCurrentBook] does that. */
    fun togglePreviews()

    /** Deletes the current book's preview strip (whatever state it is in — complete, partial, or
     *  mid-generation) and clears the live generated-chapters set. Does NOT flip [togglePreviews];
     *  a reader who deletes but leaves previews on gets a fresh strip on the next open. */
    fun deletePreviewsForCurrentBook()

    /** For the previews readout: `(chapters generated so far, total chapters)` while a strip is
     *  actively generating for the open book, or null when there is nothing to report (previews
     *  off, a strip already loaded, or no generation in flight). */
    fun previewGenerationProgress(): Pair<Int, Int>?

    /** Whether the delete-previews control should be reachable: a strip is generating right now,
     *  OR one already sits on disk for the current (book, config) — the common case, since a
     *  finished strip is exactly what a reader wants to reclaim disk space from. Deliberately NOT
     *  tied to [previewGenerationProgress] (which reports null once generation completes) — that
     *  coupling is what made this control disappear the moment the strip it deletes was ready. */
    fun hasPreviewsForCurrentBook(): Boolean
}

/**
 * **Type** — the second of the reader's two surfaces: what the page looks like, as against where
 * you are in the book.
 *
 * Every control here is one [CellRowView]. A font picker, a margin preset, a page-turn mode and a
 * plain boolean are the same shape, so the reader learns one control and then only reads which cell
 * is filled. That replaced nine pill switches (another platform's language) and the "36px" readout
 * in one move, and it is why this file is now mostly a table of value sets.
 *
 * The controls sit in three tabs ([Tab]) rather than one column: TEXT is what you judge by looking
 * at the live page above the sheet, PAGE is how the block sits on it, SCREEN is about the panel
 * rather than the book. Switching tabs is two visibility flips — one e-ink redraw, and no measure
 * pass on the rows nobody is looking at.
 *
 * This is deliberately only view binding. It decides nothing; every tap goes straight to
 * [SettingsHost]. Showing and hiding the surface is [ReaderActivity]'s job, a single `visibility`
 * flip; it calls [refresh] on the way in.
 */
internal class SettingsSheet(
    private val overlay: View,
    private val host: SettingsHost,
    private val prefs: () -> ReaderPrefs,
) {

    private val context: Context get() = overlay.context

    /**
     * Which tab is showing. Kept across opens for as long as the book is: a reader stepping the
     * margins in and out reopens Type on PAGE, not back at the top. Not persisted — a new book is a
     * new session, and TEXT is the right place to start one.
     */
    private var tab: Tab = Tab.TEXT

    /** Type's three tabs, in the order their cells appear. */
    internal enum class Tab { TEXT, PAGE, SCREEN }

    /**
     * Fills every cell group and wires it to its action. Called once: the listeners hold no state
     * and fire only on a deliberate tap, so they cost nothing at rest.
     *
     * The font cells are drawn in the faces they select — a picker that previews its own options —
     * which is why they are SPECIMEN rather than MARK.
     */
    fun wire() {
        sidehead(R.id.type_head_face, R.string.type_head_face)
        sidehead(R.id.type_head_size, R.string.type_head_size)
        sidehead(R.id.type_head_spacing, R.string.type_head_spacing)
        sidehead(R.id.type_head_book, R.string.type_head_book)
        sidehead(R.id.type_head_previews, R.string.type_head_previews)

        cells(R.id.type_segment).apply {
            setCells(
                labels = listOf(
                    context.getString(R.string.type_tab_text),
                    context.getString(R.string.type_tab_page),
                    context.getString(R.string.type_tab_screen),
                ),
                chosen = tab.ordinal,
            )
            onChoice = { index -> showTab(Tab.entries[index]) }
        }

        fillFontCells()
        cells(R.id.font_cells).onChoice = { index ->
            host.applyTypography { p -> p.fontFamily = FONT_FAMILIES[index] }
        }

        cells(R.id.size_cells).apply {
            setCells(
                labels = TEXT_SIZE_STEPS_PX.map { context.getString(R.string.text_size_specimen) },
                chosen = nearestSizeStep(prefs().textSizePx),
                style = CellRowView.CellStyle.SPECIMEN,
                specimenSizesSp = TEXT_SIZE_SPECIMEN_SP,
            )
            onChoice = { index -> host.applyTextSize(TEXT_SIZE_STEPS_PX[index]) }
        }

        cells(R.id.spacing_cells).apply {
            setCells(
                labels = listOf(
                    context.getString(R.string.spacing_tight),
                    context.getString(R.string.spacing_normal),
                    context.getString(R.string.spacing_open),
                ),
                chosen = SPACING_STEPS.indexOf(prefs().lineSpacingMultiplier).coerceAtLeast(0),
            )
            onChoice = { index ->
                host.applyTypography { p -> p.lineSpacingMultiplier = SPACING_STEPS[index] }
            }
        }

        cells(R.id.margin_cells).apply {
            setCells(
                labels = listOf(
                    context.getString(R.string.margin_narrow),
                    context.getString(R.string.margin_medium),
                    context.getString(R.string.margin_wide),
                ),
                chosen = MARGIN_STEPS.indexOf(prefs().marginPx).coerceAtLeast(0),
            )
            onChoice = { index -> host.applyMarginPreset(MARGIN_STEPS[index]) }
        }

        boolean(R.id.justify_cells, prefs().justified) {
            host.applyTypography { p -> p.justified = !p.justified }
        }
        boolean(R.id.hyphen_cells, prefs().hyphenated) {
            host.applyTypography { p -> p.hyphenated = !p.hyphenated }
        }
        boolean(R.id.publisher_cells, prefs().publisherStyling) {
            host.applyTypography { p -> p.publisherStyling = !p.publisherStyling }
        }
        boolean(R.id.headings_cells, prefs().inferHeadings) {
            host.applyTypography { p -> p.inferHeadings = !p.inferHeadings }
        }
        boolean(R.id.rotation_lock_cells, prefs().rotationLocked) { host.toggleRotationLock() }
        boolean(R.id.progress_cells, prefs().showProgressBar) { host.toggleProgressBar() }
        boolean(R.id.previews_cells, prefs().previewsEnabled) { host.togglePreviews() }

        cells(R.id.turns_cells).apply {
            setCells(
                labels = listOf(
                    context.getString(R.string.turns_clean),
                    context.getString(R.string.turns_faster),
                ),
                chosen = if (prefs().fasterPageTurns) 1 else 0,
            )
            onChoice = { host.toggleFasterTurns() }
        }

        cells(R.id.refresh_freq_cells).apply {
            setCells(
                labels = REFRESH_STEPS.map { it.toString() },
                chosen = REFRESH_STEPS.indexOf(prefs().fullRefreshEveryN).coerceAtLeast(0),
            )
            onChoice = { index -> host.applyRefreshFrequency(REFRESH_STEPS[index]) }
        }

        overlay.findViewById<View>(R.id.previews_delete).setOnClickListener {
            host.deletePreviewsForCurrentBook()
            refresh()
        }

        showTab(tab)
    }

    /**
     * The face picker, drawn as three specimens: each name set in the face it selects, all three at
     * the size the page is currently set to. Rebuilt rather than merely re-[CellRowView.choose]n
     * whenever [refresh] runs, because a text-size change makes every specimen the wrong size — the
     * picker is showing what the reader's own book would look like, so it has to follow the book.
     * Three TextViews, on open and on a control tap only; nothing at rest.
     */
    private fun fillFontCells() {
        cells(R.id.font_cells).setCells(
            labels = listOf(
                context.getString(R.string.font_literata),
                context.getString(R.string.font_bitter),
                context.getString(R.string.font_atkinson),
            ),
            chosen = FONT_FAMILIES.indexOf(prefs().fontFamily).coerceAtLeast(0),
            style = CellRowView.CellStyle.SPECIMEN,
            specimenSizePx = prefs().textSizePx.coerceIn(TEXT_SIZE_MIN_PX, TEXT_SIZE_MAX_PX),
            specimenFonts = FONT_RESOURCES,
        )
    }

    /**
     * Shows one tab's body, hides the other two, and sizes the sheet to what that tab actually
     * needs — two visibility flips and a height, which is one e-ink redraw.
     *
     * The height is per tab rather than per sheet because a single height has to clear the tallest
     * one, and that left TEXT — the tab whose whole point is watching the live page above the sheet
     * — sitting under a screenful of blank paper. See the type_sheet_* dimens.
     */
    private fun showTab(next: Tab) {
        tab = next
        overlay.findViewById<View>(R.id.type_body_text).visibility = visible(next == Tab.TEXT)
        overlay.findViewById<View>(R.id.type_body_page).visibility = visible(next == Tab.PAGE)
        overlay.findViewById<View>(R.id.type_body_screen).visibility = visible(next == Tab.SCREEN)
        cells(R.id.type_segment).choose(next.ordinal)

        val sheet = overlay.findViewById<View>(R.id.settings_sheet)
        val height = context.resources.getDimensionPixelSize(
            when (next) {
                Tab.TEXT -> R.dimen.type_sheet_text
                Tab.PAGE -> R.dimen.type_sheet_page
                Tab.SCREEN -> R.dimen.type_sheet_screen
            },
        )
        if (sheet.layoutParams.height != height) {
            sheet.layoutParams = sheet.layoutParams.apply { this.height = height }
        }
    }

    private fun visible(shown: Boolean): Int = if (shown) View.VISIBLE else View.GONE

    /**
     * Syncs every cell group to the stored preferences. Pure View work — it moves fills between
     * cells and never touches the page — called whenever the surface opens or a control changes
     * something.
     */
    fun refresh() {
        val p = prefs()

        // Rebuilt, not just re-chosen: the specimens are set at the page's own text size, so a size
        // change makes all three of them wrong until they are drawn again.
        fillFontCells()
        cells(R.id.size_cells).choose(nearestSizeStep(p.textSizePx))
        cells(R.id.spacing_cells).choose(SPACING_STEPS.indexOf(p.lineSpacingMultiplier).coerceAtLeast(0))
        cells(R.id.margin_cells).choose(MARGIN_STEPS.indexOf(p.marginPx).coerceAtLeast(0))

        cells(R.id.justify_cells).choose(onOff(p.justified))
        cells(R.id.hyphen_cells).choose(onOff(p.hyphenated))
        cells(R.id.publisher_cells).choose(onOff(p.publisherStyling))
        cells(R.id.headings_cells).choose(onOff(p.inferHeadings))
        cells(R.id.rotation_lock_cells).choose(onOff(p.rotationLocked))
        cells(R.id.progress_cells).choose(onOff(p.showProgressBar))
        cells(R.id.previews_cells).choose(onOff(p.previewsEnabled))

        cells(R.id.turns_cells).choose(if (p.fasterPageTurns) 1 else 0)

        // Disabled rather than hidden when page turns are clean: hiding a control teaches nothing,
        // while a disabled one teaches the relationship. Enabling draws the border in and darkens
        // the labels — one redraw, and nothing moves, so nothing ghosts.
        cells(R.id.refresh_freq_cells).apply {
            choose(REFRESH_STEPS.indexOf(p.fullRefreshEveryN).coerceAtLeast(0))
            isEnabled = p.fasterPageTurns
        }
        val flashEnabled = p.fasterPageTurns
        text(R.id.refresh_freq_label).setTextColor(
            context.getColor(if (flashEnabled) R.color.reader_text_primary else R.color.mist),
        )
        text(R.id.refresh_freq_note).setTextColor(
            context.getColor(if (flashEnabled) R.color.reader_text_secondary else R.color.mist),
        )

        // The previews line carries live generation progress when there is any, and its resting
        // description otherwise — one line doing both jobs rather than a row that appears and
        // disappears underneath the control it describes.
        text(R.id.previews_generating_text).text = host.previewGenerationProgress()
            ?.let { (generated, total) ->
                context.getString(R.string.previews_generating, generated, total)
            }
            ?: context.getString(R.string.setting_previews_note)

        // Reachable whenever there is something to delete, not only while generation is in
        // flight — see hasPreviewsForCurrentBook.
        overlay.findViewById<View>(R.id.previews_delete_row).visibility =
            if (host.hasPreviewsForCurrentBook()) View.VISIBLE else View.GONE

        // Re-applies the current tab's height. Load-bearing across a rotation: the reader declares
        // configChanges and is never re-inflated, so the landscape type_sheet_* values would
        // otherwise only take effect in whichever orientation the book was opened in — the same
        // trap the chrome's second row fell into.
        showTab(tab)
    }

    /**
     * The step whose size is closest to [px].
     *
     * A pref written by a build with the old ±2px stepper can hold any even value in
     * [TEXT_SIZE_MIN_PX]..[TEXT_SIZE_MAX_PX], including several that are not steps. Snapping for
     * *display* leaves that reader's page exactly as they set it while still filling one cell —
     * their size only changes if they actually tap.
     */
    private fun nearestSizeStep(px: Float): Int {
        val clamped = px.coerceIn(TEXT_SIZE_MIN_PX, TEXT_SIZE_MAX_PX)
        return TEXT_SIZE_STEPS_PX.indices.minByOrNull { i ->
            kotlin.math.abs(TEXT_SIZE_STEPS_PX[i] - clamped)
        } ?: (TEXT_SIZE_STEPS_PX.size / 2)
    }

    /** Every boolean in the app is an ON / OFF pair in the same shape as every other choice. */
    private fun boolean(id: Int, initial: Boolean, flip: () -> Unit) {
        cells(id).apply {
            setCells(
                labels = listOf(
                    context.getString(R.string.cell_on),
                    context.getString(R.string.cell_off),
                ),
                chosen = onOff(initial),
            )
            onChoice = { flip() }
        }
    }

    /** ON is the first cell, so a filled left-hand cell always reads as "this is on". */
    private fun onOff(value: Boolean): Int = if (value) 0 else 1

    private fun sidehead(id: Int, label: Int) {
        overlay.findViewById<SideheadView>(id).apply {
            this.label = context.getString(label)
            form = SideheadView.Form.RULED
        }
    }

    private fun cells(id: Int): CellRowView = overlay.findViewById(id)

    private fun text(id: Int): TextView = overlay.findViewById(id)
}
