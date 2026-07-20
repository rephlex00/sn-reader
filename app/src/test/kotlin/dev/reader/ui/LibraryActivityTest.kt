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

    // -- sortOrderFromSavedValue -----------------------------------------------------------
    // LibraryPrefs is now the source of truth for the active sort (it persists across process
    // death, unlike the old saved-instance Bundle); it parses the stored SortOrder name through
    // this pure fallback function so a corrupt or absent value can never crash the launcher.

    @Test
    fun `every SortOrder name parses back to itself`() {
        for (order in SortOrder.entries) {
            assertThat(sortOrderFromSavedValue(order.name)).isEqualTo(order)
        }
    }

    @Test
    fun `no stored value (first launch) restores to TITLE`() {
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

    // -- statusFilterForMenuItemId -----------------------------------------------------------

    @Test
    fun `each filter menu item maps to its StatusFilter`() {
        assertThat(statusFilterForMenuItemId(R.id.filter_all)).isEqualTo(StatusFilter.ALL)
        assertThat(statusFilterForMenuItemId(R.id.filter_not_started)).isEqualTo(StatusFilter.NOT_STARTED)
        assertThat(statusFilterForMenuItemId(R.id.filter_in_progress)).isEqualTo(StatusFilter.IN_PROGRESS)
        assertThat(statusFilterForMenuItemId(R.id.filter_finished)).isEqualTo(StatusFilter.FINISHED)
    }

    @Test
    fun `an unrelated menu item id maps to no status filter`() {
        assertThat(statusFilterForMenuItemId(-1)).isNull()
    }

    // -- menuItemIdForStatusFilter (the checkmark inverse) -------------------------------------

    @Test
    fun `every StatusFilter maps to the menu item that selects it`() {
        for (status in StatusFilter.entries) {
            assertThat(statusFilterForMenuItemId(menuItemIdForStatusFilter(status))).isEqualTo(status)
        }
    }
}
