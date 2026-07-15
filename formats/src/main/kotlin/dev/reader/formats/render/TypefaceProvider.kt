package dev.reader.formats.render

import android.graphics.Typeface

/**
 * Resolves a RenderConfig font family to a Typeface. Bundled reader fonts arrive in a
 * later plan; until then the platform families are enough.
 */
fun interface TypefaceProvider {
    fun get(family: String): Typeface

    companion object {
        val Platform = TypefaceProvider { family ->
            when (family) {
                "sans" -> Typeface.SANS_SERIF
                "mono" -> Typeface.MONOSPACE
                else -> Typeface.SERIF
            }
        }
    }
}
