package dev.reader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.SuperscriptSpan
import android.view.View.MeasureSpec
import com.google.common.truth.Truth.assertThat
import dev.reader.engine.Block
import dev.reader.engine.InlineStyle
import dev.reader.engine.Paginator
import dev.reader.engine.RenderConfig
import dev.reader.engine.StyleSpan
import dev.reader.engine.StyledText
import dev.reader.formats.render.AndroidMeasuredChapter
import dev.reader.formats.render.AndroidTextMeasurer
import dev.reader.formats.render.SpannedChapterBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Regression coverage for a device-reported bug (task 32): on a real Nomad panel, reading
 * *Invisible Women*, the FIRST `sup` footnote marker on a rendered page drew at full size on the
 * baseline, while every later marker on the same page drew correctly raised and shrunk — even
 * though the paragraph carrying the broken marker began on the PREVIOUS page (so the marker sits
 * well inside a paragraph, never at its start).
 *
 * This test drives the exact production path end to end — [SpannedChapterBuilder] →
 * [AndroidTextMeasurer] (real bundled Literata font, `justified`/`hyphenated` on, matching the
 * reader's real defaults) → [Paginator] → the real [PageView.draw] — for a paragraph carrying 15
 * `sup` markers spread across three pages, so several pages start mid-paragraph with the first
 * marker on the page nowhere near the paragraph's own start. A [Canvas] subclass records every
 * `drawTextRun` call's paint size, which is how [android.text.style.RelativeSizeSpan] and
 * [SuperscriptSpan] (via a baseline-shifting `translate`, see `TextLine.handleRun`/`drawTextRun`
 * in AOSP) actually surface at draw time.
 *
 * Extensive investigation (see task-32-report.md) could not reproduce the reported symptom
 * through this pipeline: every marker on every page — first or not — drew at the shrunk size on
 * every attempt, including this one. This test is therefore NOT a red-then-green regression test
 * for a fix; there is no code change to pin. It is a permanent guard recording exactly what was
 * verified correct here, so a future change to the builder/measurer/paginator/PageView draw path
 * that broke this invariant would be caught.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageViewSuperscriptAcrossPagesTest {

    /** Records every [drawTextRun] call's rendered text and the paint's text size at draw time. */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val sizesByText = mutableMapOf<String, MutableList<Float>>()
        override fun drawTextRun(
            text: CharSequence, start: Int, end: Int, contextStart: Int, contextEnd: Int,
            x: Float, y: Float, isRtl: Boolean, paint: Paint,
        ) {
            sizesByText.getOrPut(text.subSequence(start, end).toString()) { mutableListOf() } += paint.textSize
            super.drawTextRun(text, start, end, contextStart, contextEnd, x, y, isRtl, paint)
        }
    }

    @Test
    fun `every sup marker on a page draws shrunk, including the first one on a page that starts mid-paragraph`() {
        val context = RuntimeEnvironment.getApplication()
        val typefaces = BundledTypefaceProvider(context)

        // One long paragraph carrying 15 sup markers, mirroring an endnote-heavy nonfiction
        // chapter (Invisible Women's own structure: many <sup> markers inside one running
        // paragraph, each identical apart from the digit).
        val sb = StringBuilder()
        val spans = mutableListOf<StyleSpan>()
        val sentence = "The gender data gap is not usually malicious or even deliberate. "
        for (i in 1..15) {
            sb.append(sentence)
            val markStart = sb.length
            sb.append(i.toString())
            spans += StyleSpan(markStart, sb.length, InlineStyle(superscript = true))
            sb.append(" ")
        }
        val text = StyledText(sb.toString(), spans)

        // The reader's real defaults (ReaderPrefs.DEFAULT_*): justified, hyphenated, Literata,
        // a device-realistic viewport.
        val config = RenderConfig(
            fontFamily = "literata",
            textSizePx = 34f,
            lineSpacingMultiplier = 1.4f,
            marginPx = 72,
            justified = true,
            hyphenated = true,
            viewportWidthPx = 1404,
            viewportHeightPx = 1872,
        )
        val measurer = AndroidTextMeasurer(SpannedChapterBuilder(), typefaces)
        val measured = measurer.measure(listOf(Block.Paragraph(text)), config) as AndroidMeasuredChapter
        val layout = measured.layout

        // A short page height forces the one paragraph to split across several pages.
        val pages = Paginator().paginate(measured, pageHeightPx = 500)
        assertThat(pages.size).isAtLeast(3) // needs >= 2 pages after the first to be meaningful

        val chapterText = SpannedChapterBuilder().build(listOf(Block.Paragraph(text)), config).text
        val supSpans = chapterText.getSpans(0, chapterText.length, SuperscriptSpan::class.java)
            .map { chapterText.getSpanStart(it) to chapterText.getSpanEnd(it) }
            .sortedBy { it.first }

        val bodySize = config.textSizePx
        val expectedMarkerSize = bodySize * 0.75f // SUP_SUB_SIZE_RATIO in SpannedChapterBuilder

        for ((pageIdx, page) in pages.withIndex()) {
            if (pageIdx == 0) continue // only pages that can start mid-paragraph are interesting

            // Exactly ReaderActivity.showPage's call into PageView.
            val view = PageView(context)
            view.show(layout, page, marginPx = config.marginPx)
            val w = MeasureSpec.makeMeasureSpec(config.viewportWidthPx, MeasureSpec.EXACTLY)
            val h = MeasureSpec.makeMeasureSpec(config.viewportHeightPx, MeasureSpec.EXACTLY)
            view.measure(w, h)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)

            val bitmap = Bitmap.createBitmap(config.viewportWidthPx, config.viewportHeightPx, Bitmap.Config.ARGB_8888)
            val canvas = RecordingCanvas(bitmap)
            view.draw(canvas)

            val markersOnPage = supSpans.filter { it.first >= page.startOffset && it.second <= page.endOffset }
            if (markersOnPage.isEmpty()) continue
            // This page's paragraph began on a previous page (it's one paragraph, split across
            // pages by height alone) — the same condition the device repro described.
            assertThat(page.startOffset).isGreaterThan(0)

            for ((i, range) in markersOnPage.withIndex()) {
                val marker = chapterText.subSequence(range.first, range.second).toString()
                val drawnSizes = canvas.sizesByText[marker]
                assertThat(drawnSizes).isNotNull()
                assertThat(drawnSizes).containsExactly(expectedMarkerSize)
                // The specific claim from the device report: the FIRST marker on the page (i == 0)
                // must be exactly as shrunk as the later ones (i > 0) — not full body size.
                if (i == 0) assertThat(drawnSizes!!.single()).isNotEqualTo(bodySize)
            }
        }
    }
}
