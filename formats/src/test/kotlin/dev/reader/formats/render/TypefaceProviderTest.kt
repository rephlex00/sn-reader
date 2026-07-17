package dev.reader.formats.render

import android.graphics.Typeface
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the family-string contract between [dev.reader.ui.ReaderPrefs]/the Aa sheet (which store
 * "serif"/"sans-serif"/"monospace") and [TypefaceProvider.Platform] (which maps them to a
 * Typeface). The two ends once disagreed — the provider matched "sans"/"mono", so every choice
 * silently rendered as serif — which is exactly the regression these assertions catch.
 */
@RunWith(RobolectricTestRunner::class)
class TypefaceProviderTest {

    private val provider = TypefaceProvider.Platform

    @Test
    fun `the exact strings the Aa sheet stores map to distinct platform families`() {
        assertThat(provider.get("serif")).isEqualTo(Typeface.SERIF)
        assertThat(provider.get("sans-serif")).isEqualTo(Typeface.SANS_SERIF)
        assertThat(provider.get("monospace")).isEqualTo(Typeface.MONOSPACE)
        // The three must actually differ — a mapping that collapsed them would be the bug.
        assertThat(Typeface.SANS_SERIF).isNotEqualTo(Typeface.SERIF)
        assertThat(Typeface.MONOSPACE).isNotEqualTo(Typeface.SERIF)
    }

    @Test
    fun `an unrecognized family falls back to serif`() {
        assertThat(provider.get("comic-sans")).isEqualTo(Typeface.SERIF)
        assertThat(provider.get("")).isEqualTo(Typeface.SERIF)
    }
}
