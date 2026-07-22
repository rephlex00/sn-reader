package dev.reader.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import dev.reader.R

// The Aa sheet's bounded value sets, kept beside the controls that offer them. Text size is a
// stepper over [TEXT_SIZE_MIN_PX, TEXT_SIZE_MAX_PX] by TEXT_SIZE_STEP_PX; the margins are presets.
// All chosen so the resulting RenderConfig stays valid on the device viewport — margins are
// additionally clamped per-viewport, since a preset that fits in portrait need not fit in a
// landscape spread once the gutter is taken out.
internal const val TEXT_SIZE_MIN_PX = 24f
internal const val TEXT_SIZE_MAX_PX = 56f
internal const val TEXT_SIZE_STEP_PX = 2f
internal const val MARGIN_NARROW_PX = 40
internal const val MARGIN_MEDIUM_PX = 72
internal const val MARGIN_WIDE_PX = 120

/**
 * What the Aa sheet asks the reader to do. Every method is an ACTION, never a widget: the sheet
 * knows which control was tapped, the reader knows what tapping it means.
 *
 * That split is the point. Changing typography has to re-paginate the current chapter and land the
 * reader on the same words (see `ReaderActivity.applySettingsChange`), which needs the open
 * document, the measured viewport and the reading position — none of which a sheet should hold. The
 * display-only switches, by contrast, must NOT re-paginate, and keeping both kinds behind one
 * interface is what stops a future control from quietly picking the wrong one.
 */
internal interface SettingsHost {

    /** Writes a typography preference and re-paginates, preserving the reader's place. */
    fun applyTypography(mutate: (ReaderPrefs) -> Unit)

    /** Steps the text size by [deltaPx], clamped to the supported range. */
    fun stepTextSize(deltaPx: Float)

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

    /** For the Aa readout: `(chapters generated so far, total chapters)` while a strip is actively
     *  generating for the open book, or null when there is nothing to report (previews off, a strip
     *  already loaded, or no generation in flight). */
    fun previewGenerationProgress(): Pair<Int, Int>?
}

/**
 * The Aa sheet: the panel of typography and display controls, and the code that keeps them showing
 * the live values.
 *
 * This is deliberately only view binding — wiring twenty controls and syncing twenty controls. It
 * decides nothing; every tap goes straight to [SettingsHost]. That was the bulk of what the Aa
 * feature contributed to [ReaderActivity], and it is the least interesting code there: mechanical,
 * repetitive, and in the way of the reading logic it sat between.
 *
 * Holds no state but [fontPreviewsLoaded]. Showing and hiding the sheet is [ReaderActivity]'s job,
 * a single `visibility` flip; it calls [refresh] on the way in.
 */
