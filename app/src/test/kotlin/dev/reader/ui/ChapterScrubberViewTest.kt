package dev.reader.ui

import android.view.MotionEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChapterScrubberViewTest {

    private fun scrubber(width: Int = 400): ChapterScrubberView {
        val view = ChapterScrubberView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, width, 60)
        view.setBook(chapterStartFractions = listOf(0f, 0.25f, 0.5f), progress = 0f)
        return view
    }

    private fun touch(view: ChapterScrubberView, action: Int, x: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, 30f, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    /** Inverts the view's fractionAt geometry (thumbRadius inset), mirroring its math exactly. */
    private fun xForFraction(view: ChapterScrubberView, fraction: Float): Float {
        val thumbRadiusPx = 7f // density 1.0 in Robolectric's default environment
        return thumbRadiusPx + fraction * (view.width - thumbRadiusPx * 2f)
    }

    @Test
    fun `dragging reports the fraction under the finger`() {
        val view = scrubber(width = 400)
        val moves = mutableListOf<Float>()
        view.onScrubMove = { moves += it }

        touch(view, MotionEvent.ACTION_DOWN, 200f)

        assertThat(moves).hasSize(1)
        assertThat(moves.single()).isWithin(0.05f).of(0.5f)
    }

    @Test
    fun `lifting off commits once with the final fraction`() {
        val view = scrubber(width = 400)
        var commits = 0
        var committed = -1f
        view.onScrubCommit = { commits++; committed = it }

        touch(view, MotionEvent.ACTION_DOWN, 100f)
        touch(view, MotionEvent.ACTION_MOVE, 300f)
        touch(view, MotionEvent.ACTION_UP, 300f)

        assertThat(commits).isEqualTo(1)
        assertThat(committed).isWithin(0.05f).of(0.75f)
    }

    @Test
    fun `a fraction beyond either edge is clamped`() {
        val view = scrubber(width = 400)
        val moves = mutableListOf<Float>()
        view.onScrubMove = { moves += it }

        touch(view, MotionEvent.ACTION_DOWN, -80f)
        touch(view, MotionEvent.ACTION_MOVE, 9000f)

        assertThat(moves.first()).isEqualTo(0f)
        assertThat(moves.last()).isEqualTo(1f)
    }

    @Test
    fun `a cancelled gesture commits nothing`() {
        val view = scrubber()
        var commits = 0
        view.onScrubCommit = { commits++ }

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
        view.onScrubCommit = { commits++ }

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
    fun `bookmark fractions merge within the radius`() {
        assertThat(mergedBookmarkFractions(listOf(0.10f, 0.11f, 0.50f))).hasSize(2)
        assertThat(mergedBookmarkFractions(emptyList())).isEmpty()
        // A merged cluster is represented once, near its members.
        val merged = mergedBookmarkFractions(listOf(0.10f, 0.11f, 0.12f))
        assertThat(merged).hasSize(1)
        assertThat(merged.single()).isWithin(0.02f).of(0.11f)
    }

    @Test
    fun `a drag reports snapped fractions`() {
        val view = scrubber(width = 400) // helper exists; setBook installs starts 0f, 0.25f, 0.5f
        val moves = mutableListOf<Float>()
        view.onScrubMove = { moves += it }

        // x for raw fraction ~0.26 on a 400px track lands within snap radius of 0.25.
        touch(view, MotionEvent.ACTION_DOWN, xForFraction(view, 0.26f))

        assertThat(moves.single()).isEqualTo(0.25f)
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
}
