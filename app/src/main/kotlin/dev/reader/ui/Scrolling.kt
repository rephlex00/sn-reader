package dev.reader.ui

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Makes a list scroll without animating once the finger has lifted.
 *
 * Nulling `itemAnimator` (which every list here already does) only stops *item change* animations.
 * Two framework defaults survive it, and both animate:
 *
 *  - **Over-scroll glow.** Hitting either end draws a fading `EdgeEffect`, invalidating the view for
 *    several frames after the gesture is over.
 *  - **Fling.** Lifting the finger with velocity hands off to an `OverScroller` driven by
 *    `Choreographer`, which keeps redrawing for up to a second or two as it decays.
 *
 * On an LCD both are invisible polish. On e-ink each redraw is a visible partial refresh, so a
 * single flick leaves the list smearing and ghosting long after the user stopped touching it, which
 * is worse than the deliberate page turn the reader is careful to make clean.
 *
 * Swallowing the fling means a list travels exactly as far as the finger dragged it and then stops
 * dead. That is a real interaction trade: long lists need more drags. It is the intended behaviour
 * here, matching the rest of the app's no-animation rule.
 */
internal fun RecyclerView.stopScrollAnimations() {
    overScrollMode = View.OVER_SCROLL_NEVER
    onFlingListener = object : RecyclerView.OnFlingListener() {
        /** true = "handled", so RecyclerView does not start its own decay animation. */
        override fun onFling(velocityX: Int, velocityY: Int): Boolean = true
    }
}
