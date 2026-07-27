package dev.reader.ui

/**
 * The reader's way back: every JUMP (a scrub commit, a Contents/bookmark/highlight jump) pushes the
 * position being left; the ↩ control pops. Page turns never push — the stack unwinds wandering, not
 * reading. Back-only by design: popping does not re-push, so walking back is one-way, which is what
 * "take me back to where I was" means.
 *
 * In-memory, per book-open, capped at [cap] (oldest dropped): it exists to unwind THIS session's
 * jumps; resurrecting a prior session's history as tap targets would surprise more than help, and
 * it keeps the schema untouched. Pure Kotlin — JVM-tested.
 *
 * Generic over the type of position being left: the EPUB reader pushes [dev.reader.engine.ReadingState],
 * the comic reader pushes a page index (`Int`).
 */
class JumpStack<T>(private val cap: Int = 20) {

    private val entries = ArrayDeque<T>()

    val isEmpty: Boolean get() = entries.isEmpty()

    fun push(value: T) {
        entries.addLast(value)
        while (entries.size > cap) entries.removeFirst()
    }

    fun pop(): T? = entries.removeLastOrNull()

    fun clear() = entries.clear()
}
