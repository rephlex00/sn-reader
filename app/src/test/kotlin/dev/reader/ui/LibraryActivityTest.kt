package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.R
import dev.reader.data.SortOrder
import org.junit.Test

class LibraryActivityTest {

    // -- sortOrderForMenuItemId -------------------------------------------------------------

    @Test
    fun `each sort menu item maps to its SortOrder`() {
        assertThat(sortOrderForMenuItemId(R.id.sort_title)).isEqualTo(SortOrder.TITLE)
        assertThat(sortOrderForMenuItemId(R.id.sort_author)).isEqualTo(SortOrder.AUTHOR)
        assertThat(sortOrderForMenuItemId(R.id.sort_recently_added)).isEqualTo(SortOrder.RECENTLY_ADDED)
        assertThat(sortOrderForMenuItemId(R.id.sort_recently_opened)).isEqualTo(SortOrder.RECENTLY_OPENED)
    }

    @Test
    fun `an unrelated menu item id maps to no sort order`() {
        assertThat(sortOrderForMenuItemId(-1)).isNull()
    }

    // -- spanCountFor -------------------------------------------------------------------------

    @Test
    fun `a narrow width still gets at least two columns`() {
        assertThat(spanCountFor(widthPx = 100, columnWidthPx = 260)).isEqualTo(2)
    }

    @Test
    fun `span count grows with available width`() {
        assertThat(spanCountFor(widthPx = 1404, columnWidthPx = 260)).isEqualTo(5)
    }

    @Test
    fun `an exact multiple of the column width divides evenly`() {
        assertThat(spanCountFor(widthPx = 780, columnWidthPx = 260)).isEqualTo(3)
    }
}
