package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageNavigatorTest {

    private val nav = PageNavigator(spineSize = 3)

    @Test
    fun `advances within the current chapter`() {
        val target = nav.next(ReadingState(0, 0), pagesInCurrentChapter = 5)
        assertThat(target).isEqualTo(NavTarget.Page(0, 1))
    }

    @Test
    fun `rolls over to the next chapter's first page`() {
        val target = nav.next(ReadingState(0, 4), pagesInCurrentChapter = 5)
        assertThat(target).isEqualTo(NavTarget.Page(1, 0))
    }

    @Test
    fun `reports the end of the book`() {
        val target = nav.next(ReadingState(2, 4), pagesInCurrentChapter = 5)
        assertThat(target).isEqualTo(NavTarget.AtEnd)
    }

    @Test
    fun `rolls over a one-page chapter to the next chapter's first page`() {
        // Arithmetically identical to the "rolls over" case above (pageIndex + 1 == pagesInCurrentChapter);
        // this is NOT a test of an empty chapter (pagesInCurrentChapter == 0). See the next test for that.
        val target = nav.next(ReadingState(0, 0), pagesInCurrentChapter = 1)
        assertThat(target).isEqualTo(NavTarget.Page(1, 0))
    }

    @Test
    fun `skips an empty chapter when advancing`() {
        // A genuinely empty chapter has pagesInCurrentChapter == 0 (Task 8's EpubDocument.chapter()
        // returns pages = emptyList() for a missing or empty chapter file). next() must still land on
        // the following chapter's first page rather than getting stuck inside the empty one.
        val target = nav.next(ReadingState(1, 0), pagesInCurrentChapter = 0)
        assertThat(target).isEqualTo(NavTarget.Page(2, 0))
    }

    @Test
    fun `goes back within the current chapter`() {
        assertThat(nav.previous(ReadingState(1, 3))).isEqualTo(NavTarget.Page(1, 2))
    }

    @Test
    fun `rolls back to the last page of the previous chapter`() {
        assertThat(nav.previous(ReadingState(1, 0))).isEqualTo(NavTarget.LastPageOf(0))
    }

    @Test
    fun `rolls back to LastPageOf regardless of whether that chapter has any pages`() {
        // previous() cannot know chapter 1's page count (it may be empty per Task 8), so it always
        // returns LastPageOf blindly. The caller must resolve it and skip forward-in-reverse if the
        // resolved chapter turns out to have zero pages, rather than computing pageCount - 1 directly.
        assertThat(nav.previous(ReadingState(2, 0))).isEqualTo(NavTarget.LastPageOf(1))
    }

    @Test
    fun `reports the start of the book`() {
        assertThat(nav.previous(ReadingState(0, 0))).isEqualTo(NavTarget.AtStart)
    }

    @Test
    fun `rejects a spine size of zero`() {
        val e = runCatching { PageNavigator(spineSize = 0) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("0")
    }

    @Test
    fun `rejects a negative spine size`() {
        val e = runCatching { PageNavigator(spineSize = -2) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("-2")
    }

    @Test
    fun `rejects a negative spine index in ReadingState`() {
        val e = runCatching { ReadingState(-1, 0) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("-1")
    }

    @Test
    fun `rejects a negative page index in ReadingState`() {
        val e = runCatching { ReadingState(0, -5) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("-5")
    }
}
