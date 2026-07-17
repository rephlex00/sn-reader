package dev.reader.formats.render

import android.graphics.Typeface

/**
 * Resolves a [dev.reader.engine.RenderConfig] font family to a Typeface — the seam between the
 * `:formats` measurer and whatever supplies fonts. Production uses `:app`'s `BundledTypefaceProvider`
 * (the bundled OFL faces the Aa picker offers, e.g. `"literata"`).
 *
 * [Platform] is the fallback used by tests and by non-rendering opens (metadata/cover extraction)
 * that never actually resolve a font. It maps the generic `"serif"`/`"sans-serif"`/`"monospace"`
 * family names (the CSS/Android names) to platform typefaces; [TypefaceProviderTest] pins that
 * mapping (a mismatch there once rendered every choice as serif).
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
