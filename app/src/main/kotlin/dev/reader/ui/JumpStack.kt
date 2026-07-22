package dev.reader.ui

import dev.reader.engine.ReadingState

/**
 * The reader's way back: every JUMP (a scrub commit, a Contents/bookmark/highlight jump) pushes the
 * position being left; the ↩ control pops. Page turns never push — the stack unwinds wandering, not
 * reading. Back-only by design: popping does not re-push, so walking back is one-way, which is what
 * "take me back to where I was" means.
 *
 * In-memory, per book-open, capped at [cap] (oldest dropped): it exists to unwind THIS session's
 * jumps; resurrecting a prior session's history as tap targets would surprise more than help, and
 * it keeps the schema untouched. Pure Kotlin — JVM-tested.
 */
class JumpStack(private val cap: Int = 20) {

    private val entries = ArrayDeque<ReadingState>()

    val isEmpty: Boolean get() = entries.isEmpty()

    fun push(state: ReadingState) {
        entries.addLast(state)
        while (entries.size > cap) entries.removeFirst()
    }

    fun pop(): ReadingState? = entries.removeLastOrNull()

    fun clear() = entries.clear()
}
