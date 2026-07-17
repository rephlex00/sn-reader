package dev.reader.ui

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import dev.reader.R
import dev.reader.formats.render.TypefaceProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the Aa picker's bundled family names to their loaded [Typeface]s. Lives in `:app`
 * because the font-family resources (`R.font.*`) do, while the [TypefaceProvider] seam it satisfies
 * lives in `:formats`; [ReaderActivity] injects this into the measurer, tests inject
 * [TypefaceProvider.Platform] instead.
 *
 * Each family resource is a font-family XML with the four static cuts (regular/bold/italic/
 * bold-italic), so a `StyleSpan(BOLD)`/`(ITALIC)` on an emphasis run resolves to the real cut, not
 * a synthesized slant.
 *
 * **Thread-safe by construction.** [get] runs on the measurer's thread, which is the main thread
 * for a page turn but a background thread for a prefetch (`EpubDocument.paginate` off-main). The
 * cache is a [ConcurrentHashMap] and [ResourcesCompat.getFont] is a pure read of immutable
 * resources, so concurrent resolves cannot corrupt anything; a family is loaded at most once and
 * the [Typeface] it yields is immutable and safe to share.
 */
class BundledTypefaceProvider(context: Context) : TypefaceProvider {

    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, Typeface>()

    override fun get(family: String): Typeface = cache.computeIfAbsent(family) { load(it) }

    private fun load(family: String): Typeface = try {
        ResourcesCompat.getFont(appContext, fontResFor(family)) ?: Typeface.SERIF
    } catch (e: Exception) {
        // A missing/corrupt bundled font must never crash a chapter build — fall back to a
        // platform serif and let the reader keep working.
        Typeface.SERIF
    }

    companion object {
        /** The bundled family names the Aa picker offers and [ReaderPrefs] stores. */
        val FAMILIES = listOf("literata", "bitter", "atkinson")

        /**
         * The font-family resource for a stored family name. Unknown or legacy values (the old
         * "serif"/"sans-serif"/"monospace", or anything a future build removed) resolve to the
         * default, [R.font.literata] — so a stale pref renders as the default face rather than
         * throwing or blanking. Pure, so the mapping is unit-testable without loading a font.
         */
        @FontRes
        fun fontResFor(family: String): Int = when (family) {
            "bitter" -> R.font.bitter
            "atkinson" -> R.font.atkinson
            else -> R.font.literata // "literata" and any legacy/unknown value
        }
    }
}
