package dev.reader.ui

import android.content.Context
import dev.reader.engine.COLUMN_GAP_PX
import dev.reader.engine.RenderConfig
import dev.reader.engine.columnCountFor

/** The full-refresh cadence choices offered in the Aa sheet (pages between clean refreshes in
 *  "Faster page turns" mode). The single source of truth for both the pref clamp and the UI cells. */
val REFRESH_FREQUENCY_OPTIONS: List<Int> = listOf(3, 6, 10)

/**
 * The reader's persisted typography, a thin typed wrapper over one
 * `SharedPreferences("reader_prefs")` — a separate file from [LibraryPrefs] so the two stores
 * never collide. It is the single source of truth for the [RenderConfig] fields that
 * [ReaderActivity] used to hardcode inline, so the choice now survives process death.
 *
 * Every default equals the literal it replaced (see [renderConfig]), so an untouched install
 * renders exactly as before this class existed — the persistence is a behavior no-op until the
 * Aa sheet writes a value. Reads and writes are cheap main-thread key-values, as [LibraryPrefs]'s
 * are: `apply()` defers the only I/O off the caller's thread, so nothing here costs the idle
 * promise or adds latency to a page turn.
 *
 * The viewport is deliberately absent: it is measured from the view on each open, not a stored
 * preference, so [renderConfig] takes it as an argument rather than reading it back.
 */
class ReaderPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    /** A bundled reader face — one of [BundledTypefaceProvider.FAMILIES] ("literata"/"bitter"/
     * "atkinson"). A legacy or unknown value resolves to the default face at render time (see
     * [BundledTypefaceProvider.fontResFor]); the store itself is a dumb string. */
    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, null) ?: DEFAULT_FONT_FAMILY
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

    var textSizePx: Float
        get() = prefs.getFloat(KEY_TEXT_SIZE_PX, DEFAULT_TEXT_SIZE_PX)
        set(value) = prefs.edit().putFloat(KEY_TEXT_SIZE_PX, value).apply()

    var lineSpacingMultiplier: Float
        get() = prefs.getFloat(KEY_LINE_SPACING, DEFAULT_LINE_SPACING)
        set(value) = prefs.edit().putFloat(KEY_LINE_SPACING, value).apply()

    var marginPx: Int
        get() = prefs.getInt(KEY_MARGIN_PX, DEFAULT_MARGIN_PX)
        set(value) = prefs.edit().putInt(KEY_MARGIN_PX, value).apply()

    var justified: Boolean
        get() = prefs.getBoolean(KEY_JUSTIFIED, DEFAULT_JUSTIFIED)
        set(value) = prefs.edit().putBoolean(KEY_JUSTIFIED, value).apply()

    var hyphenated: Boolean
        get() = prefs.getBoolean(KEY_HYPHENATED, DEFAULT_HYPHENATED)
        set(value) = prefs.edit().putBoolean(KEY_HYPHENATED, value).apply()

    var inferHeadings: Boolean
        get() = prefs.getBoolean(KEY_INFER_HEADINGS, DEFAULT_INFER_HEADINGS)
        set(value) = prefs.edit().putBoolean(KEY_INFER_HEADINGS, value).apply()

    var publisherStyling: Boolean
        get() = prefs.getBoolean(KEY_PUBLISHER_STYLING, DEFAULT_PUBLISHER_STYLING)
        set(value) = prefs.edit().putBoolean(KEY_PUBLISHER_STYLING, value).apply()

    /** Whether the whole-book progress bar is drawn at the bottom of the page. A pure display
     * toggle: deliberately NOT part of [renderConfig], since it changes nothing about pagination. */
    var showProgressBar: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PROGRESS_BAR, DEFAULT_SHOW_PROGRESS_BAR)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_PROGRESS_BAR, value).apply()

    /** Whether page turns use the fast path with a full clean refresh only every [fullRefreshEveryN]
     *  turns. Off by default → every turn is a full clean refresh. A pure display/refresh toggle, NOT
     *  part of [renderConfig] (it never re-paginates). */
    var fasterPageTurns: Boolean
        get() = prefs.getBoolean(KEY_FASTER_PAGE_TURNS, DEFAULT_FASTER_PAGE_TURNS)
        set(value) = prefs.edit().putBoolean(KEY_FASTER_PAGE_TURNS, value).apply()

    /** Pages between full clean refreshes when [fasterPageTurns] is on. Clamped to
     *  [REFRESH_FREQUENCY_OPTIONS] on read so a legacy/corrupt value can never set a nonsense cadence. */
    var fullRefreshEveryN: Int
        get() = prefs.getInt(KEY_FULL_REFRESH_EVERY_N, DEFAULT_FULL_REFRESH_EVERY_N)
            .let { if (it in REFRESH_FREQUENCY_OPTIONS) it else DEFAULT_FULL_REFRESH_EVERY_N }
        set(value) = prefs.edit().putInt(KEY_FULL_REFRESH_EVERY_N, value).apply()

    /**
     * Whether the reader ignores the device's rotation sensor and stays in the orientation it is
     * currently in. Off by default, so a fresh install follows the sensor (itself subject to the
     * system auto-rotate setting). Like [showProgressBar] this is deliberately NOT part of
     * [renderConfig]: it changes which viewport the reader is handed, never how one is laid out.
     */
    var rotationLocked: Boolean
        get() = prefs.getBoolean(KEY_ROTATION_LOCKED, DEFAULT_ROTATION_LOCKED)
        set(value) = prefs.edit().putBoolean(KEY_ROTATION_LOCKED, value).apply()

    /** Whether the chapter scrubber's thumbnail strip is generated and shown at all. On by default;
     *  a pure display/generation toggle, NOT part of [renderConfig]. */
    var previewsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PREVIEWS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PREVIEWS_ENABLED, value).apply()

    /**
     * Builds the [RenderConfig] for one open: the stored typography plus the viewport the view
     * just measured. The pure prefs+viewport→config mapping, factored out so its no-op equivalence
     * to the old hardcoded literals is unit-testable without an Activity (see [ReaderPrefsTest]).
     *
     * The stored margin is clamped to leave at least one content pixel on the measured viewport, so
     * [RenderConfig]'s `contentWidth/Height > 0` init requirement can never throw here regardless of
     * caller. The Aa sheet already clamps on write, but centralizing it here defends the open path
     * too (and any future viewport that shrinks below a previously-stored margin). On a real panel
     * every shipped preset (≤80) is far below the limit (~701), so this is pure defense.
     */
    fun renderConfig(
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        bottomChromePx: Int = 0,
    ): RenderConfig {
        val columns = columnCountFor(viewportWidthPx, viewportHeightPx)
        // The gutter eats width the margin clamp below would otherwise be free to spend. Shrink it
        // (to nothing, in the limit) rather than letting it push the column width to zero, which is
        // the one thing RenderConfig throws on. No real viewport comes close — a landscape Nomad has
        // ~1.7k px for a 140px gutter — this only keeps a degenerate viewport from crashing the open.
        val gap = if (columns > 1) {
            COLUMN_GAP_PX.coerceIn(0, ((viewportWidthPx - columns) / (columns - 1)).coerceAtLeast(0))
        } else {
            COLUMN_GAP_PX // unused at one column (multiplied by columns - 1), kept constant so
            // portrait configs differ from landscape ones only in the fields that matter.
        }
        // Leave at least one pixel of COLUMN width, not just of content width: with two columns the
        // margin has to clear the gutter as well as both edges.
        val widthLimit = (viewportWidthPx - gap * (columns - 1) - columns) / 2
        val margin = marginPx.coerceIn(0, minOf((minOf(viewportWidthPx, viewportHeightPx) - 1) / 2, widthLimit).coerceAtLeast(0))
        // The progress bar and running foot are drawn into the bottom margin band, and nothing in
        // the draw path enforces clearance — so before this, whether the foot overdrew the last
        // line was decided by whether the chosen margin happened to be deeper than the foot. At
        // the narrow preset it was not, and every increase in foot size ate further into the text.
        // Taking the shortfall out of the text area instead makes the two independent: the foot
        // can be sized for legibility without the margin preset having a vote.
        // Capped at half the content height. On any real viewport the cap is nowhere near binding
        // (the band is ~57px against ~1700px of content), but without it a degenerate viewport
        // could see the whole text area reserved away, leaving a 1px content height that paginates
        // into an absurd number of pages. Reserving less is the right failure, never inflating
        // viewportHeightPx to make room — that would hand the paginator a viewport that does not
        // exist.
        val contentHeight = viewportHeightPx - margin * 2
        val reserved = (bottomChromePx - margin).coerceIn(0, (contentHeight / 2).coerceAtLeast(0))
        return RenderConfig(
            fontFamily = fontFamily,
            textSizePx = textSizePx,
            lineSpacingMultiplier = lineSpacingMultiplier,
            marginPx = margin,
            justified = justified,
            hyphenated = hyphenated,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx - reserved,
            inferHeadings = inferHeadings,
            publisherStyling = publisherStyling,
            // Not a stored preference — the viewport's own shape decides it, so rotating the device
            // is all it takes to read in two columns and there is no setting to get out of sync
            // with the panel. See columnCountFor.
            columnCount = columns,
            columnGapPx = gap,
        )
    }

    private companion object {
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_TEXT_SIZE_PX = "text_size_px"
        const val KEY_LINE_SPACING = "line_spacing_multiplier"
        const val KEY_MARGIN_PX = "margin_px"
        const val KEY_JUSTIFIED = "justified"
        const val KEY_HYPHENATED = "hyphenated"
        const val KEY_INFER_HEADINGS = "infer_headings"
        const val KEY_PUBLISHER_STYLING = "publisher_styling"
        const val KEY_SHOW_PROGRESS_BAR = "show_progress_bar"
        const val KEY_FASTER_PAGE_TURNS = "faster_page_turns"
        const val KEY_FULL_REFRESH_EVERY_N = "full_refresh_every_n"
        const val KEY_ROTATION_LOCKED = "rotation_locked"
        const val KEY_PREVIEWS_ENABLED = "previews_enabled"

        // The reader's standing typography baseline. All but the font matched openFirstBook's old
        // hardcoded literals; the font default became "literata" when bundled fonts shipped.
        const val DEFAULT_FONT_FAMILY = "literata"
        const val DEFAULT_TEXT_SIZE_PX = 34f
        const val DEFAULT_LINE_SPACING = 1.4f
        const val DEFAULT_MARGIN_PX = 72
        const val DEFAULT_JUSTIFIED = true
        const val DEFAULT_HYPHENATED = true
        const val DEFAULT_INFER_HEADINGS = true
        const val DEFAULT_PUBLISHER_STYLING = true
        const val DEFAULT_SHOW_PROGRESS_BAR = true
        const val DEFAULT_FASTER_PAGE_TURNS = false
        const val DEFAULT_FULL_REFRESH_EVERY_N = 6
        const val DEFAULT_ROTATION_LOCKED = false
    }
}
