package dev.reader.ui

import android.content.Context
import dev.reader.engine.RenderConfig

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
    fun renderConfig(viewportWidthPx: Int, viewportHeightPx: Int): RenderConfig = RenderConfig(
        fontFamily = fontFamily,
        textSizePx = textSizePx,
        lineSpacingMultiplier = lineSpacingMultiplier,
        marginPx = marginPx.coerceIn(0, (minOf(viewportWidthPx, viewportHeightPx) - 1) / 2),
        justified = justified,
        hyphenated = hyphenated,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        inferHeadings = inferHeadings,
        publisherStyling = publisherStyling,
    )

    private companion object {
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_TEXT_SIZE_PX = "text_size_px"
        const val KEY_LINE_SPACING = "line_spacing_multiplier"
        const val KEY_MARGIN_PX = "margin_px"
        const val KEY_JUSTIFIED = "justified"
        const val KEY_HYPHENATED = "hyphenated"
        const val KEY_INFER_HEADINGS = "infer_headings"
        const val KEY_PUBLISHER_STYLING = "publisher_styling"

        // Defaults MUST equal the literals openFirstBook used to hardcode — the no-op guarantee.
        const val DEFAULT_FONT_FAMILY = "literata"
        const val DEFAULT_TEXT_SIZE_PX = 34f
        const val DEFAULT_LINE_SPACING = 1.4f
        const val DEFAULT_MARGIN_PX = 48
        const val DEFAULT_JUSTIFIED = true
        const val DEFAULT_HYPHENATED = true
        const val DEFAULT_INFER_HEADINGS = true
        const val DEFAULT_PUBLISHER_STYLING = true
    }
}
