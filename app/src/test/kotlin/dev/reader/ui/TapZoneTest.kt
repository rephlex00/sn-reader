package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TapZoneTest {

    @Test
    fun `left quarter goes back`() {
        assertThat(tapZoneFor(x = 10f, width = 1000)).isEqualTo(TapZone.PREVIOUS)
        assertThat(tapZoneFor(x = 249f, width = 1000)).isEqualTo(TapZone.PREVIOUS)
    }

    @Test
    fun `right forty percent goes forward`() {
        assertThat(tapZoneFor(x = 600f, width = 1000)).isEqualTo(TapZone.NEXT)
        assertThat(tapZoneFor(x = 999f, width = 1000)).isEqualTo(TapZone.NEXT)
    }

    @Test
    fun `the middle toggles the overlay`() {
        assertThat(tapZoneFor(x = 400f, width = 1000)).isEqualTo(TapZone.TOGGLE_OVERLAY)
    }

    @Test
    fun `the left quarter boundary is exclusive`() {
        // fraction == 0.25 exactly must NOT go PREVIOUS (left 25% is the half-open [0, 0.25)),
        // pinning this boundary the same way the 0.60 boundary is pinned above.
        assertThat(tapZoneFor(x = 250f, width = 1000)).isEqualTo(TapZone.TOGGLE_OVERLAY)
    }

    @Test
    fun `zone boundaries are stable across widths`() {
        assertThat(tapZoneFor(x = 350f, width = 1404)).isEqualTo(TapZone.PREVIOUS)
        assertThat(tapZoneFor(x = 843f, width = 1404)).isEqualTo(TapZone.NEXT)
    }

    @Test
    fun `a zero width never crashes`() {
        assertThat(tapZoneFor(x = 0f, width = 0)).isEqualTo(TapZone.TOGGLE_OVERLAY)
    }
}
