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
    fun `defaults are the shipped reader baseline on a fresh install`() {
        val prefs = ReaderPrefs(context)
        assertThat(prefs.fontFamily).isEqualTo("literata")
        assertThat(prefs.textSizePx).isEqualTo(34f)
        assertThat(prefs.lineSpacingMultiplier).isEqualTo(1.4f)
        assertThat(prefs.marginPx).isEqualTo(72)
        assertThat(prefs.justified).isTrue()
        assertThat(prefs.hyphenated).isTrue()
        assertThat(prefs.inferHeadings).isTrue()
        assertThat(prefs.publisherStyling).isTrue()
        assertThat(prefs.showProgressBar).isTrue()
    }

    @Test
    fun `every field round-trips through a write`() {
        val prefs = ReaderPrefs(context)
        prefs.fontFamily = "bitter"
        prefs.textSizePx = 40f
        prefs.lineSpacingMultiplier = 1.6f
        prefs.marginPx = 64
        prefs.justified = false
        prefs.hyphenated = false
        prefs.inferHeadings = false
        prefs.publisherStyling = false
        prefs.showProgressBar = false

        assertThat(prefs.fontFamily).isEqualTo("bitter")
        assertThat(prefs.textSizePx).isEqualTo(40f)
        assertThat(prefs.lineSpacingMultiplier).isEqualTo(1.6f)
        assertThat(prefs.marginPx).isEqualTo(64)
        assertThat(prefs.justified).isFalse()
        assertThat(prefs.hyphenated).isFalse()
        assertThat(prefs.inferHeadings).isFalse()
        assertThat(prefs.publisherStyling).isFalse()
        assertThat(prefs.showProgressBar).isFalse()
    }

    @Test
    fun `a written value persists across ReaderPrefs instances`() {
        ReaderPrefs(context).textSizePx = 42f
        // A fresh wrapper over the same context (a new process would do the same) reads it back —
        // the cold-launch survival that the hardcoded literals could never offer.
        assertThat(ReaderPrefs(context).textSizePx).isEqualTo(42f)
    }

    @Test
    fun `an untouched ReaderPrefs builds the shipped default RenderConfig`() {
        // Pins the exact config the reader opens with on a fresh install (Literata baseline), so a
        // stray change to a default or the mapping is caught. The font default is "literata" since
        // bundled fonts shipped; everything else is the reader's standing baseline.
        val prefs = ReaderPrefs(context)
        val built = prefs.renderConfig(viewportWidthPx = 1404, viewportHeightPx = 1872)

        val expected = RenderConfig(
            fontFamily = "literata",
            textSizePx = 34f,
            lineSpacingMultiplier = 1.4f,
            marginPx = 72,
            justified = true,
            hyphenated = true,
            viewportWidthPx = 1404,
            viewportHeightPx = 1872,
        )
        assertThat(built).isEqualTo(expected)
    }

    @Test
    fun `the widest margin preset builds a valid RenderConfig on the device viewport`() {
        // The Aa sheet's widest preset (120px) must never hand RenderConfig.init a non-positive
        // content width or height on the real ~1404x1872 panel. This pins that the config builds
        // (init would throw otherwise) and that both content dimensions stay positive.
        val prefs = ReaderPrefs(context)
        prefs.marginPx = 120

        val built = prefs.renderConfig(viewportWidthPx = 1404, viewportHeightPx = 1872)
        assertThat(built.marginPx).isEqualTo(120)
        assertThat(built.contentWidthPx).isGreaterThan(0)
        assertThat(built.contentHeightPx).isGreaterThan(0)
    }

    @Test
    fun `a margin deeper than the bottom chrome reserves nothing`() {
        // The medium and wide presets already clear the foot on their own, so pagination must be
        // byte-identical to before the reserve existed — no silently lost line at the default.
        val prefs = ReaderPrefs(context)
        prefs.marginPx = 72

        val built = prefs.renderConfig(1404, 1872, bottomChromePx = 60)
        assertThat(built.viewportHeightPx).isEqualTo(1872)
    }

    @Test
    fun `a margin shallower than the bottom chrome gives the shortfall back to the chrome`() {
        // The narrow preset (40px) is shallower than the progress bar + running foot band, so the
        // text area must shrink by exactly the difference. Without this the foot overdraws the
        // last line, which is what happened at every foot size the app has shipped.
        val prefs = ReaderPrefs(context)
        prefs.marginPx = 40

        val built = prefs.renderConfig(1404, 1872, bottomChromePx = 60)
        assertThat(built.viewportHeightPx).isEqualTo(1872 - 20)
        assertThat(built.marginPx).isEqualTo(40) // the margin itself is untouched
    }

    @Test
    fun `omitting the bottom chrome reserves nothing`() {
        val prefs = ReaderPrefs(context)
        prefs.marginPx = 0

        assertThat(prefs.renderConfig(1404, 1872).viewportHeightPx).isEqualTo(1872)
    }

    @Test
    fun `chrome taller than the whole viewport still leaves a content pixel`() {
        // Defensive, mirroring the margin clamp above: an absurd chrome height must not drive
        // contentHeightPx to zero and make RenderConfig.init throw on the way into the reader.
        val prefs = ReaderPrefs(context)
        prefs.marginPx = 40

        val built = prefs.renderConfig(1404, 1872, bottomChromePx = 9999)
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

    @Test
    fun `toggling the progress bar does not change the RenderConfig (it must never re-paginate)`() {
        // The progress bar is a display-only toggle that must never reach renderConfig. If it ever
        // did, toggling it would trigger a re-paginate, which would disturb the reader's
        // locator-preserving reflow machinery on an e-ink device. This test is the tripwire: if
        // someone wires showProgressBar into RenderConfig, the configs will no longer be equal.
        val prefs = ReaderPrefs(context)
        val configBefore = prefs.renderConfig(viewportWidthPx = 1404, viewportHeightPx = 1872)

        prefs.showProgressBar = false
        val configAfter = prefs.renderConfig(viewportWidthPx = 1404, viewportHeightPx = 1872)

        assertThat(configAfter).isEqualTo(configBefore)
    }

    @Test
    fun `faster page turns defaults off and round-trips`() {
        val prefs = ReaderPrefs(context)
        assertThat(prefs.fasterPageTurns).isFalse()
        prefs.fasterPageTurns = true
        assertThat(ReaderPrefs(context).fasterPageTurns).isTrue()
    }

    @Test
    fun `full refresh frequency defaults to 6 and round-trips an offered value`() {
        val prefs = ReaderPrefs(context)
        assertThat(prefs.fullRefreshEveryN).isEqualTo(6)
        prefs.fullRefreshEveryN = 10
        assertThat(ReaderPrefs(context).fullRefreshEveryN).isEqualTo(10)
    }

    @Test
    fun `an out-of-set stored frequency falls back to the default on read`() {
        val prefs = ReaderPrefs(context)
        prefs.fullRefreshEveryN = 999 // not in REFRESH_FREQUENCY_OPTIONS
        assertThat(ReaderPrefs(context).fullRefreshEveryN).isEqualTo(6)
    }

    @Test
    fun `the offered frequencies are 3 6 10`() {
        assertThat(REFRESH_FREQUENCY_OPTIONS).containsExactly(3, 6, 10).inOrder()
    }

    @Test
    fun `the default margin is the medium preset`() {
        assertThat(ReaderPrefs(RuntimeEnvironment.getApplication()).marginPx).isEqualTo(72)
    }
}
