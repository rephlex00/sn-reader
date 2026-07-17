package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the pure family-name → font-resource mapping. The actual [android.graphics.Typeface] load
 * (ResourcesCompat.getFont) is thin and device-real; this covers the branch logic that matters —
 * each known family maps to its own resource, and anything else (legacy "serif"/"sans-serif"/
 * "monospace" or an unknown value) resolves to the Literata default rather than throwing.
 */
@RunWith(RobolectricTestRunner::class)
class BundledTypefaceProviderTest {

    @Test
    fun `each bundled family maps to its own font resource`() {
        assertThat(BundledTypefaceProvider.fontResFor("literata")).isEqualTo(R.font.literata)
        assertThat(BundledTypefaceProvider.fontResFor("bitter")).isEqualTo(R.font.bitter)
        assertThat(BundledTypefaceProvider.fontResFor("atkinson")).isEqualTo(R.font.atkinson)
    }

    @Test
    fun `legacy and unknown families fall back to the Literata default`() {
        // The old vocabulary the picker used before bundled fonts, plus anything unrecognized.
        for (legacy in listOf("serif", "sans-serif", "monospace", "", "comic-sans")) {
            assertThat(BundledTypefaceProvider.fontResFor(legacy)).isEqualTo(R.font.literata)
        }
    }

    @Test
    fun `FAMILIES lists exactly the three offered faces`() {
        assertThat(BundledTypefaceProvider.FAMILIES).containsExactly("literata", "bitter", "atkinson").inOrder()
    }
}