internal class SettingsSheet(
    private val overlay: View,
    private val host: SettingsHost,
    private val prefs: () -> ReaderPrefs,
) {

    private val context: Context get() = overlay.context

    /** Font previews are loaded once, on first open — see [loadFontPreviewsOnce]. */
    private var fontPreviewsLoaded = false

    /**
     * Wires every control to its action. Called once; the listeners hold no state and fire only on a
     * deliberate tap, so they cost nothing at rest.
     */
    fun wire() {
        onClick(R.id.font_literata) { host.applyTypography { p -> p.fontFamily = "literata" } }
        onClick(R.id.font_bitter) { host.applyTypography { p -> p.fontFamily = "bitter" } }
        onClick(R.id.font_atkinson) { host.applyTypography { p -> p.fontFamily = "atkinson" } }
        // The per-option preview typefaces are loaded lazily on first sheet-open, not here — see
        // loadFontPreviewsOnce. Loading three font families synchronously at every book open (even
        // for a reader who never touches the Aa sheet) would be cold-open work for nothing.

        onClick(R.id.size_minus) { host.stepTextSize(-TEXT_SIZE_STEP_PX) }
        onClick(R.id.size_plus) { host.stepTextSize(TEXT_SIZE_STEP_PX) }

        onClick(R.id.spacing_12) { host.applyTypography { p -> p.lineSpacingMultiplier = 1.2f } }
        onClick(R.id.spacing_14) { host.applyTypography { p -> p.lineSpacingMultiplier = 1.4f } }
        onClick(R.id.spacing_16) { host.applyTypography { p -> p.lineSpacingMultiplier = 1.6f } }

        onClick(R.id.margin_narrow) { host.applyMarginPreset(MARGIN_NARROW_PX) }
        onClick(R.id.margin_medium) { host.applyMarginPreset(MARGIN_MEDIUM_PX) }
        onClick(R.id.margin_wide) { host.applyMarginPreset(MARGIN_WIDE_PX) }

        onClick(R.id.toggle_justify) { host.applyTypography { p -> p.justified = !p.justified } }
        onClick(R.id.toggle_hyphen) { host.applyTypography { p -> p.hyphenated = !p.hyphenated } }
        onClick(R.id.toggle_publisher) { host.applyTypography { p -> p.publisherStyling = !p.publisherStyling } }
        onClick(R.id.toggle_headings) { host.applyTypography { p -> p.inferHeadings = !p.inferHeadings } }

        onClick(R.id.toggle_progress) { host.toggleProgressBar() }
        onClick(R.id.toggle_rotation_lock) { host.toggleRotationLock() }
        onClick(R.id.toggle_faster_turns) { host.toggleFasterTurns() }
        onClick(R.id.refresh_freq_3) { host.applyRefreshFrequency(3) }
        onClick(R.id.refresh_freq_6) { host.applyRefreshFrequency(6) }
        onClick(R.id.refresh_freq_10) { host.applyRefreshFrequency(10) }

        onClick(R.id.toggle_previews) { host.togglePreviews() }
        onClick(R.id.previews_delete) { host.deletePreviewsForCurrentBook(); refresh() }
    }

    /**
     * Syncs every control to the stored preferences: the selected option in each group gets a boxed
     * outline, the size readout shows the current px, and each switch reflects its boolean. Pure
     * View work, called whenever the sheet opens or a control changes something.
     */
    fun refresh() {
        val p = prefs()

        setSelected(R.id.font_literata, p.fontFamily == "literata")
        setSelected(R.id.font_bitter, p.fontFamily == "bitter")
        setSelected(R.id.font_atkinson, p.fontFamily == "atkinson")

        text(R.id.size_value).text = context.getString(R.string.text_size_value, p.textSizePx.toInt())

        setSelected(R.id.spacing_12, p.lineSpacingMultiplier == 1.2f)
        setSelected(R.id.spacing_14, p.lineSpacingMultiplier == 1.4f)
        setSelected(R.id.spacing_16, p.lineSpacingMultiplier == 1.6f)

        setSelected(R.id.margin_narrow, p.marginPx == MARGIN_NARROW_PX)
        setSelected(R.id.margin_medium, p.marginPx == MARGIN_MEDIUM_PX)
        setSelected(R.id.margin_wide, p.marginPx == MARGIN_WIDE_PX)

        setToggle(R.id.toggle_justify_switch, p.justified)
        setToggle(R.id.toggle_hyphen_switch, p.hyphenated)
        setToggle(R.id.toggle_publisher_switch, p.publisherStyling)
        setToggle(R.id.toggle_headings_switch, p.inferHeadings)
        setToggle(R.id.toggle_progress_switch, p.showProgressBar)
        setToggle(R.id.toggle_rotation_lock_switch, p.rotationLocked)

        setToggle(R.id.toggle_faster_turns_switch, p.fasterPageTurns)
        overlay.findViewById<View>(R.id.refresh_frequency_row).visibility =
            if (p.fasterPageTurns) View.VISIBLE else View.GONE
        setSelected(R.id.refresh_freq_3, p.fullRefreshEveryN == 3)
        setSelected(R.id.refresh_freq_6, p.fullRefreshEveryN == 6)
        setSelected(R.id.refresh_freq_10, p.fullRefreshEveryN == 10)

        setToggle(R.id.toggle_previews_switch, p.previewsEnabled)
        val progressRow = overlay.findViewById<View>(R.id.previews_status_row)
        host.previewGenerationProgress()?.let { (generated, total) ->
            text(R.id.previews_generating_text).text =
                context.getString(R.string.previews_generating, generated, total)
            progressRow.visibility = View.VISIBLE
        } ?: run {
            progressRow.visibility = View.GONE
        }
    }

    /**
     * Shows each font option in its own face, so the picker previews the fonts before selection.
     * Deferred to first sheet-open and done once — loading three font families is real work that a
     * reader who never opens the Aa sheet should not pay at every book open.
     */
    fun loadFontPreviewsOnce() {
        if (fontPreviewsLoaded) return
        fontPreviewsLoaded = true
        text(R.id.font_literata).typeface = ResourcesCompat.getFont(context, R.font.literata)
        text(R.id.font_bitter).typeface = ResourcesCompat.getFont(context, R.font.bitter)
        text(R.id.font_atkinson).typeface = ResourcesCompat.getFont(context, R.font.atkinson)
    }

    private fun onClick(id: Int, action: () -> Unit) =
        overlay.findViewById<View>(id).setOnClickListener { action() }

    private fun text(id: Int): TextView = overlay.findViewById(id)

    /**
     * A boxed outline (not bold weight) marks the selection. It is typeface-independent, so it works
     * with the font options that preview their own face without disturbing that face, and — crucially
     * — it clears cleanly. The old `setTypeface(view.typeface, NORMAL)` could not strip bold back off
     * a bundled font's already-bold instance, so de-selecting silently failed and every font
     * eventually rendered as selected. `0` clears the background.
     */
    private fun setSelected(id: Int, selected: Boolean) =
        text(id).setBackgroundResource(if (selected) R.drawable.aa_option_selected else 0)

    private fun setToggle(switchId: Int, on: Boolean) {
        overlay.findViewById<ToggleSwitchView>(switchId).checked = on
    }
}
