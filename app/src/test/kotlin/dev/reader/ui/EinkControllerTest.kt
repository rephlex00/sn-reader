package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EinkControllerTest {

    // A stand-in for android.os.EinkManager: EinkController finds screenRefresh(boolean,int) by reflection.
    class FakeManager {
        val calls = mutableListOf<Pair<Boolean, Int>>()
        fun screenRefresh(wait: Boolean, mode: Int) { calls += wait to mode }
    }

    class ThrowingManager {
        fun screenRefresh(wait: Boolean, mode: Int): Unit = throw RuntimeException("boom")
    }

    @Test
    fun `resolves and invokes screenRefresh with the full-refresh args`() {
        val fake = FakeManager()
        val c = EinkController { fake }
        assertThat(c.available).isTrue()
        assertThat(c.cleanRefresh()).isTrue()
        assertThat(fake.calls).containsExactly(true to 0)
    }

    @Test
    fun `is unavailable and no-ops when the service is absent`() {
        val c = EinkController { null }
        assertThat(c.available).isFalse()
        assertThat(c.cleanRefresh()).isFalse()
    }

    @Test
    fun `is unavailable when the object has no screenRefresh method`() {
        val c = EinkController { Any() }
        assertThat(c.available).isFalse()
        assertThat(c.cleanRefresh()).isFalse()
    }

    @Test
    fun `a throwing call degrades to false and stops retrying`() {
        val c = EinkController { ThrowingManager() }
        assertThat(c.cleanRefresh()).isFalse()
        assertThat(c.available).isFalse() // degraded after the throw — callers fall back for the session
        assertThat(c.cleanRefresh()).isFalse()
    }

    @Test
    fun `a throwing service provider is caught at construction`() {
        val c = EinkController { throw RuntimeException("no service") }
        assertThat(c.available).isFalse()
        assertThat(c.cleanRefresh()).isFalse()
    }

    // A stand-in for android.os.EinkManager's fast-mode axis: EinkController finds
    // setScreenMode(int,boolean) by reflection. Signature must match exactly.
    class FakeModeManager {
        val screenModeCalls = mutableListOf<Int>()
        var throwOnSet = false

        fun screenRefresh(wait: Boolean, arg: Int) = Unit
        fun setScreenMode(mode: Int, flag: Boolean) {
            if (throwOnSet) throw RuntimeException("firmware says no")
            screenModeCalls += mode
        }
    }

    @Test
    fun `entering fast mode sets SPEED and exiting restores DEFAULT`() {
        val manager = FakeModeManager()
        val controller = EinkController { manager }

        assertThat(controller.enterFastMode()).isTrue()
        assertThat(manager.screenModeCalls.last()).isEqualTo(2) // EINK_SCREEN_MODE_SPEED

        assertThat(controller.exitFastMode()).isTrue()
        assertThat(manager.screenModeCalls.last()).isEqualTo(0) // EINK_SCREEN_MODE_DEFAULT
    }

    @Test
    fun `exiting without entering does nothing`() {
        val manager = FakeModeManager()
        val controller = EinkController { manager }

        assertThat(controller.exitFastMode()).isFalse()
        assertThat(manager.screenModeCalls).isEmpty()
    }

    @Test
    fun `entering while already held does not re-enter`() {
        val manager = FakeModeManager()
        val controller = EinkController { manager }

        controller.enterFastMode()
        controller.enterFastMode()

        // Only one SPEED set — the second enter is a no-op while already held.
        assertThat(manager.screenModeCalls).containsExactly(2)
    }

    @Test
    fun `a throwing setScreenMode degrades permanently instead of propagating`() {
        val manager = FakeModeManager().apply { throwOnSet = true }
        val controller = EinkController { manager }

        assertThat(controller.enterFastMode()).isFalse()
        assertThat(controller.available).isFalse()
        assertThat(controller.cleanRefresh()).isFalse()
    }

    @Test
    fun `a manager without setScreenMode is a silent no-op`() {
        val controller = EinkController { object { fun screenRefresh(wait: Boolean, arg: Int) = Unit } }

        assertThat(controller.enterFastMode()).isFalse()
        assertThat(controller.exitFastMode()).isFalse()
    }
}
