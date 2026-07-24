package dev.reader.ui

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ChapterScrubberViewTest {

    // Attached to a real Activity window, not just laid out in isolation: the grace-window timer
    // uses View.postDelayed, which queues into an internal RunQueue (not the main Looper's real
    // queue) until the view has an AttachInfo — an unattached view's postDelayed would silently
    // never fire under shadowOf(Looper.getMainLooper()).idleFor(...). A fixed-size FrameLayout host
    // forces the child to exactly [width]x60 regardless of the emulated device's own screen size.
    private fun scrubber(width: Int = 400): ChapterScrubberView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = ChapterScrubberView(activity)
        val container = FrameLayout(activity)
        activity.setContentView(container, ViewGroup.LayoutParams(width, 60))
        container.addView(view, ViewGroup.LayoutParams(width, 60))
        // The window's own traversal is async (Choreographer-posted) — don't rely on its timing
        // for the exact pixel bounds the tests reason about; measure/layout the child ourselves,
        // same as the original bare `view.layout(0, 0, width, 60)` did before attachment mattered.
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, 60)
        view.setBook(chapterStartFractions = listOf(0f, 0.25f, 0.5f), progress = 0f)
        return view
    }

    private fun touch(view: ChapterScrubberView, action: Int, x: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, 30f, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    /** Inverts the view's fractionAt geometry (padding-based track ends), mirroring its math
     *  exactly. Programmatic test views carry no XML padding, so paddingLeft/Right are both 0 and
     *  this collapses to fraction * width — the simplest possible mapping. */
    private fun xForFraction(view: ChapterScrubberView, fraction: Float): Float {
        val left = view.paddingLeft.toFloat()
        val right = view.width - view.paddingRight.toFloat()
        return left + fraction * (right - left)
    }

    @Test
    fun `dragging reports the fraction under the finger`() {
        val view = scrubber(width = 400)
        val moves = mutableListOf<Float>()
        view.onScrubMove = { f, _ -> moves += f }

        touch(view, MotionEvent.ACTION_DOWN, 200f)

        assertThat(moves).hasSize(1)
        assertThat(moves.single()).isWithin(0.05f).of(0.5f)
    }

    @Test
    fun `lifting off commits once with the final fraction`() {
        val view = scrubber(width = 400)
        var commits = 0
        var committed = -1f
        view.onScrubCommit = { f, _ -> commits++; committed = f }

        touch(view, MotionEvent.ACTION_DOWN, 100f)
        touch(view, MotionEvent.ACTION_MOVE, 300f)
        touch(view, MotionEvent.ACTION_UP, 300f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertThat(commits).isEqualTo(1)
        assertThat(committed).isWithin(0.05f).of(0.75f)
    }

    @Test
    fun `a fraction beyond either edge is clamped`() {
        val view = scrubber(width = 400)
        val moves = mutableListOf<Float>()
        view.onScrubMove = { f, _ -> moves += f }

        touch(view, MotionEvent.ACTION_DOWN, -80f)
        touch(view, MotionEvent.ACTION_MOVE, 9000f)

        assertThat(moves.first()).isEqualTo(0f)
        assertThat(moves.last()).isEqualTo(1f)
    }

    @Test
    fun `a cancelled gesture commits nothing`() {
        val view = scrubber()
        var commits = 0
        view.onScrubCommit = { _, _ -> commits++ }

        touch(view, MotionEvent.ACTION_DOWN, 100f)
        touch(view, MotionEvent.ACTION_CANCEL, 100f)

        assertThat(commits).isEqualTo(0)
    }

    @Test
    fun `a cancelled gesture fires onScrubCancel exactly once, not onScrubCommit`() {
        val view = scrubber()
        var cancels = 0
        var commits = 0
        view.onScrubCancel = { cancels++ }
        view.onScrubCommit = { _, _ -> commits++ }

        touch(view, MotionEvent.ACTION_DOWN, 100f)
        touch(view, MotionEvent.ACTION_CANCEL, 100f)

        assertThat(cancels).isEqualTo(1)
        assertThat(commits).isEqualTo(0)
    }

    @Test
    fun `scrub start fires once per gesture`() {
        val view = scrubber()
        var starts = 0
        view.onScrubStart = { starts++ }

        touch(view, MotionEvent.ACTION_DOWN, 100f)
        touch(view, MotionEvent.ACTION_MOVE, 150f)
        touch(view, MotionEvent.ACTION_MOVE, 200f)

        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun `snapping magnetizes within the radius and releases outside it`() {
        val starts = listOf(0f, 0.25f, 0.5f)
        assertThat(snappedFraction(0.26f, starts)).isEqualTo(0.25f)
        assertThat(snappedFraction(0.24f, starts)).isEqualTo(0.25f)
        assertThat(snappedFraction(0.30f, starts)).isEqualTo(0.30f)
        assertThat(snappedFraction(0.007f, starts)).isEqualTo(0f)
        assertThat(snappedFraction(0.4f, emptyList())).isEqualTo(0.4f)
    }

    @Test
    fun `snap prefers the nearest tick when two are in radius`() {
        val starts = listOf(0.50f, 0.52f)
        assertThat(snappedFraction(0.505f, starts)).isEqualTo(0.50f)
        assertThat(snappedFraction(0.515f, starts)).isEqualTo(0.52f)
    }

    @Test
    fun `a drag reports snapped fractions`() {
        val view = scrubber(width = 400) // helper exists; setBook installs starts 0f, 0.25f, 0.5f
        val moves = mutableListOf<Float>()
        view.onScrubMove = { f, _ -> moves += f }

        // x for raw fraction ~0.26 on a 400px track lands within snap radius of 0.25.
        touch(view, MotionEvent.ACTION_DOWN, xForFraction(view, 0.26f))

        assertThat(moves.single()).isEqualTo(0.25f)
    }

    @Test
    fun `a snapped drag reports the snapped chapter index`() {
        val view = scrubber(width = 400) // setBook installs starts 0f, 0.25f, 0.5f
        val snaps = mutableListOf<Int?>()
        view.onScrubMove = { _, snap -> snaps += snap }

        touch(view, MotionEvent.ACTION_DOWN, xForFraction(view, 0.255f)) // within radius of 0.25 = chapter 1
        assertThat(snaps.last()).isEqualTo(1)
    }

    @Test
    fun `an unsnapped drag reports a null snapped chapter`() {
        val view = scrubber(width = 400)
        val snaps = mutableListOf<Int?>()
        view.onScrubMove = { _, snap -> snaps += snap }

        touch(view, MotionEvent.ACTION_DOWN, xForFraction(view, 0.62f)) // far from any of 0/0.25/0.5
        assertThat(snaps.last()).isNull()
    }

    @Test
    fun `commit carries the snapped chapter`() {
        val view = scrubber(width = 400)
        var committedSnap: Int? = -1
        view.onScrubCommit = { _, snap -> committedSnap = snap }
        touch(view, MotionEvent.ACTION_DOWN, xForFraction(view, 0.005f)) // snaps to chapter 0
        touch(view, MotionEvent.ACTION_UP, xForFraction(view, 0.005f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertThat(committedSnap).isEqualTo(0)
    }

    @Test
    fun `track segments span between chapter starts, marked solid by generated set`() {
        // 3 chapters starting at 0, 0.5, 0.75 of the book; track from x=10 to x=210 (usable 200).
        val segs = trackSegments(listOf(0f, 0.5f, 0.75f), generated = setOf(0, 2), allSolid = false, leftX = 10f, rightX = 210f)
        assertThat(segs).hasSize(3)
        // chapter 0: [0,0.5) -> x[10,110), solid (in set)
        assertThat(segs[0].solid).isTrue()
        assertThat(segs[0].startX).isWithin(0.01f).of(10f)
        assertThat(segs[0].endX).isWithin(0.01f).of(110f)
        // chapter 1: [0.5,0.75) -> dashed (not in set)
        assertThat(segs[1].solid).isFalse()
        // chapter 2: [0.75,1.0] -> to rightX, solid
        assertThat(segs[2].solid).isTrue()
        assertThat(segs[2].endX).isWithin(0.01f).of(210f)
    }

    @Test
    fun `allSolid overrides the generated set`() {
        val segs = trackSegments(listOf(0f, 0.5f), generated = emptySet(), allSolid = true, leftX = 0f, rightX = 100f)
        assertThat(segs.all { it.solid }).isTrue()
    }

    @Test
    fun `empty chapter starts yields one solid full-width segment`() {
        val segs = trackSegments(emptyList(), emptySet(), allSolid = false, leftX = 5f, rightX = 95f)
        assertThat(segs).hasSize(1)
        assertThat(segs.single().solid).isTrue() // nothing to mark pending -> a plain visible track
        assertThat(segs.single().startX).isEqualTo(5f)
        assertThat(segs.single().endX).isEqualTo(95f)
    }

    @Test
    fun `clusteredTickMarks passes single ticks through untouched`() {
        val marks = clusteredTickMarks(listOf(10f, 50f, 90f), minGapPx = 6f)
        assertThat(marks).hasSize(3)
        assertThat(marks.map { it.cluster }).containsExactly(false, false, false).inOrder()
        assertThat(marks.map { it.x }).containsExactly(10f, 50f, 90f).inOrder()
    }

    @Test
    fun `clusteredTickMarks merges a dense run to its mean x`() {
        // Three ticks within 6px of each other collapse to one cluster mark at their mean.
        val marks = clusteredTickMarks(listOf(10f, 12f, 14f, 90f), minGapPx = 6f)
        assertThat(marks).hasSize(2)
        assertThat(marks[0].cluster).isTrue()
        assertThat(marks[0].x).isWithin(0.01f).of(12f) // mean of 10, 12, 14
        assertThat(marks[1]).isEqualTo(TickMark(90f, cluster = false))
    }

    @Test
    fun `clusteredTickMarks merges dense runs at both ends independently`() {
        val marks = clusteredTickMarks(listOf(0f, 1f, 2f, 500f, 900f, 901f, 902f), minGapPx = 6f)
        assertThat(marks).hasSize(3)
        assertThat(marks[0].cluster).isTrue()
        assertThat(marks[0].x).isWithin(0.01f).of(1f)
        assertThat(marks[1]).isEqualTo(TickMark(500f, cluster = false))
        assertThat(marks[2].cluster).isTrue()
        assertThat(marks[2].x).isWithin(0.01f).of(901f)
    }

    @Test
    fun `clusteredTickMarks on an empty list is empty`() {
        assertThat(clusteredTickMarks(emptyList(), minGapPx = 6f)).isEmpty()
    }

    @Test
    fun `clusteredTickMarks is deterministic`() {
        val xs = listOf(0f, 3f, 5f, 40f, 41f, 200f)
        assertThat(clusteredTickMarks(xs, minGapPx = 6f)).isEqualTo(clusteredTickMarks(xs, minGapPx = 6f))
    }

    @Test
    fun `bookmarkGlyphs merges bookmarks closer than the gap`() {
        val glyphs = bookmarkGlyphs(listOf(10f, 11f, 200f), minGapPx = 6f)
        assertThat(glyphs).hasSize(2)
        assertThat(glyphs[0].stacked).isTrue()
        assertThat(glyphs[0].x).isWithin(0.01f).of(10.5f)
        assertThat(glyphs[1]).isEqualTo(BookmarkGlyph(200f, stacked = false))
    }

    @Test
    fun `bookmarkGlyphs leaves well-separated bookmarks single`() {
        val glyphs = bookmarkGlyphs(listOf(10f, 100f, 300f), minGapPx = 6f)
        assertThat(glyphs).hasSize(3)
        assertThat(glyphs.all { !it.stacked }).isTrue()
    }

    @Test
    fun `bookmarkGlyphs on an empty list is empty`() {
        assertThat(bookmarkGlyphs(emptyList(), minGapPx = 6f)).isEmpty()
    }

    @Test
    fun `bookmarkGlyphs sorts unsorted input before merging`() {
        val glyphs = bookmarkGlyphs(listOf(200f, 10f, 11f), minGapPx = 6f)
        assertThat(glyphs).hasSize(2)
        assertThat(glyphs.map { it.x }).containsExactly(10.5f, 200f).inOrder()
    }

    /** A touch with explicit event time and pressure — the axes the trusted-lift grammar reads.
     *  The plain [touch] helper's events carry pressure 1.0 (firm) and time 0 (crisp). */
    private fun touchPT(view: ChapterScrubberView, action: Int, x: Float, timeMs: Long, pressure: Float) {
        val event = MotionEvent.obtain(0L, timeMs, action, x, 30f, pressure, 1f, 0, 1f, 1f, 0, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    // ---- Trusted-lift grammar -------------------------------------------------------------------
    // The pt_mt panel fabricates lifts for LIGHT contacts (device captures 2026-07-23/24): every
    // phantom-drop fragment ran at peak pressure <= 0.235 and duration >= 257ms, while real drags
    // peaked >= 0.267 and real taps finished by 212ms. These tests replay those exact profiles.

    @Test
    fun `a light moving fragment arms instead of committing — the phantom-drop drag case`() {
        val view = scrubber(width = 400)
        var commits = 0
        var cancels = 0
        view.onScrubCommit = { _, _ -> commits++ }
        view.onScrubCancel = { cancels++ }

        // Contact 7 from the 2026-07-23 capture: 257ms, ~106px of drift, never above 0.235.
        touchPT(view, MotionEvent.ACTION_DOWN, 100f, 0L, 0.21f)
        touchPT(view, MotionEvent.ACTION_MOVE, 206f, 200L, 0.235f)
        touchPT(view, MotionEvent.ACTION_UP, 206f, 257L, 0.22f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertThat(commits).isEqualTo(0) // the finger is still on the glass — never navigate
        assertThat(cancels).isEqualTo(0) // and never snap the thumb away from it either
        assertThat(view.gestureStateForTest).isEqualTo("ARMED")
    }

    @Test
    fun `a light stationary fragment arms and holds its position`() {
        val view = scrubber(width = 400)
        var commits = 0
        var cancels = 0
        val moves = mutableListOf<Float>()
        view.onScrubCommit = { _, _ -> commits++ }
        view.onScrubCancel = { cancels++ }
        view.onScrubMove = { f, _ -> moves += f }

        // Contact 5 from the capture: 319ms, ~3px, pressure 0.22-0.23.
        touchPT(view, MotionEvent.ACTION_DOWN, 200f, 0L, 0.225f)
        touchPT(view, MotionEvent.ACTION_MOVE, 203f, 296L, 0.227f)
        touchPT(view, MotionEvent.ACTION_UP, 203f, 319L, 0.227f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertThat(commits).isEqualTo(0)
        assertThat(cancels).isEqualTo(0)
        assertThat(view.gestureStateForTest).isEqualTo("ARMED")
        assertThat(moves.last()).isWithin(0.05f).of(0.5f) // thumb stays under the resting finger
    }

    @Test
    fun `a firm drag still commits on its settled lift`() {
        val view = scrubber(width = 400)
        var committed = -1f
        view.onScrubCommit = { f, _ -> committed = f }

        // The 8.4s drag from the 2026-07-24 calibration: starts light, peaks 0.325.
        touchPT(view, MotionEvent.ACTION_DOWN, 100f, 0L, 0.21f)
        touchPT(view, MotionEvent.ACTION_MOVE, 300f, 4000L, 0.31f)
        touchPT(view, MotionEvent.ACTION_UP, 300f, 8384L, 0.31f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertThat(committed).isWithin(0.05f).of(0.75f)
        assertThat(view.gestureStateForTest).isEqualTo("IDLE")
    }

    @Test
    fun `a crisp tap commits even at light pressure`() {
        val view = scrubber(width = 400)
        var committed = -1f
        view.onScrubCommit = { f, _ -> committed = f }

        // His real taps: 115-212ms, pressure 0.196-0.24 — light but SHORT, unlike any fragment.
        touchPT(view, MotionEvent.ACTION_DOWN, 300f, 0L, 0.2f)
        touchPT(view, MotionEvent.ACTION_UP, 300f, 120L, 0.2f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertThat(committed).isWithin(0.05f).of(0.75f)
    }

    @Test
    fun `a re-touch while armed resumes the same session without a new start`() {
        val view = scrubber(width = 400)
        var starts = 0
        var committed = -1f
        view.onScrubStart = { starts++ }
        view.onScrubCommit = { f, _ -> committed = f }

        touchPT(view, MotionEvent.ACTION_DOWN, 100f, 0L, 0.22f)
        touchPT(view, MotionEvent.ACTION_MOVE, 206f, 200L, 0.23f)
        touchPT(view, MotionEvent.ACTION_UP, 206f, 393L, 0.22f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertThat(view.gestureStateForTest).isEqualTo("ARMED")

        // The dropped finger moves again 2.4s later — a fresh kernel contact, same physical touch.
        touchPT(view, MotionEvent.ACTION_DOWN, 210f, 2800L, 0.22f)
        touchPT(view, MotionEvent.ACTION_MOVE, 300f, 3000L, 0.30f)
        touchPT(view, MotionEvent.ACTION_UP, 300f, 3200L, 0.30f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertThat(starts).isEqualTo(1) // one continuous session across the phantom split
        assertThat(committed).isWithin(0.05f).of(0.75f) // firm leg -> its lift is trusted
    }

    @Test
    fun `commitArmed completes an armed session at its held position`() {
        val view = scrubber(width = 400)
        var committed = -1f
        view.onScrubCommit = { f, _ -> committed = f }

        touchPT(view, MotionEvent.ACTION_DOWN, 100f, 0L, 0.22f)
        touchPT(view, MotionEvent.ACTION_MOVE, 300f, 200L, 0.23f)
        touchPT(view, MotionEvent.ACTION_UP, 300f, 393L, 0.22f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertThat(view.gestureStateForTest).isEqualTo("ARMED")

        view.commitArmed()

        assertThat(committed).isWithin(0.05f).of(0.75f)
        assertThat(view.gestureStateForTest).isEqualTo("IDLE")
    }

    @Test
    fun `commitArmed outside an armed session is a no-op`() {
        val view = scrubber(width = 400)
        var commits = 0
        view.onScrubCommit = { _, _ -> commits++ }

        view.commitArmed()

        assertThat(commits).isEqualTo(0)
        assertThat(view.gestureStateForTest).isEqualTo("IDLE")
    }

    @Test
    fun `resetSession silently discards an armed session`() {
        val view = scrubber(width = 400)
        var commits = 0
        var cancels = 0
        view.onScrubCommit = { _, _ -> commits++ }
        view.onScrubCancel = { cancels++ }

        touchPT(view, MotionEvent.ACTION_DOWN, 100f, 0L, 0.22f)
        touchPT(view, MotionEvent.ACTION_MOVE, 206f, 200L, 0.23f)
        touchPT(view, MotionEvent.ACTION_UP, 206f, 393L, 0.22f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        view.resetSession()

        assertThat(commits).isEqualTo(0)
        assertThat(cancels).isEqualTo(0)
        assertThat(view.gestureStateForTest).isEqualTo("IDLE")
    }

    @Test
    fun `flushing a light lift arms rather than commits`() {
        val view = scrubber(width = 400)
        var commits = 0
        view.onScrubCommit = { _, _ -> commits++ }

        touchPT(view, MotionEvent.ACTION_DOWN, 100f, 0L, 0.22f)
        touchPT(view, MotionEvent.ACTION_MOVE, 206f, 200L, 0.23f)
        touchPT(view, MotionEvent.ACTION_UP, 206f, 393L, 0.22f)
        view.flushPendingCommit() // overlay closing mid-grace: classify NOW

        assertThat(commits).isEqualTo(0)
        assertThat(view.gestureStateForTest).isEqualTo("ARMED")
    }

    /** Two-pointer MotionEvent: [action] with pointer ids 0 and 1 at (x0, x1), y mid-strip. */
    private fun twoPointerEvent(action: Int, x0: Float, x1: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = x0; y = 30f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = x1; y = 30f; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(0L, 0L, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    @Test
    fun `a lift does not commit until the grace window expires`() {
        val view = scrubber(width = 400)
        var commits = 0
        view.onScrubCommit = { _, _ -> commits++ }

        touch(view, MotionEvent.ACTION_DOWN, 100f)
        touch(view, MotionEvent.ACTION_UP, 100f)

        assertThat(commits).isEqualTo(0) // lifted, but the grace window is still open
        assertThat(view.gestureStateForTest).isEqualTo("PENDING_COMMIT")

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertThat(commits).isEqualTo(1) // stayed lifted -> the commit is real
        assertThat(view.gestureStateForTest).isEqualTo("IDLE")
    }

    @Test
    fun `a contact bounce — up then immediate down — never commits`() {
        val view = scrubber(width = 400)
        var commits = 0
        var starts = 0
        val moves = mutableListOf<Float>()
        view.onScrubCommit = { _, _ -> commits++ }
        view.onScrubStart = { starts++ }
        view.onScrubMove = { f, _ -> moves += f }

        touch(view, MotionEvent.ACTION_DOWN, 100f)
        touch(view, MotionEvent.ACTION_MOVE, 200f)
        touch(view, MotionEvent.ACTION_UP, 200f)      // firmware bounce: phantom lift...
        touch(view, MotionEvent.ACTION_DOWN, 205f)    // ...contact re-established 0ms later
        touch(view, MotionEvent.ACTION_MOVE, 300f)
        touch(view, MotionEvent.ACTION_UP, 300f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertThat(commits).isEqualTo(1)                       // exactly one, at the REAL lift
        assertThat(starts).isEqualTo(1)                        // one continuous session, one start
        assertThat(moves.last()).isWithin(0.05f).of(0.75f)     // tracking resumed seamlessly
    }

    @Test
    fun `the grace commit carries the lift position, not the resume position`() {
        val view = scrubber(width = 400)
        var committed = -1f
        view.onScrubCommit = { f, _ -> committed = f }

        touch(view, MotionEvent.ACTION_DOWN, 100f)
        touch(view, MotionEvent.ACTION_UP, 300f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertThat(committed).isWithin(0.05f).of(0.75f)
    }

    @Test
    fun `a second pointer cannot steal the gesture or move the thumb`() {
        val view = scrubber(width = 400)
        val moves = mutableListOf<Float>()
        view.onScrubMove = { f, _ -> moves += f }

        touch(view, MotionEvent.ACTION_DOWN, 100f)                       // finger (id 0) owns it
        view.onTouchEvent(twoPointerEvent(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            100f, 350f,                                                   // palm lands at 350
        ))
        view.onTouchEvent(twoPointerEvent(MotionEvent.ACTION_MOVE, 120f, 380f)) // both drift

        // Every reported fraction tracks pointer id 0 only — the palm at 350/380 never leaks in.
        assertThat(moves.none { it > 0.5f }).isTrue()
    }

    @Test
    fun `our pointer lifting while the palm stays down starts the grace, palm events are ignored`() {
        val view = scrubber(width = 400)
        var commits = 0
        view.onScrubCommit = { _, _ -> commits++ }

        touch(view, MotionEvent.ACTION_DOWN, 100f)                       // id 0
        view.onTouchEvent(twoPointerEvent(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            100f, 350f,
        ))
        // Our pointer (index 0) lifts; the palm (id 1) is still on the glass.
        view.onTouchEvent(twoPointerEvent(
            MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            100f, 350f,
        ))
        assertThat(view.gestureStateForTest).isEqualTo("PENDING_COMMIT")

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertThat(commits).isEqualTo(1)                                  // committed at OUR x, not the palm's
    }

    @Test
    fun `a bounce while a palm is co-touching still never commits under the finger`() {
        // The compound case the review caught: with a palm resting on the strip, the bounced
        // finger's re-contact arrives as ACTION_POINTER_DOWN (the screen was never empty), not
        // ACTION_DOWN — without its own arm the grace would expire and commit while the finger is
        // back on the glass, the exact invariant this machine exists to protect.
        val view = scrubber(width = 400)
        var commits = 0
        var starts = 0
        var committed = -1f
        view.onScrubCommit = { f, _ -> commits++; committed = f }
        view.onScrubStart = { starts++ }

        touch(view, MotionEvent.ACTION_DOWN, 100f)                        // finger, id 0
        view.onTouchEvent(twoPointerEvent(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            100f, 350f,                                                    // palm lands, id 1
        ))
        view.onTouchEvent(twoPointerEvent(
            MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            100f, 350f,                                                    // firmware phantom-lifts the finger
        ))
        assertThat(view.gestureStateForTest).isEqualTo("PENDING_COMMIT")

        view.onTouchEvent(twoPointerEvent(
            MotionEvent.ACTION_POINTER_DOWN or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            300f, 350f,                                                    // finger re-contacts 10ms later
        ))
        assertThat(view.gestureStateForTest).isEqualTo("TRACKING")         // session resumed, not committed

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertThat(commits).isEqualTo(0)                                   // the stale grace timer is dead

        view.onTouchEvent(twoPointerEvent(
            MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            300f, 350f,                                                    // the REAL lift
        ))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertThat(commits).isEqualTo(1)
        assertThat(starts).isEqualTo(1)                                    // one continuous session
        assertThat(committed).isWithin(0.05f).of(0.75f)                    // at the finger's x, never the palm's
    }

    @Test
    fun `cancel during tracking abandons and cancel during grace abandons too`() {
        val view = scrubber(width = 400)
        var commits = 0
        var cancels = 0
        view.onScrubCommit = { _, _ -> commits++ }
        view.onScrubCancel = { cancels++ }

        touch(view, MotionEvent.ACTION_DOWN, 100f)
        touch(view, MotionEvent.ACTION_CANCEL, 100f)
        assertThat(cancels).isEqualTo(1)
        assertThat(view.gestureStateForTest).isEqualTo("IDLE")

        touch(view, MotionEvent.ACTION_DOWN, 100f)
        touch(view, MotionEvent.ACTION_UP, 100f)      // grace opens
        touch(view, MotionEvent.ACTION_CANCEL, 100f)  // system steals during grace
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertThat(commits).isEqualTo(0)
        assertThat(cancels).isEqualTo(2)
    }

    @Test
    fun `flushPendingCommit commits immediately during grace and no-ops otherwise`() {
        val view = scrubber(width = 400)
        var commits = 0
        view.onScrubCommit = { _, _ -> commits++ }

        view.flushPendingCommit()                     // IDLE: nothing
        assertThat(commits).isEqualTo(0)

        touch(view, MotionEvent.ACTION_DOWN, 100f)
        touch(view, MotionEvent.ACTION_UP, 100f)
        view.flushPendingCommit()                     // grace open: commit NOW
        assertThat(commits).isEqualTo(1)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertThat(commits).isEqualTo(1)              // the timer was disarmed — no double commit
    }
}
