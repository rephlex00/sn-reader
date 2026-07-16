package dev.reader.ui

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {

    @Before
    fun setUp() {
        // library_prefs is a file Robolectric reuses across tests in a fork — start clean.
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `it shows the current book folder from prefs`() {
        LibraryPrefs(RuntimeEnvironment.getApplication()).rootPath = "/storage/emulated/0/Books"

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()

        assertThat(controller.get().displayedRootPath).isEqualTo("/storage/emulated/0/Books")
    }

    @Test
    fun `it re-reads the book folder on resume after a change`() {
        val prefs = LibraryPrefs(RuntimeEnvironment.getApplication())
        prefs.rootPath = "/storage/emulated/0/Books"
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()

        // Stand in for returning from the chooser, which writes prefs then finishes back here.
        controller.pause()
        prefs.rootPath = "/storage/emulated/0/Novels"
        controller.resume()

        assertThat(controller.get().displayedRootPath).isEqualTo("/storage/emulated/0/Novels")
    }
}
