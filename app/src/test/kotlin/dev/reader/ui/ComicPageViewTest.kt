package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComicPageViewTest {
    @Test fun `a tall page is letterboxed to view width`() {
        // 1000x2000 image into a 1404x1872 view: width-bound? height ratio 0.936 vs width ratio 1.404
        val r = fitRect(1000, 2000, 1404, 1872)
        assertThat(r.width()).isAtMost(1404)
        assertThat(r.height()).isAtMost(1872)
        // aspect preserved (within 1px rounding)
        assertThat(r.width().toFloat() / r.height()).isWithin(0.01f).of(0.5f)
    }

    @Test fun `centered within the view`() {
        val r = fitRect(1000, 1000, 1404, 1872)
        assertThat(r.centerX()).isEqualTo(702)
        assertThat(r.centerY()).isEqualTo(936)
    }

    // The Supernote EPD displays the software framebuffer; on a hardware-accelerated canvas a
    // decoded page composites to a GPU surface the panel never shows (verified on-device: the page
    // rendered blank until this was forced). Lock the software layer against regression.
    @Test fun `renders on a software layer so the e-ink panel can display it`() {
        val view = ComicPageView(org.robolectric.RuntimeEnvironment.getApplication())
        assertThat(view.layerType).isEqualTo(android.view.View.LAYER_TYPE_SOFTWARE)
    }
}
