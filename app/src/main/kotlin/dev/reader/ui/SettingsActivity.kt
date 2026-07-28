package dev.reader.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.reader.R

/**
 * The app's one settings screen, set as a **colophon**.
 *
 * It holds what belongs to the app rather than to a book: the **book folder** ([LibraryActivity]'s
 * indexing root, tappable to change via [DirectoryChooserActivity]) and the colophon itself — the
 * version, and the no-network promise stated in as many words. Everything about how a *page* looks
 * lives in Type, beside the page it changes.
 *
 * It is deliberately the third instance of one surface: same header, same ‹ at the same margin, same
 * sideheads and same cells as Type and back matter. Settings is not a special screen.
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
        setContentView(R.layout.activity_settings)

        sidehead(R.id.settings_head_books, R.string.settings_head_books)
        sidehead(R.id.settings_head_colophon, R.string.settings_head_colophon)

        rootValue = findViewById(R.id.settings_root_path)
        rootValue.text = prefs.rootPath

        findViewById<TextView>(R.id.settings_version).text = versionName()

        // The device has no hardware Back, so this screen carries its own ‹ in the same place every
        // other surface does — the gap the reader's own "‹ Library" was added to close.
        findViewById<View>(R.id.settings_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        findViewById<View>(R.id.settings_change_folder).setOnClickListener {
            startActivity(Intent(this, DirectoryChooserActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Reflect a root just chosen in DirectoryChooserActivity (which wrote prefs and finished
        // back here) without any observer — this screen is re-entered, so onResume re-reads.
        rootValue.text = prefs.rootPath
    }

    private fun sidehead(id: Int, label: Int) {
        findViewById<SideheadView>(id).apply {
            this.label = getString(label)
            form = SideheadView.Form.RULED
        }
    }

    /**
     * What this build calls itself, read from the package rather than BuildConfig so a debug
     * install says what it actually is. Falls back to empty rather than throwing: a colophon that
     * cannot name its version should show nothing, not crash the screen.
     */
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    /** The book-folder value currently shown — a read seam for [SettingsActivityTest]. */
    internal val displayedRootPath: String get() = rootValue.text.toString()
}
