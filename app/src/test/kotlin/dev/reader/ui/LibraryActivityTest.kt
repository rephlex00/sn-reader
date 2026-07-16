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

    // -- sortOrderToSavedValue / sortOrderFromSavedValue -----------------------------------
    // Rotation destroys and recreates LibraryActivity; onSaveInstanceState/onCreate round-trip
    // currentSort through a Bundle string via these two pure functions so the chosen order
    // survives instead of snapping back to TITLE.

    @Test
    fun `every SortOrder round-trips through save and restore`() {
        for (order in SortOrder.entries) {
            assertThat(sortOrderFromSavedValue(sortOrderToSavedValue(order))).isEqualTo(order)
        }
    }

    @Test
    fun `no saved value (first launch) restores to TITLE`() {
        assertThat(sortOrderFromSavedValue(null)).isEqualTo(SortOrder.TITLE)
    }

    @Test
    fun `an unrecognized saved value restores to TITLE rather than throwing`() {
        assertThat(sortOrderFromSavedValue("NOT_A_REAL_SORT_ORDER")).isEqualTo(SortOrder.TITLE)
    }

    // -- menuItemIdForSortOrder --------------------------------------------------------------

    @Test
    fun `every SortOrder maps to the menu item that selects it`() {
        for (order in SortOrder.entries) {
            assertThat(sortOrderForMenuItemId(menuItemIdForSortOrder(order))).isEqualTo(order)
        }
    }
}
