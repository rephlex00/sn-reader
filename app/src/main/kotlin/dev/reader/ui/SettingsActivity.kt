package dev.reader.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * The app's one settings screen. Today it holds a single setting — the **book folder** (the root
 * [LibraryActivity] indexes and shows) — displayed with its current value and tappable to change
 * via [DirectoryChooserActivity]. Reached from the library toolbar's overflow.
 *
 * There is no observer or refresh machinery tying this to the library: the chooser writes
 * [LibraryPrefs.rootPath] and finishes, this screen re-reads it in [onResume] to update the shown
 * value, and the next [LibraryActivity.onStart] sync picks the new root up on its own (sync runs
 * on every entry — see that class's KDoc).
 *
 * `open` only so a Robolectric test can instantiate it; no member is `open` — the screen touches
 * no device permission or real EPUB, so it needs no test seam beyond that.
 */
open class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { LibraryPrefs(this) }
    private lateinit var rootValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toolbar = Toolbar(this).apply {
            title = "Settings"
            // An explicit up affordance: this screen is reached from the library overflow and had
            // no on-screen way back — only the device's Back gesture, the same discoverability gap
            // the reader's own "‹ Back" control was added to close.
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationContentDescription = "Back"
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }

        val label = TextView(this).apply {
            text = "Book folder"
            textSize = 16f
        }
        rootValue = TextView(this).apply {
            text = prefs.rootPath
            setPadding(0, 8, 0, 0)
        }
        val bookFolderRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
            isClickable = true
            addView(label)
            addView(rootValue)
            setOnClickListener { startActivity(Intent(this@SettingsActivity, DirectoryChooserActivity::class.java)) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(bookFolderRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // Reflect a root just chosen in DirectoryChooserActivity (which wrote prefs and finished
        // back here) without any observer — this screen is re-entered, so onResume re-reads.
        rootValue.text = prefs.rootPath
    }

    /** The book-folder value currently shown — a read seam for [SettingsActivityTest]. */
    internal val displayedRootPath: String get() = rootValue.text.toString()
}
