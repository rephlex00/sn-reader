package dev.reader.ui

import android.content.Context
import com.google.common.truth.Truth.assertThat
import dev.reader.engine.RenderConfig
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReaderPrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Each test starts from a clean slate — SharedPreferences is a file Robolectric reuses
        // within a JVM fork. A separate file from LibraryPrefs, so the two stores never collide.
        context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `defaults equal today's hardcoded literals on a fresh install`() {
        val prefs = ReaderPrefs(context)
        assertThat(prefs.fontFamily).isEqualTo("serif")
        assertThat(prefs.textSizePx).isEqualTo(34f)
        assertThat(prefs.lineSpacingMultiplier).isEqualTo(1.4f)
        assertThat(prefs.marginPx).isEqualTo(48)
        assertThat(prefs.justified).isTrue()
        assertThat(prefs.hyphenated).isTrue()
        assertThat(prefs.inferHeadings).isTrue()
        assertThat(prefs.publisherStyling).isTrue()
    }

    @Test
    fun `every field round-trips through a write`() {
        val prefs = ReaderPrefs(context)
        prefs.fontFamily = "monospace"
        prefs.textSizePx = 40f
        prefs.lineSpacingMultiplier = 1.6f
        prefs.marginPx = 64
        prefs.justified = false
        prefs.hyphenated = false
        prefs.inferHeadings = false
        prefs.publisherStyling = false

        assertThat(prefs.fontFamily).isEqualTo("monospace")
        assertThat(prefs.textSizePx).isEqualTo(40f)
        assertThat(prefs.lineSpacingMultiplier).isEqualTo(1.6f)
        assertThat(prefs.marginPx).isEqualTo(64)
        assertThat(prefs.justified).isFalse()
        assertThat(prefs.hyphenated).isFalse()
        assertThat(prefs.inferHeadings).isFalse()
        assertThat(prefs.publisherStyling).isFalse()
    }

    @Test
    fun `a written value persists across ReaderPrefs instances`() {
        ReaderPrefs(context).textSizePx = 42f
        // A fresh wrapper over the same context (a new process would do the same) reads it back —
        // the cold-launch survival that the hardcoded literals could never offer.
        assertThat(ReaderPrefs(context).textSizePx).isEqualTo(42f)
    }

    @Test
    fun `an untouched ReaderPrefs builds exactly the RenderConfig the old literals produced`() {
        // The no-op guarantee: with defaults equal to the old literals and a fixed viewport, the
        // built config is identical to the one openFirstBook used to hardcode. This pins the
        // task as a behavior no-op until the Aa sheet changes a value.
        val prefs = ReaderPrefs(context)
        val built = prefs.renderConfig(viewportWidthPx = 1404, viewportHeightPx = 1872)

        val old = RenderConfig(
            fontFamily = "serif",
            textSizePx = 34f,
            lineSpacingMultiplier = 1.4f,
            marginPx = 48,
            justified = true,
            hyphenated = true,
            viewportWidthPx = 1404,
            viewportHeightPx = 1872,
        )
        assertThat(built).isEqualTo(old)
    }

    @Test
    fun `the widest margin preset builds a valid RenderConfig on the device viewport`() {
        // The Aa sheet's widest preset (80px) must never hand RenderConfig.init a non-positive
        // content width or height on the real ~1404x1872 panel. This pins that the config builds
        // (init would throw otherwise) and that both content dimensions stay positive.
        val prefs = ReaderPrefs(context)
        prefs.marginPx = 80

        val built = prefs.renderConfig(viewportWidthPx = 1404, viewportHeightPx = 1872)
        assertThat(built.marginPx).isEqualTo(80)
        assertThat(built.contentWidthPx).isGreaterThan(0)
        assertThat(built.contentHeightPx).isGreaterThan(0)
    }

    @Test
    fun `renderConfig carries a changed field through to the built config`() {
        val prefs = ReaderPrefs(context)
        prefs.textSizePx = 40f
        prefs.publisherStyling = false

        val built = prefs.renderConfig(viewportWidthPx = 1404, viewportHeightPx = 1872)
        assertThat(built.textSizePx).isEqualTo(40f)
        assertThat(built.publisherStyling).isFalse()
        assertThat(built.viewportWidthPx).isEqualTo(1404)
        assertThat(built.viewportHeightPx).isEqualTo(1872)
    }
}
