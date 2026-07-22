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
}
