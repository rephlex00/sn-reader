package dev.reader.formats.render

import android.graphics.Typeface

/**
 * Resolves a [RenderConfig.fontFamily] to a Typeface. Bundled reader fonts arrive in a
 * later plan; until then the platform families are enough.
 *
 * The family strings are the exact vocabulary [dev.reader.ui.ReaderPrefs]/the Aa sheet store —
 * `"serif"`, `"sans-serif"`, `"monospace"` (also the CSS/Android family names). This mapping is
 * the other end of that contract: a mismatch here silently renders every choice as serif, so
 * [TypefaceProviderTest] pins these exact strings.
 */
fun interface TypefaceProvider {
    fun get(family: String): Typeface

    companion object {
        val Platform = TypefaceProvider { family ->
            when (family) {
                "sans-serif" -> Typeface.SANS_SERIF
                "monospace" -> Typeface.MONOSPACE
                else -> Typeface.SERIF // "serif" and any unrecognized value
            }
        }
    }
}
