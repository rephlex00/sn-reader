package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.engine.ReadingState
import org.junit.Test

class JumpStackTest {

    @Test
    fun `pops in reverse push order and empties`() {
        val stack = JumpStack()
        stack.push(ReadingState(1, 2))
        stack.push(ReadingState(3, 4))

        assertThat(stack.pop()).isEqualTo(ReadingState(3, 4))
        assertThat(stack.pop()).isEqualTo(ReadingState(1, 2))
        assertThat(stack.pop()).isNull()
        assertThat(stack.isEmpty).isTrue()
    }

    @Test
    fun `the cap drops the OLDEST entry, not the newest`() {
        val stack = JumpStack(cap = 3)
        for (i in 0 until 5) stack.push(ReadingState(i, 0))

        assertThat(stack.pop()!!.spineIndex).isEqualTo(4)
        assertThat(stack.pop()!!.spineIndex).isEqualTo(3)
        assertThat(stack.pop()!!.spineIndex).isEqualTo(2)
        assertThat(stack.pop()).isNull()
    }

    @Test
    fun `clear empties without navigation`() {
        val stack = JumpStack()
        stack.push(ReadingState(1, 1))
        stack.clear()
        assertThat(stack.isEmpty).isTrue()
    }
}
