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
    fun `skips an empty chapter when advancing`() {
        // An empty chapter has no pages; landing on it would strand the reader.
        val target = nav.next(ReadingState(0, 0), pagesInCurrentChapter = 1)
        assertThat(target).isEqualTo(NavTarget.Page(1, 0))
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
    fun `reports the start of the book`() {
        assertThat(nav.previous(ReadingState(0, 0))).isEqualTo(NavTarget.AtStart)
    }

    @Test
    fun `rejects a spine size of zero`() {
        val e = runCatching { PageNavigator(spineSize = 0) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
    }
}
