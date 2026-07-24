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

/** One stretch of the scrubber track between two chapter ticks. [solid] draws a solid STEEL line
 *  (its chapter's preview exists, or previews are off/complete); otherwise the segment draws as a
 *  row of round dots (that chapter's preview is still being generated). Pure geometry — JVM-tested;
 *  the line-vs-dots rendering is Canvas work. */
data class TrackSegment(val startX: Float, val endX: Float, val solid: Boolean)

/**
 * Splits the track [leftX,rightX] into one segment per chapter (bounded by consecutive chapter-start
 * fractions, last chapter running to [rightX]). A segment is solid when [allSolid] is true (previews
 * off, or fully generated) or its chapter index is in [generated]; otherwise pending. With no chapter
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

/** A drawn chapter-tick position: either a real tick ([cluster] false) or a merged cluster mark
 *  standing in for a dense run of ticks ([cluster] true), at the run's mean x. */
data class TickMark(val x: Float, val cluster: Boolean)

/** Groups consecutive x positions (ascending) into runs whose neighbour-to-neighbour gap is below
 *  [minGapPx]; a lone element is its own run. Shared by [clusteredTickMarks] (the tested, pure
 *  drawing-position API) and the View's onDraw (which additionally needs each single tick's source
 *  index to look up its pending/generated state) — one algorithm, not two copies of it. */
private fun clusterRuns(xs: List<Float>, minGapPx: Float): List<List<Int>> {
    if (xs.isEmpty()) return emptyList()
    val runs = mutableListOf(mutableListOf(0))
    for (i in 1 until xs.size) {
        if (xs[i] - xs[i - 1] < minGapPx) runs.last().add(i) else runs.add(mutableListOf(i))
    }
    return runs
}

/**
 * Groups consecutive chapter-tick x positions whose neighbour gap < [minGapPx] into one run; a
 * 1-tick run stays a real tick, a 2+ run becomes ONE cluster mark at the run's mean x. Front-matter
 * and back-matter otherwise pack many chapter starts into a few percent of the track, which drawn
 * naively smears into a black blur — this rule reads a dense region as "a group of short sections
 * lives here" instead. Drawing rule only: snapping still targets every true chapter position, so a
 * reader can land on each front-matter section by feel even though only one mark is drawn for them.
 * Deterministic — same input always yields the same marks, and no tick is ever silently dropped.
 * Pure — JVM-tested.
 */
fun clusteredTickMarks(xs: List<Float>, minGapPx: Float): List<TickMark> =
    clusterRuns(xs, minGapPx).map { run ->
        if (run.size == 1) {
            TickMark(xs[run[0]], cluster = false)
        } else {
            TickMark(run.map { xs[it] }.average().toFloat(), cluster = true)
        }
    }

/** A drawn bookmark position: either one bookmark's own ribbon ([stacked] false) or a merged glyph
 *  standing in for two-or-more near-coincident bookmarks ([stacked] true), at their mean x. */
data class BookmarkGlyph(val x: Float, val stacked: Boolean)

/**
 * Collapses bookmark x positions closer than [minGapPx] into one glyph (their mean): bookmarks a
 * few pixels apart are one place in the book at timeline resolution, and overlapping ribbons would
 * render as smudge. A merged glyph draws as a second, offset ribbon behind the first rather than a
 * count badge — see [ChapterScrubberView.onDraw]. Pure — JVM-tested.
 */
