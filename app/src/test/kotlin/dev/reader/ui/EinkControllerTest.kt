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
}
