package dev.reader.ui

import com.google.common.truth.Truth.assertThat
import dev.reader.data.BookEntity
import org.junit.Test

class LibraryFindTest {
    private fun book(
        title: String, author: String? = null, unreadable: Boolean = false,
        progress: Float? = null, opened: Long? = null, path: String = "/$title.epub",
    ) = BookEntity(
        path = path, sizeBytes = 1, modifiedAtMs = 1, title = title, author = author,
        coverPath = null, spineIndex = 0, charOffset = 0, unreadable = unreadable,
        unreadableReason = if (unreadable) "x" else null, addedAtMs = 1, lastOpenedAtMs = opened,
        progressFraction = progress,
    )

    @Test fun `blank query all status returns everything in input order`() {
        val a = book("Alpha"); val b = book("Beta")
        assertThat(findBooks(listOf(a, b), "", StatusFilter.ALL)).containsExactly(a, b).inOrder()
    }

    @Test fun `query matches title case-insensitively`() {
        val a = book("The Martian"); val b = book("Project Hail Mary")
        assertThat(findBooks(listOf(a, b), "martian", StatusFilter.ALL)).containsExactly(a)
    }

    @Test fun `query matches author`() {
        val a = book("X", author = "Andy Weir"); val b = book("Y", author = "Cixin Liu")
        assertThat(findBooks(listOf(a, b), "weir", StatusFilter.ALL)).containsExactly(a)
    }

    @Test fun `no match yields empty`() {
        assertThat(findBooks(listOf(book("Alpha")), "zzz", StatusFilter.ALL)).isEmpty()
    }

    @Test fun `status filter selects and excludes unreadable`() {
        val notStarted = book("N")
        val inProgress = book("I", opened = 5, progress = 0.3f)
        val finished = book("F", opened = 6, progress = 0.99f)
        val broken = book("B", unreadable = true)
        val all = listOf(notStarted, inProgress, finished, broken)
        assertThat(findBooks(all, "", StatusFilter.NOT_STARTED)).containsExactly(notStarted)
        assertThat(findBooks(all, "", StatusFilter.IN_PROGRESS)).containsExactly(inProgress)
        assertThat(findBooks(all, "", StatusFilter.FINISHED)).containsExactly(finished)
        assertThat(findBooks(all, "", StatusFilter.ALL)).containsExactly(notStarted, inProgress, finished, broken).inOrder()
    }

    @Test fun `query and status combine`() {
        val a = book("Rogue Protocol", author = "Martha Wells", opened = 5, progress = 0.2f)
        val b = book("Exit Strategy", author = "Martha Wells")
        assertThat(findBooks(listOf(a, b), "wells", StatusFilter.IN_PROGRESS)).containsExactly(a)
    }

    @Test fun `isFilterActive is true for a query or a non-ALL status`() {
        assertThat(isFilterActive("", StatusFilter.ALL)).isFalse()
        assertThat(isFilterActive("x", StatusFilter.ALL)).isTrue()
        assertThat(isFilterActive("", StatusFilter.FINISHED)).isTrue()
        assertThat(isFilterActive("   ", StatusFilter.ALL)).isFalse() // blank == inactive
    }
}