fun bookmarkGlyphs(xs: List<Float>, minGapPx: Float): List<BookmarkGlyph> {
    if (xs.isEmpty()) return emptyList()
    val sorted = xs.sorted()
    val runs = mutableListOf(mutableListOf(sorted.first()))
    for (x in sorted.drop(1)) {
        if (x - runs.last().last() < minGapPx) runs.last().add(x) else runs.add(mutableListOf(x))
    }
    return runs.map { run -> BookmarkGlyph(run.sum() / run.size, stacked = run.size > 1) }
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
 *
 * Visual design (v2): one instrument read top-to-bottom by weight, never by color — INK (thumb,
 * armed detent) > GRAPHITE (chapter ticks) > STEEL (track, dots, cluster marks, bookmarks) > MIST
 * (not-yet-generated ticks only). No animation of any kind; every state change is a single repaint.
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

    /** True from ACTION_DOWN through the gesture's commit/cancel (including the PENDING_COMMIT
     *  grace window — see the gesture state machine below). Drives the thumb's size (the ONLY
     *  grab signal — no color, no shadow) and gates the snapped-detent tick. */
    private var dragging: Boolean = false

    /** The chapter index the current drag is snapped to, or null. Only meaningful while [dragging];
     *  cleared on UP/CANCEL so a stale snap never survives into the next rest-state draw. */
    private var currentSnapIndex: Int? = null

    // Hoisted BEFORE the Paints: inside a `Paint().apply { }` block the name `density` resolves to
    // Paint's own member (always 1.0), silently dropping dp scaling.
    private val density = resources.displayMetrics.density

    private val trackThicknessPx = 3f * density
    private val tickWidthPx = 2f * density
    private val tickRiseAbovePx = 7f * density
    private val tickRootBelowPx = 2f * density
    private val tickClusterMinGapPx = 6f * density
    private val clusterMarkWidthPx = 2f * density
    private val clusterMarkHeightPx = 5f * density
    private val dotDiameterPx = 2f * density
    private val dotPitchPx = 5f * density
    private val thumbRestInkRadiusPx = 5f * density
    private val thumbRestHaloRadiusPx = 7f * density
    private val thumbDragInkRadiusPx = 8f * density
    private val thumbDragHaloRadiusPx = 10f * density
    private val detentTickWidthPx = 3f * density
    private val detentTickRisePx = 11f * density
    private val detentTickRootBelowPx = 4f * density
    private val bookmarkWidthPx = 7f * density
    private val bookmarkHeightPx = 10f * density
    private val bookmarkNotchPx = 3f * density
    private val bookmarkTopAboveBaselinePx = 23f * density
    private val bookmarkMergeMinGapPx = 6f * density
    private val bookmarkStackOffsetPx = 2.5f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = STEEL
        style = Paint.Style.STROKE
        strokeWidth = trackThicknessPx
        strokeCap = Paint.Cap.ROUND
    }

    // One FILL paint per weight in the ramp, reused across every shape drawn at that weight (dots,
    // cluster marks and bookmark ribbons are all STEEL; chapter ticks are GRAPHITE unless pending;
    // the thumb's disc and the snapped detent are INK; the thumb's halo is plain white).
    private val steelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STEEL; style = Paint.Style.FILL }
    private val graphitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRAPHITE; style = Paint.Style.FILL }
    private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MIST; style = Paint.Style.FILL }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    /**
     * Sets the book's shape and the current position, then invalidates. [chapterStartFractions] is
     * one whole-book fraction per chapter start, which becomes the tick marks.
     */
    fun setBook(chapterStartFractions: List<Float>, progress: Float) {
        this.chapterStarts = chapterStartFractions
        this.progress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    /** Raw bookmark whole-book fractions, one per bookmark — near-coincident ones are merged only
     *  at draw time (see [bookmarkGlyphs]), in pixel space, so the merge radius means the same
     *  physical distance on screen regardless of book length. Informational only: glyphs are not
     *  tap targets in v2; snap-to-tick and glyph-tap would fight over the same fat finger. */
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
     *  draw solid; the rest draw as pending dots, showing generation-in-progress. */
    fun setGeneratedChapters(generated: Set<Int>) {
        generatedChapters = generated
        invalidate()
    }

    /** When previews are off, every segment draws solid — pending dots only mean something while a
     *  preview strip is actually being built. */
    fun setPreviewsEnabled(enabled: Boolean) {
        previewsEnabled = enabled
        invalidate()
    }

    // The track's ends sit at the view's own padding, not inset by the thumb radius: the XML gives
    // this view 16dp horizontal padding so the thumb's halo has room to breathe past the track ends,
    // and a programmatic test view with no padding gets fractionAt(x) == x / width, the simplest
    // possible mapping to reason about in a test.
    private fun fractionAt(x: Float): Float {
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        val usable = right - left
        if (usable <= 0f) return 0f
        return ((x - left) / usable).coerceIn(0f, 1f)
    }

    private fun xFor(fraction: Float): Float {
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        return left + (right - left) * fraction.coerceIn(0f, 1f)
    }

    // ---- Gesture state machine ----------------------------------------------------------------
    // IDLE -> (down) TRACKING -> (our pointer lifts) PENDING_COMMIT
    //   PENDING_COMMIT -> (down within grace) TRACKING            bounce absorbed, same session
    //   PENDING_COMMIT -> (grace expires, trusted lift) commit -> IDLE
    //   PENDING_COMMIT -> (grace expires, untrusted lift) ARMED   held, nothing navigates
    //   ARMED -> (down) TRACKING                                  dropped finger re-acquired
    //   ARMED -> commitArmed() commit -> IDLE                     tap on the preview window
    //   ARMED / any -> resetSession() -> IDLE                     overlay closed, quiet abandon
    //
    // Raw ACTION_UP is NOT a commit: this panel's firmware closes light contacts while the finger
    // is still on the glass (see the trusted-lift doc above [graceExpiry]), and committing on one
    // navigated the book mid-touch — the bug this machine exists to kill. A lift must first STAY
    // lifted for [commitGraceMsForTest] (bounce absorption), and must then classify as a crisp
    // tap or a firm drag (trust). The timer is a one-shot postDelayed armed only by a lift —
    // nothing runs at rest, including in ARMED.
    //
    // Pointer discipline: the pointer id captured on DOWN owns the gesture. All coordinates come
    // from that pointer via findPointerIndex; other pointers (a palm heel, a second fingertip)
    // are ignored entirely, and only OUR pointer's lift opens the grace window.

    private var gestureState = GestureState.IDLE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var pendingFraction = 0f
    private var pendingSnap: Int? = null

    // One LEG = one kernel contact (DOWN..UP). The pt_mt panel silently drops LIGHT contacts and
    // re-acquires them seconds later, so one physical touch can span several legs; the session
    // (scrub start -> commit/abandon) survives across them. Classification is per-leg at grace
    // expiry — see [graceExpiry].
    private var legDownTime = 0L
    private var legDownX = 0f
    private var legLiftTime = 0L
    private var legDraggedBeyondSlop = false
    private var legPeakPressure = 0f
    private val touchSlopPx =
        android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private fun beginLeg(x: Float, time: Long, pressure: Float) {
        legDownX = x
        legDownTime = time
        legDraggedBeyondSlop = false
        legPeakPressure = pressure
    }

    private fun sampleLeg(x: Float, pressure: Float) {
        if (pressure > legPeakPressure) legPeakPressure = pressure
        if (!legDraggedBeyondSlop && kotlin.math.abs(x - legDownX) > touchSlopPx) {
            legDraggedBeyondSlop = true
        }
    }

    internal var commitGraceMsForTest: Long = COMMIT_GRACE_MS

    internal val gestureStateForTest: String get() = gestureState.name

    private enum class GestureState { IDLE, TRACKING, PENDING_COMMIT, ARMED }

    // Trusted-lift classification. The panel fabricates UP events for light contacts (kernel
    // captures 2026-07-23/24: every phantom-drop fragment peaked at pressure <= 0.235 and lasted
    // >= 257ms; real drags peaked >= 0.267, real taps finished by 212ms), so a lift is only
    // OBEYED when the leg expressed unambiguous intent:
    //   - a crisp tap: stationary and short — too brief to be a drop fragment
    //   - a firm drag: heavy enough that the panel never drops it, so its lift is real
    // Anything else — a light drag's lift, a long stationary press — may be the panel dropping a
    // finger that is still on the glass. The session holds ARMED instead of navigating: the thumb
    // and preview stay at the target, a re-touch resumes tracking, a tap (track or preview)
    // completes the jump, and closing the overlay abandons it. A phantom lift can therefore never
    // repaint the page.
    private val graceExpiry = Runnable {
        if (gestureState != GestureState.PENDING_COMMIT) return@Runnable
        val legDurationMs = legLiftTime - legDownTime
        val isTap = !legDraggedBeyondSlop && legDurationMs <= TAP_MAX_MS
        val isFirmDrag = legDraggedBeyondSlop && legPeakPressure >= FIRM_DRAG_MIN_PRESSURE
        if (android.util.Log.isLoggable(TRACE_TAG, android.util.Log.DEBUG)) {
            android.util.Log.d(
                TRACE_TAG,
                "leg dur=${legDurationMs}ms dragged=$legDraggedBeyondSlop " +
                    "peakP=$legPeakPressure -> ${if (isTap || isFirmDrag) "commit" else "arm"}",
            )
        }
        if (isTap || isFirmDrag) {
            gestureState = GestureState.IDLE
            activePointerId = MotionEvent.INVALID_POINTER_ID
            dragging = false
            currentSnapIndex = null
            invalidate()
            onScrubCommit?.invoke(pendingFraction, pendingSnap)
        } else {
            gestureState = GestureState.ARMED
            activePointerId = MotionEvent.INVALID_POINTER_ID
            dragging = false // thumb returns to rest size but STAYS at the pending position
            invalidate()
        }
    }

    /** Resolves a lift that is still inside its grace window RIGHT NOW — the overlay is closing or
     *  the Activity is pausing. Runs the same trusted-lift classification the grace timer would:
     *  a trusted lift commits (dropping it would lose a navigation the reader meant), an untrusted
     *  one arms, and the caller's abandon path then discards the armed session quietly.
     *  No-op in any other state. */
    fun flushPendingCommit() {
        if (gestureState != GestureState.PENDING_COMMIT) return
        removeCallbacks(graceExpiry)
        graceExpiry.run()
    }

    /** Completes an ARMED session at its held position — the tap-on-the-preview path. No-op when
     *  no armed session is waiting. */
    fun commitArmed() {
        if (gestureState != GestureState.ARMED) return
        gestureState = GestureState.IDLE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        dragging = false
        currentSnapIndex = null
        invalidate()
        onScrubCommit?.invoke(pendingFraction, pendingSnap)
    }

    /** Silently discards any open session — grace window or ARMED — without invoking any
     *  callback. The Activity's abandon path calls this so the view never stays half-open after
     *  the overlay closes around it. */
    fun resetSession() {
        removeCallbacks(graceExpiry)
        gestureState = GestureState.IDLE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        dragging = false
        currentSnapIndex = null
        invalidate()
    }

    private fun trackedX(event: MotionEvent): Float? {
        val index = event.findPointerIndex(activePointerId)
        return if (index >= 0) event.getX(index) else null
    }

    private fun applyPosition(x: Float, emitMove: Boolean) {
        val raw = fractionAt(x)
        val snap = snappedChapterIndex(raw, chapterStarts)
        currentSnapIndex = snap
        val fraction = if (snap != null) chapterStarts[snap] else raw
        pendingFraction = fraction
        pendingSnap = snap
        setProgress(fraction)
        if (emitMove) onScrubMove?.invoke(fraction, snap)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Claim the gesture: the overlay's root would otherwise let it fall through to the
                // PageView below, which reads a tap as a page turn.
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointerId = event.getPointerId(0)
                beginLeg(event.getX(0), event.eventTime, event.getPressure(0))
                when (gestureState) {
                    GestureState.PENDING_COMMIT, GestureState.ARMED -> {
                        // A re-contact: either a bounce inside the grace window or the panel
                        // re-acquiring a dropped light touch seconds later (ARMED). Resume the
                        // SAME session — no start, no commit, visually seamless.
                        removeCallbacks(graceExpiry)
                        gestureState = GestureState.TRACKING
                        dragging = true
                        applyPosition(event.getX(0), emitMove = true)
                    }
                    else -> {
                        gestureState = GestureState.TRACKING
                        onScrubStart?.invoke()
                        dragging = true
                        applyPosition(event.getX(0), emitMove = true)
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A non-first contact. While TRACKING this is a palm/second finger landing —
                // ignored, the owning pointer keeps the gesture. But during the commit grace it is
                // the BOUNCED FINGER RETURNING while the palm never left: with another contact
                // still on the glass, the re-touch arrives as POINTER_DOWN, not DOWN, so without
                // this arm the grace would expire and commit under a finger that is back on the
                // strip — the exact invariant this machine exists to protect, in the compound
                // palm+bounce case. Adopt the new contact as the owner and resume the session.
                if (gestureState == GestureState.PENDING_COMMIT || gestureState == GestureState.ARMED) {
                    removeCallbacks(graceExpiry)
                    activePointerId = event.getPointerId(event.actionIndex)
                    beginLeg(
                        event.getX(event.actionIndex),
                        event.eventTime,
                        event.getPressure(event.actionIndex),
                    )
                    gestureState = GestureState.TRACKING
                    dragging = true
                    applyPosition(event.getX(event.actionIndex), emitMove = true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureState != GestureState.TRACKING) return true
                val x = trackedX(event) ?: return true // only OUR pointer moves the thumb
                sampleLeg(x, event.getPressure(event.findPointerIndex(activePointerId)))
                applyPosition(x, emitMove = true)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (gestureState != GestureState.TRACKING) return true
                // Only OUR pointer's lift matters; a palm lifting is not a gesture event.
                val liftedId = event.getPointerId(event.actionIndex)
                if (liftedId != activePointerId) return true
                trackedX(event)?.let { x ->
                    // A fast flick can cover most of its distance on the UP event itself — the
                    // lift coordinate counts toward the drag classification too.
                    sampleLeg(x, event.getPressure(event.actionIndex))
                    applyPosition(x, emitMove = false)
                }
                legLiftTime = event.eventTime
                gestureState = GestureState.PENDING_COMMIT
                // Thumb stays drag-sized and the preview stays up: if this lift is a bounce, the
                // resume is invisible; if it is real, the commit path restores rest visuals.
                postDelayed(graceExpiry, commitGraceMsForTest)
                return true
            }

            // A cancelled gesture commits nothing: the Activity restores the position the scrub
            // started from, so an interrupted drag leaves the reader where it was.
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(graceExpiry)
                val wasActive = gestureState != GestureState.IDLE
                gestureState = GestureState.IDLE
                activePointerId = MotionEvent.INVALID_POINTER_ID
                dragging = false
                currentSnapIndex = null
                invalidate()
                if (wasActive) onScrubCancel?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(graceExpiry)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        if (right <= left) return
        // Raised off height / 2f: the lower portion of the (taller, 88dp) view is inert
        // finger-buffer, so a thumb drifting down while dragging stays on glass, off the bezel.
        val centreY = height * 0.35f

        // allSolid when previews are off OR every chapter is generated (a complete/absent strip):
        val allSolid = !previewsEnabled ||
            (chapterStarts.isNotEmpty() && generatedChapters.size >= chapterStarts.size)

        // Track: a solid STEEL rounded-cap line reads as filled-in; a pending chapter draws as a
        // row of round dots instead — dots read as "loading", dashes read as "debug". The boundary
        // between them is crisp (no taper) because each segment draws independently, back to back.
        for (seg in trackSegments(chapterStarts, generatedChapters, allSolid, left, right)) {
            if (seg.solid) {
                canvas.drawLine(seg.startX, centreY, seg.endX, centreY, trackPaint)
            } else {
                drawPendingDots(canvas, seg.startX, seg.endX, centreY)
            }
        }

        // Chapter ticks draw over the track. Dense runs (front/back matter) collapse to one quiet
        // cluster mark — a drawing rule only, so the snap detent below still targets every true
        // chapter position even for a tick absorbed into a cluster (see clusteredTickMarks).
        val chapterXs = chapterStarts.map { xFor(it) }
        val snapIdx = if (dragging) currentSnapIndex else null
        for (run in clusterRuns(chapterXs, tickClusterMinGapPx)) {
            if (run.size == 1) {
                val idx = run[0]
                if (idx == snapIdx) continue // redrawn below as the tall ink detent instead
                val pending = previewsEnabled && !allSolid && idx !in generatedChapters
                drawChapterTick(canvas, chapterXs[idx], centreY, if (pending) mistPaint else graphitePaint)
            } else {
                val meanX = run.map { chapterXs[it] }.average().toFloat()
                drawClusterMark(canvas, meanX, centreY)
            }
        }

        // Bookmarks hang above the ticks; near-coincident ones merge to one stacked glyph — a
        // second MIST ribbon offset behind the STEEL one, never a count badge.
        for (glyph in bookmarkGlyphs(bookmarkFractions.map { xFor(it) }, bookmarkMergeMinGapPx)) {
            val topY = centreY - bookmarkTopAboveBaselinePx
            if (glyph.stacked) {
                drawBookmarkGlyph(canvas, glyph.x - bookmarkStackOffsetPx, topY - bookmarkStackOffsetPx, mistPaint)
            }
            drawBookmarkGlyph(canvas, glyph.x, topY, steelPaint)
        }

        // Thumb: a white halo under an ink disc, both larger while dragging — size is the only grab
        // signal. Mid-drag on a snapped tick, draw order is halo -> detent tick -> disc, so the tall
        // ink stroke emerges above the disc while the halo still separates it from the track.
        val thumbX = xFor(progress)
        val haloRadius = if (dragging) thumbDragHaloRadiusPx else thumbRestHaloRadiusPx
        val inkRadius = if (dragging) thumbDragInkRadiusPx else thumbRestInkRadiusPx
        canvas.drawCircle(thumbX, centreY, haloRadius, haloPaint)
        if (snapIdx != null) {
            chapterXs.getOrNull(snapIdx)?.let { tickX -> drawDetentTick(canvas, tickX, centreY) }
        }
        canvas.drawCircle(thumbX, centreY, inkRadius, inkPaint)
    }

    /** A pending chapter draws as round STEEL dots rather than a line: 2dp diameter, 5dp on-center
     *  pitch, centered on the baseline. First dot sits half a pitch in from [startX] and drawing
     *  stops before [endX] — so a pending segment never dodges into its solid neighbour. */
    private fun drawPendingDots(canvas: Canvas, startX: Float, endX: Float, centreY: Float) {
        var x = startX + dotPitchPx / 2f
        while (x < endX) {
            canvas.drawCircle(x, centreY, dotDiameterPx / 2f, steelPaint)
            x += dotPitchPx
        }
    }

    /** A chapter tick: 2dp wide, rising [tickRiseAbovePx] above the baseline and rooting
     *  [tickRootBelowPx] below it. [paint] carries the GRAPHITE/MIST distinction. */
    private fun drawChapterTick(canvas: Canvas, x: Float, centreY: Float, paint: Paint) {
        canvas.drawRect(
            x - tickWidthPx / 2f,
            centreY - tickRiseAbovePx,
            x + tickWidthPx / 2f,
            centreY + tickRootBelowPx,
            paint,
        )
    }

    /** A cluster mark standing in for a dense run of ticks: shorter and lighter than a real tick —
     *  2dp wide, 5dp tall, seated ON the baseline with nothing rooting below it — so a dense region
     *  reads as "a group of short sections", not as one loud boundary. */
    private fun drawClusterMark(canvas: Canvas, x: Float, centreY: Float) {
        canvas.drawRect(
            x - clusterMarkWidthPx / 2f,
            centreY - clusterMarkHeightPx,
            x + clusterMarkWidthPx / 2f,
            centreY,
            steelPaint,
        )
    }

    /** The snapped-tick detent: 3dp wide, 15dp tall INK — taller and heavier than a normal tick,
     *  rising [detentTickRisePx] above the baseline and rooting [detentTickRootBelowPx] below it.
     *  The one moment a tick outranks the track; only the snapped tick ever draws this way. */
    private fun drawDetentTick(canvas: Canvas, x: Float, centreY: Float) {
        canvas.drawRect(
            x - detentTickWidthPx / 2f,
            centreY - detentTickRisePx,
            x + detentTickWidthPx / 2f,
            centreY + detentTickRootBelowPx,
            inkPaint,
        )
    }

    /** A notched bookmark ribbon — a rectangle with a triangular notch cut up into its bottom edge,
     *  a 5-point Path — centred horizontally at [x], its TOP edge at [topY]. [paint] carries the
     *  STEEL/MIST distinction (MIST for the second ribbon behind a stacked glyph). */
    private fun drawBookmarkGlyph(canvas: Canvas, x: Float, topY: Float, paint: Paint) {
        val halfWidth = bookmarkWidthPx / 2f
        val bottomY = topY + bookmarkHeightPx
        val notchY = bottomY - bookmarkNotchPx
        val path = Path().apply {
            moveTo(x - halfWidth, topY)
            lineTo(x + halfWidth, topY)
            lineTo(x + halfWidth, bottomY)
            lineTo(x, notchY)
            lineTo(x - halfWidth, bottomY)
            close()
        }
        canvas.drawPath(path, paint)
    }

    companion object {
        // The whole hierarchy is weight, not color: four grays, chosen to survive a coarse
        // 16-level e-ink panel — nothing thinner than 2dp, nothing lighter than MIST at
        // load-bearing size. See the class doc for what each weight carries.
        private const val INK = 0xFF1A1A1A.toInt()
        private const val GRAPHITE = 0xFF555555.toInt()
        private const val STEEL = 0xFF8A8A8A.toInt()
        private const val MIST = 0xFFB9B9B9.toInt()

        // A lift commits only after staying lifted this long — see the gesture state machine doc
        // above onTouchEvent. Long enough to absorb an e-ink contact-bounce phantom lift, short
        // enough that a genuine release still reads as instant to the reader.
        private const val COMMIT_GRACE_MS = 200L

        // Trusted-lift thresholds, calibrated from this device's kernel captures (2026-07-23 and
        // the 2026-07-24 scrub session). Real taps: 115-212ms. Real drags: peak pressure
        // 0.267-0.325. Phantom-drop fragments — contacts the panel closed while the finger stayed
        // on the glass: 257-393ms, peak pressure <= 0.235. The tap ceiling and the pressure floor
        // each sit in the middle of their observed gap.
        private const val TAP_MAX_MS = 250L
        private const val FIRM_DRAG_MIN_PRESSURE = 0.25f

        // Classification telemetry, off unless enabled on-device:
        //   adb shell setprop log.tag.SCRUBTRACE DEBUG
        private const val TRACE_TAG = "SCRUBTRACE"
    }
}
