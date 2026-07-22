package dev.reader.ui

import android.content.Context
import java.lang.reflect.Method

/** A source of hardware e-ink clean refreshes, with a graceful "unavailable" state so callers can
 *  fall back to a plain redraw. See [EinkController] (production) and [NoopRefresher] (default/tests). */
interface EpdRefresher {
    /** True only if a real EPD clean refresh resolved on this device and has not degraded. */
    val available: Boolean

    /** Force a full clean refresh of the panel. Returns false if unavailable or the call failed —
     *  the caller then does a plain [android.view.View.invalidate]. Never throws. */
    fun cleanRefresh(): Boolean

    /**
     * Puts the panel into its fast/low-quality update mode (EINK_SCREEN_MODE_SPEED) for chrome —
     * menus, panels, the Aa sheet — where a clean update per frame is unaffordable. Returns false if
     * unavailable or the call failed; the caller then simply draws in the normal mode. Never throws.
     *
     * No-ops (returning true) if already held, so a second enter cannot double-apply. There is
     * nothing to save: the reader's normal state is the default mode, and [exitFastMode] restores it
     * unconditionally.
     */
    fun enterFastMode(): Boolean

    /**
     * Restores the default screen mode (EINK_SCREEN_MODE_DEFAULT = 0), and returns false if fast mode
     * was never entered or the call failed. Never throws.
     *
     * MUST be called from `onPause`. The screen mode is DEVICE-WIDE runtime state, not app state, and
     * is not persisted — a leaked fast mode degrades the whole device UI until something resets it.
     */
    fun exitFastMode(): Boolean
}

/** The no-hardware default: always unavailable, so [PageView] falls back to invalidate() until (and
 *  unless) a real [EinkController] is wired in. Used by tests and by PageView's construction default. */
object NoopRefresher : EpdRefresher {
    override val available: Boolean get() = false
    override fun cleanRefresh(): Boolean = false
    override fun enterFastMode(): Boolean = false
    override fun exitFastMode(): Boolean = false
}

/**
 * Drives the Supernote panel via the hidden `android.os.EinkManager` (reached with
 * `getSystemService("eink")`), calling `screenRefresh(boolean, int)` by reflection. Everything is
 * guarded: a missing class/method/service, or a thrown call, leaves the controller [available] = false
 * and [cleanRefresh] returning false — never an exception into the reader. Once a call throws, the
 * controller marks itself degraded and stops retrying for the session (a firmware that rejects the
 * call will reject every call; retrying each turn is wasted work and log noise).
 *
 * The reflection handle is resolved ONCE at construction. [serviceProvider] is injected so tests can
 * supply a fake manager; production uses [forContext].
 */
class EinkController(serviceProvider: () -> Any?) : EpdRefresher {

    private val manager: Any? = try { serviceProvider() } catch (e: Throwable) { null }

    private val screenRefresh: Method? = manager?.let { m ->
        try {
            m.javaClass.getMethod("screenRefresh", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        } catch (e: Throwable) {
            null
        }
    }

    private val setScreenMode: Method? = manager?.let { m ->
        try {
            m.javaClass.getMethod("setScreenMode", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        } catch (e: Throwable) {
            null
        }
    }

    @Volatile private var degraded = false

    /** Whether fast mode is currently applied, so [exitFastMode] can no-op when it was never entered
     *  and [enterFastMode] can no-op when already held. */
    @Volatile private var fastModeHeld = false

    override val available: Boolean
        get() = !degraded && manager != null && screenRefresh != null

    override fun cleanRefresh(): Boolean {
        val m = manager ?: return false
        val method = screenRefresh ?: return false
        if (degraded) return false
        return try {
            method.invoke(m, FULL_REFRESH_WAIT, FULL_REFRESH_ARG)
            true
        } catch (e: Throwable) {
            degraded = true
            false
        }
    }

    override fun enterFastMode(): Boolean {
        val m = manager ?: return false
        val setter = setScreenMode ?: return false
        if (degraded) return false
        if (fastModeHeld) return true
        return try {
            setter.invoke(m, FAST_SCREEN_MODE, true)
            fastModeHeld = true
            true
        } catch (e: Throwable) {
            degraded = true
            false
        }
    }

    override fun exitFastMode(): Boolean {
        val m = manager ?: return false
        val setter = setScreenMode ?: return false
        if (!fastModeHeld) return false
        // Cleared before the call: if the restore throws and degrades the controller, a later retry
        // must not think fast mode is still held and loop on a dead call.
        fastModeHeld = false
        if (degraded) return false
        return try {
            setter.invoke(m, DEFAULT_SCREEN_MODE, true)
            true
        } catch (e: Throwable) {
            degraded = true
            false
        }
    }

    companion object {
        // Confirmed on-device (Task 1): screenRefresh(true, 0) produces a full clean flash.
        private const val FULL_REFRESH_WAIT = true
        private const val FULL_REFRESH_ARG = 0

        /** EINK_SCREEN_MODE_SPEED — the panel's fast/low-quality waveform, for chrome. Measured on
         *  device in Task 1; see docs/superpowers/plans/2026-07-21-eink-screen-mode-findings.md. */
        private const val FAST_SCREEN_MODE = 2

        /** EINK_SCREEN_MODE_DEFAULT — the crisp full-quality waveform, the reader's normal state. */
        private const val DEFAULT_SCREEN_MODE = 0

        /** Production factory: resolves the panel's e-ink service by name. */
        fun forContext(context: Context): EinkController =
            EinkController { context.getSystemService("eink") }
    }
}
