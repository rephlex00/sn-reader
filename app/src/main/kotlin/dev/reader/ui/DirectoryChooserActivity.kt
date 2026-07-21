package dev.reader.ui

import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.reader.R
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Case-insensitive **natural** order for folder names, so `page2` sorts before `page10` rather
 * than after it. Digit runs compare by numeric value (leading zeros ignored, shorter number
 * first), everything else compares lexicographically lowercased. Pulled out as a pure top-level
 * value so it is unit-testable without a device.
 */
val NATURAL_NAME_ORDER: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

private fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var i2 = i
            while (i2 < a.length && a[i2].isDigit()) i2++
            var j2 = j
            while (j2 < b.length && b[j2].isDigit()) j2++
            val na = a.substring(i, i2).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(j, j2).trimStart('0').ifEmpty { "0" }
            val cmp = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
            if (cmp != 0) return cmp
            i = i2
            j = j2
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return cmp
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

/**
 * A deliberately minimal folder picker: it exists to set [LibraryPrefs.rootPath] the roughly once
 * a user ever re-points their library, so it has no search, no file display, and no gold-plating.
 * It lists the *directories* of the current location (starting at
 * [Environment.getExternalStorageDirectory]), naturally sorted; tapping one descends into it, an
 * "Up" control ascends (disabled at the storage root), and "Use this folder" writes the current
 * directory as the root and finishes back to [SettingsActivity].
 *
 * **It never writes anything under the storage tree** — it only reads directory listings and, on
 * confirm, writes the app's own [LibraryPrefs]. Each navigation does a single, non-recursive
 * [File.listFiles] on [Dispatchers.IO], never on the main thread. There is no service, observer,
 * timer, or poll here — every load is driven by a user tap and completes.
 *
 * `open` only so a Robolectric test can instantiate it; no member needs a test seam.
 */
open class DirectoryChooserActivity : AppCompatActivity() {

    private val prefs by lazy { LibraryPrefs(this) }
    private val storageRoot: File = Environment.getExternalStorageDirectory()

    private var currentDir: File = storageRoot

    private lateinit var pathLabel: TextView
    private lateinit var upButton: Button
    private lateinit var adapter: DirAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toolbar = Toolbar(this).apply {
            title = getString(R.string.chooser_title)
            // Up affordance (cancel/return): without it the only way out was the device Back
            // gesture. This returns to Settings without choosing a folder — the "Up" button in the
            // list navigates the directory tree, a separate concern.
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationContentDescription = getString(R.string.action_back)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }

        pathLabel = TextView(this).apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }

        upButton = Button(this).apply {
            text = getString(R.string.chooser_up)
            setOnClickListener {
                // The storage root is the floor: never ascend above it (the button is also
                // disabled there, so this is belt-and-braces). Below it, every parent is in range.
                if (currentDir.path == storageRoot.path) return@setOnClickListener
                currentDir.parentFile?.let { parent ->
                    currentDir = parent
                    loadCurrentDir()
                }
            }
        }

        adapter = DirAdapter { dir ->
            currentDir = dir
            loadCurrentDir()
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DirectoryChooserActivity)
            // Same reason as everywhere else in this app: DefaultItemAnimator cross-fades on every
            // change, a smear on e-ink and banned outright.
            itemAnimator = null
            stopScrollAnimations()
            this.adapter = this@DirectoryChooserActivity.adapter
        }

        val useButton = Button(this).apply {
            text = getString(R.string.chooser_use_this_folder)
            setOnClickListener {
                prefs.rootPath = currentDir.path
                // A folder remembered under the OLD root is meaningless under the new one; clear it
                // so the library opens at the new root rather than a stale, now-out-of-scope folder.
                prefs.lastFolderPath = null
                finish()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(pathLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(upButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(useButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        loadCurrentDir()
    }

    private fun loadCurrentDir() {
        pathLabel.text = currentDir.path
        // Up is available only while we are strictly below the storage root — the floor this
        // chooser will not climb above.
        upButton.isEnabled = isAtOrBelowStorageRoot(currentDir) && currentDir != storageRoot
        lifecycleScope.launch {
            val dirs = withContext(Dispatchers.IO) { directoriesIn(currentDir) }
            adapter.submit(dirs)
        }
    }

    /** True while [dir] is the storage root or somewhere beneath it — the chooser's allowed range. */
    private fun isAtOrBelowStorageRoot(dir: File): Boolean =
        dir.path == storageRoot.path || dir.path.startsWith(storageRoot.path + File.separator)

    private class DirAdapter(val onClick: (File) -> Unit) : RecyclerView.Adapter<DirAdapter.VH>() {
        private var dirs: List<File> = emptyList()

        fun submit(items: List<File>) {
            dirs = items
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val tv = TextView(ctx).apply {
                setPadding(ctx.dp(24), ctx.dp(20), ctx.dp(24), ctx.dp(20))
                textSize = 16f
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val dir = dirs[position]
            (holder.itemView as TextView).text = dir.name
            holder.itemView.setOnClickListener { onClick(dir) }
        }

        override fun getItemCount(): Int = dirs.size

        class VH(view: View) : RecyclerView.ViewHolder(view)
    }
}

/**
 * The immediate subdirectories of [dir], naturally sorted by name. Non-recursive, and hidden
 * (dot-prefixed) directories are skipped. Returns empty on a null [File.listFiles] (a denied or
 * unreadable directory) rather than throwing — an empty list is a fine "nothing to descend into".
 */
private fun directoriesIn(dir: File): List<File> =
    (dir.listFiles() ?: emptyArray())
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .sortedWith(compareBy(NATURAL_NAME_ORDER) { it.name })
