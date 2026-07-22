package dev.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The snap detent: within [radius] of a chapter-start tick, the fraction magnetizes to the tick —
 * landing exactly on a chapter opening is what a scrubber is mostly FOR, and a fingertip cannot hit
 * a 2px target unaided. Outside the radius the raw fraction passes through untouched, so the detent
 * is escapable by simply dragging on (a detent, not a wall). Nearest tick wins when two are in
 * range. Pure — JVM-tested.
 */
fun snappedFraction(raw: Float, chapterStarts: List<Float>, radius: Float = 0.015f): Float {
    val nearest = chapterStarts.minByOrNull { kotlin.math.abs(it - raw) } ?: return raw
    return if (kotlin.math.abs(nearest - raw) <= radius) nearest else raw
}

/** The index of the chapter-start tick the raw fraction snaps to (nearest within [radius]), or null
 *  when it snaps to none. Index-aligned with [chapterStarts], which setBook fills one-per-spine, so
 *  the index IS the spine chapter — carried through the callbacks so a snapped commit lands on that
 *  chapter's first page instead of resolving the boundary fraction to the previous chapter. */
fun snappedChapterIndex(raw: Float, chapterStarts: List<Float>, radius: Float = 0.015f): Int? {
    val nearest = chapterStarts.indices.minByOrNull { kotlin.math.abs(chapterStarts[it] - raw) } ?: return null
    return if (kotlin.math.abs(chapterStarts[nearest] - raw) <= radius) nearest else null
}

/**
 * Collapses bookmark fractions closer than [mergeRadius] into one glyph position (their mean):
 * three bookmarks on adjacent pages are one place in the book at timeline resolution, and three
 * overlapping glyphs would render as smudge. Pure — JVM-tested.
 */
fun mergedBookmarkFractions(fractions: List<Float>, mergeRadius: Float = 0.02f): List<Float> {
    if (fractions.isEmpty()) return emptyList()
    val sorted = fractions.sorted()
    val merged = mutableListOf<MutableList<Float>>(mutableListOf(sorted.first()))
    for (f in sorted.drop(1)) {
        if (f - merged.last().last() <= mergeRadius) merged.last().add(f) else merged.add(mutableListOf(f))
    }
    return merged.map { cluster -> cluster.sum() / cluster.size }
}

/** One stretch of the scrubber track between two chapter ticks. [solid] draws a solid line (its
 *  chapter's preview exists, or previews are off/complete); otherwise a dashed line (that chapter's
 *  preview is still being generated). Pure geometry — JVM-tested; the dashing is Canvas work. */
data class TrackSegment(val startX: Float, val endX: Float, val solid: Boolean)

/**
 * Splits the track [leftX,rightX] into one segment per chapter (bounded by consecutive chapter-start
 * fractions, last chapter running to [rightX]). A segment is solid when [allSolid] is true (previews
 * off, or fully generated) or its chapter index is in [generated]; otherwise dashed. With no chapter
 * starts, one solid full-width segment — a plain visible track.
 */
fun trackSegments(
    chapterStarts: List<Float>,
    generated: Set<Int>,
    allSolid: Boolean,
    leftX: Float,
    rightX: Float,
): List<TrackSegment> {
    if (chapterStarts.isEmpty()) return listOf(TrackSegment(leftX, rightX, solid = true))
    val span = rightX - leftX
    fun x(fraction: Float) = leftX + span * fraction.coerceIn(0f, 1f)
    return chapterStarts.indices.map { i ->
        val startX = x(chapterStarts[i])
        val endX = if (i + 1 < chapterStarts.size) x(chapterStarts[i + 1]) else rightX
        TrackSegment(startX, endX, solid = allSolid || i in generated)
    }
}

/**
 * The overlay's chapter scrubber: a track spanning the whole book, a tick per chapter, and a thumb
 * at the current position. Dragging it moves through the book.
 *
 * Deliberately NOT a `SeekBar` — SeekBar brings a ripple and an animated thumb, both disqualifying
 * on e-ink. This is a plain View with an `onDraw` and a touch handler, holding no animation, no
 * timer and no state beyond what it draws.
 *
 * It reports positions as whole-book fractions and knows nothing about chapters, pages or
 * pagination. Crucially, it renders no page: a drag emits [onScrubMove] (for the Activity to update
 * a text readout and move the thumb) but the book page does not repaint until the finger lifts and
 * [onScrubCommit] fires. That "no page render until release" rule was the owner's on-hardware
 * decision — live preview repaints far too often for e-ink.
 */
class ChapterScrubberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Fires once per gesture, on ACTION_DOWN, before the first [onScrubMove]. */
    var onScrubStart: (() -> Unit)? = null

    /** The fraction under the finger, and the chapter it snapped to (or null if unsnapped), on every
     *  DOWN and MOVE. The Activity uses it to update the readout only — never to render a page. */
    var onScrubMove: ((fraction: Float, snappedChapter: Int?) -> Unit)? = null

    /** The final fraction, and the chapter it snapped to (or null), once, on ACTION_UP. The only
     *  signal that renders a page. Never fires for a cancelled gesture. */
    var onScrubCommit: ((fraction: Float, snappedChapter: Int?) -> Unit)? = null

    /** Fires once on ACTION_CANCEL. The Activity uses this to abandon the scrub and restore the
     *  page it started from — without it a cancelled drag leaves the scrubber's origin state stuck. */
    var onScrubCancel: (() -> Unit)? = null

    private var chapterStarts: List<Float> = emptyList()
    private var progress: Float = 0f
    private var bookmarkFractions: List<Float> = emptyList()
    private var generatedChapters: Set<Int> = emptySet()
    private var previewsEnabled: Boolean = true

    // Hoisted BEFORE the Paints: inside a `Paint().apply { }` block the name `density` resolves to
    // Paint's own member (always 1.0), silently dropping dp scaling.
    private val density = resources.displayMetrics.density
    private val trackThicknessPx = 2f * density
    private val tickHeightPx = 7f * density
    private val thumbRadiusPx = 7f * density
    private val bookmarkGlyphWidthPx = 8f * density
    private val bookmarkGlyphHeightPx = 10f * density
    private val bookmarkGlyphGapPx = 4f * density
    private val dashOnPx = 6f * density
    private val dashOffPx = 5f * density

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bookmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    // The track is a MID-GRAY (#666, matching reader_text_secondary): visible on 16-level e-ink but
    // fainter than the black chapter ticks and thumb, so the ticks read as the emphasis and the line
    // as the quieter rail. (Plain black — the first fix for the invisible faint-gray track — read as
    // too heavy against the ticks; this is the calibrated middle.)
    private val trackLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x66, 0x66, 0x66) }

    /**
     * Sets the book's shape and the current position, then invalidates. [chapterStartFractions] is
     * one whole-book fraction per chapter start, which becomes the tick marks.
     */
    fun setBook(chapterStartFractions: List<Float>, progress: Float) {
        this.chapterStarts = chapterStartFractions
        this.progress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    /** Bookmark glyph positions (already merged — see mergedBookmarkFractions). Informational only:
     *  glyphs are not tap targets in v2; snap-to-tick and glyph-tap would fight over the same fat
     *  finger. Set once per book open. */
    fun setBookmarks(fractions: List<Float>) {
        bookmarkFractions = fractions
        invalidate()
    }

    /** Moves the thumb without re-stating the book — used while a drag is in flight. */
    fun setProgress(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    /** The set of chapter indices whose preview thumbnails already exist — those track segments
     *  draw solid; the rest dash, showing generation-in-progress. */
    fun setGeneratedChapters(generated: Set<Int>) {
        generatedChapters = generated
        invalidate()
    }

    /** When previews are off, every segment draws solid — dashing only means something while a
     *  preview strip is actually being built. */
    fun setPreviewsEnabled(enabled: Boolean) {
        previewsEnabled = enabled
        invalidate()
    }

    private fun fractionAt(x: Float): Float {
        val usable = width - thumbRadiusPx * 2f
        if (usable <= 0f) return 0f
        return ((x - thumbRadiusPx) / usable).coerceIn(0f, 1f)
    }

    private fun xFor(fraction: Float): Float =
        thumbRadiusPx + (width - thumbRadiusPx * 2f) * fraction.coerceIn(0f, 1f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Claim the gesture: the overlay's root would otherwise let it fall through to the
                // PageView below, which reads a tap as a page turn.
                parent?.requestDisallowInterceptTouchEvent(true)
                onScrubStart?.invoke()
                val raw = fractionAt(event.x)
                val snap = snappedChapterIndex(raw, chapterStarts)
                val fraction = if (snap != null) chapterStarts[snap] else raw
                setProgress(fraction)
                onScrubMove?.invoke(fraction, snap)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val raw = fractionAt(event.x)
                val snap = snappedChapterIndex(raw, chapterStarts)
                val fraction = if (snap != null) chapterStarts[snap] else raw
                setProgress(fraction)
                onScrubMove?.invoke(fraction, snap)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val raw = fractionAt(event.x)
                val snap = snappedChapterIndex(raw, chapterStarts)
                val fraction = if (snap != null) chapterStarts[snap] else raw
                setProgress(fraction)
                onScrubCommit?.invoke(fraction, snap)
                return true
            }

            // A cancelled gesture commits nothing: the Activity restores the position the scrub
            // started from, so an interrupted drag leaves the reader where it was.
            MotionEvent.ACTION_CANCEL -> {
                onScrubCancel?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val left = thumbRadiusPx
        val right = width - thumbRadiusPx
        if (right <= left) return
        // Raised off height / 2f: the lower portion of the (taller, 88dp) view is inert
        // finger-buffer, so a thumb drifting down while dragging stays on glass, off the bezel.
        val centreY = height * 0.35f

        // allSolid when previews are off OR every chapter is generated (a complete/absent strip):
        val allSolid = !previewsEnabled ||
            (chapterStarts.isNotEmpty() && generatedChapters.size >= chapterStarts.size)
        for (seg in trackSegments(chapterStarts, generatedChapters, allSolid, left, right)) {
            if (seg.solid) {
                canvas.drawRect(
                    seg.startX,
                    centreY - trackThicknessPx / 2f,
                    seg.endX,
                    centreY + trackThicknessPx / 2f,
                    trackLinePaint,
                )
            } else {
                drawDashedSegment(canvas, seg.startX, seg.endX, centreY)
            }
        }

        for (start in chapterStarts) {
            val x = xFor(start)
            canvas.drawRect(x, centreY - tickHeightPx / 2f, x + density, centreY + tickHeightPx / 2f, markPaint)
        }

        for (fraction in bookmarkFractions) {
            drawBookmarkGlyph(canvas, xFor(fraction), centreY - trackThicknessPx / 2f - bookmarkGlyphGapPx)
        }

        canvas.drawCircle(xFor(progress), centreY, thumbRadiusPx, markPaint)
    }

    /** A chapter whose preview isn't generated yet draws dashed rather than solid — short black
     *  dashes at the track's thickness, no [android.graphics.DashPathEffect] since this is a static,
     *  hand-rolled loop rather than a stroked path (keeps the fill style consistent with the solid
     *  segments' filled rects). */
    private fun drawDashedSegment(canvas: Canvas, startX: Float, endX: Float, centreY: Float) {
        var x = startX
        val period = dashOnPx + dashOffPx
        while (x < endX) {
            val dashEnd = (x + dashOnPx).coerceAtMost(endX)
            canvas.drawRect(x, centreY - trackThicknessPx / 2f, dashEnd, centreY + trackThicknessPx / 2f, trackLinePaint)
            x += period
        }
    }

    /** A small filled bookmark silhouette — a rectangle with a notched (triangular) bottom, a
     *  5-point Path — centred horizontally at [x], its bottom edge at [bottomY]. */
    private fun drawBookmarkGlyph(canvas: Canvas, x: Float, bottomY: Float) {
        val halfWidth = bookmarkGlyphWidthPx / 2f
        val topY = bottomY - bookmarkGlyphHeightPx
        val notchY = bottomY - bookmarkGlyphHeightPx * 0.35f
        val path = Path().apply {
            moveTo(x - halfWidth, topY)
            lineTo(x + halfWidth, topY)
            lineTo(x + halfWidth, bottomY)
            lineTo(x, notchY)
            lineTo(x - halfWidth, bottomY)
            close()
        }
        canvas.drawPath(path, bookmarkPaint)
    }
}
