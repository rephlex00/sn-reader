package dev.reader.ui

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// NATIVE graphics: the shadow BitmapFactory returns a bitmap even for an inJustDecodeBounds pass,
// which hides the exact production bug this file guards (see the two-pass test). Real native
// decoding reproduces the true semantics — a bounds pass returns null.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicPageDecoderTest {
    @Test fun `sample size downsamples while either dim stays at or above target`() {
        assertThat(computeSampleSize(3000, 4000, 1404, 1872)).isEqualTo(2) // 1500x2000 >= target
        assertThat(computeSampleSize(1272, 1754, 1404, 1872)).isEqualTo(1) // already smaller
        assertThat(computeSampleSize(0, 0, 1404, 1872)).isEqualTo(1)       // guard
        // Extreme aspect: long axis must be downsampled or a full-res decode risks OOM.
        assertThat(computeSampleSize(30000, 200, 1404, 1872)).isEqualTo(16)
    }

    private fun png(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    @Test fun `decodes a downsampled bitmap`() = runBlocking {
        val bytes = png(2808, 3744) // 2x the panel
        val bmp = ComicPageDecoder().decode({ bytes.inputStream() }, 1404, 1872)
        assertThat(bmp).isNotNull()
        assertThat(bmp!!.width).isAtMost(1404)
    }

    @Test fun `a null stream decodes to null, never throws`() = runBlocking {
        assertThat(ComicPageDecoder().decode({ null }, 1404, 1872)).isNull()
    }

    /**
     * The bounds pass must NOT abort the decode. `BitmapFactory.decodeStream` returns null by
     * design when `inJustDecodeBounds` is set, so an elvis on that call (`?: return null`) aborts
     * every decode — which blanked every comic page on-device while this suite stayed green,
     * because Robolectric's ShadowBitmapFactory hands back a bitmap even in bounds-only mode.
     * Assert the shape the shadow cannot fake: a real two-pass decode opens the stream TWICE
     * (once for bounds, once for pixels). The buggy version stopped after one.
     */
    @Test fun `decoding makes both passes, opening the stream twice`() = runBlocking {
        val bytes = png(2808, 3744)
        var opens = 0
        ComicPageDecoder().decode({ opens++; bytes.inputStream() }, 1404, 1872)
        assertThat(opens).isEqualTo(2)
    }
}
