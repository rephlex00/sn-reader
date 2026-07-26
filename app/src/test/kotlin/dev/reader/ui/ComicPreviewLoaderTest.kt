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
// which would hide a repeat of the blank-page bug (ComicPageDecoder's bounds-pass elvis trap) if it
// ever crept back in through this loader. Real native decoding reproduces the true semantics.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicPreviewLoaderTest {

    private fun png(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    @Test
    fun `previews a page smaller than the source, proving downsampling happened`() = runBlocking {
        val bytes = png(1272, 1754)
        val loader = ComicPreviewLoader(ComicPageDecoder())

        val bmp = loader.preview(0) { bytes.inputStream() }

        assertThat(bmp).isNotNull()
        assertThat(bmp!!.width).isLessThan(1272)
        assertThat(bmp.height).isLessThan(1754)
    }

    @Test
    fun `a second request for the same page does not re-open the stream`() = runBlocking {
        val bytes = png(1272, 1754)
        val loader = ComicPreviewLoader(ComicPageDecoder())
        var opens = 0
        val provider = { opens++; bytes.inputStream() }

        loader.preview(3, provider)
        val opensAfterFirstDecode = opens
        loader.preview(3, provider)

        assertThat(opensAfterFirstDecode).isEqualTo(2) // bounds pass + pixel pass, per ComicPageDecoder
        assertThat(opens).isEqualTo(opensAfterFirstDecode) // cache hit: no further stream opens
    }

    @Test
    fun `the LRU evicts beyond cacheSize and recycles what it evicts`() = runBlocking {
        val loader = ComicPreviewLoader(ComicPageDecoder(), cacheSize = 2)
        val bytes = png(1272, 1754)

        val first = loader.preview(0) { bytes.inputStream() }!!
        loader.preview(1) { bytes.inputStream() }
        assertThat(first.isRecycled).isFalse()
        loader.preview(2) { bytes.inputStream() } // over capacity: evicts page 0, the least recently used

        assertThat(first.isRecycled).isTrue()
    }

    @Test
    fun `a null stream returns null without throwing`() = runBlocking {
        val loader = ComicPreviewLoader(ComicPageDecoder())

        assertThat(loader.preview(0) { null }).isNull()
    }

    @Test
    fun `clear recycles and drops everything`() = runBlocking {
        val loader = ComicPreviewLoader(ComicPageDecoder())
        val bytes = png(1272, 1754)
        val bmp = loader.preview(0) { bytes.inputStream() }!!

        loader.clear()

        assertThat(bmp.isRecycled).isTrue()
    }
}
