package dev.reader.ui

import android.content.Context
import android.os.Environment
import com.google.common.truth.Truth.assertThat
import dev.reader.data.SortOrder
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LibraryPrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Each test starts from a clean slate — SharedPreferences is a file Robolectric reuses
        // within a JVM fork.
        context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `defaults match today's behavior on a fresh install`() {
        val prefs = LibraryPrefs(context)
        assertThat(prefs.rootPath)
            .isEqualTo(File(Environment.getExternalStorageDirectory(), "Document").path)
        assertThat(prefs.viewMode).isEqualTo(ViewMode.TILES)
        assertThat(prefs.flatten).isFalse()
        assertThat(prefs.sortOrder).isEqualTo(SortOrder.TITLE)
        assertThat(prefs.lastFolderPath).isNull()
    }

    @Test
    fun `every field round-trips through a write`() {
        val prefs = LibraryPrefs(context)
        prefs.rootPath = "/storage/emulated/0/Books"
        prefs.viewMode = ViewMode.LIST
        prefs.flatten = true
        prefs.sortOrder = SortOrder.RECENTLY_OPENED
        prefs.lastFolderPath = "/storage/emulated/0/Books/Sci-Fi"

        assertThat(prefs.rootPath).isEqualTo("/storage/emulated/0/Books")
        assertThat(prefs.viewMode).isEqualTo(ViewMode.LIST)
        assertThat(prefs.flatten).isTrue()
        assertThat(prefs.sortOrder).isEqualTo(SortOrder.RECENTLY_OPENED)
        assertThat(prefs.lastFolderPath).isEqualTo("/storage/emulated/0/Books/Sci-Fi")
    }

    @Test
    fun `a written value persists across LibraryPrefs instances`() {
        LibraryPrefs(context).sortOrder = SortOrder.AUTHOR
        // A fresh wrapper over the same context (a new process would do the same) reads it back —
        // this is exactly the cold-launch survival the Bundle mechanism never had.
        assertThat(LibraryPrefs(context).sortOrder).isEqualTo(SortOrder.AUTHOR)
    }

    @Test
    fun `a corrupt sort order falls back to the default rather than throwing`() {
        context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
            .edit().putString("sort_order", "NOT_A_REAL_ORDER").commit()
        assertThat(LibraryPrefs(context).sortOrder).isEqualTo(SortOrder.TITLE)
    }

    @Test
    fun `a corrupt view mode falls back to the default rather than throwing`() {
        context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
            .edit().putString("view_mode", "GALAXY").commit()
        assertThat(LibraryPrefs(context).viewMode).isEqualTo(ViewMode.TILES)
    }

    @Test
    fun `lastFolderPath can be cleared back to null`() {
        val prefs = LibraryPrefs(context)
        prefs.lastFolderPath = "/somewhere"
        prefs.lastFolderPath = null
        assertThat(prefs.lastFolderPath).isNull()
    }
}
