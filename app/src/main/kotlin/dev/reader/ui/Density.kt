package dev.reader.ui

import android.content.Context
import kotlin.math.roundToInt

/**
 * dp to px, for the two screens built in code rather than XML.
 *
 * Every layout in `res/layout` states its spacing in dp and is scaled by the framework.
 * [SettingsActivity] and [DirectoryChooserActivity] build their views programmatically, where
 * `setPadding` takes **pixels** — so the raw integers they used to pass were device-dependent
 * spacing that happened to look about right on one panel and would be wrong on any other density.
 * This makes those two screens agree with the rest of the app.
 */
internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()
