package dev.reader.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaginatorTest {

    private val paginator = Paginator()

    @Test
    fun `fills each page greedily up to the height budget`() {
        // 10 lines x 20px, 100px page => exactly 5 lines per page.
        val pages = paginator.paginate(FakeMeasuredChapter(lineCount = 10), pageHeightPx = 100)

        assertThat(pages).hasSize(2)
        assertThat(pages[0].startLine).isEqualTo(0)
        assertThat(pages[0].endLine).isEqualTo(4)
        assertThat(pages[1].startLine).isEqualTo(5)
        assertThat(pages[1].endLine).isEqualTo(9)
    }

    @Test
    fun `page char offsets follow line offsets`() {
        val pages = paginator.paginate(FakeMeasuredChapter(lineCount = 10), pageHeightPx = 100)

        assertThat(pages[0].startOffset).isEqualTo(0)
        assertThat(pages[0].endOffset).isEqualTo(5 * 40)
        assertThat(pages[1].startOffset).isEqualTo(5 * 40)
        assertThat(pages[1].endOffset).isEqualTo(10 * 40)
    }

    @Test
    fun `page top is the top of its first line`() {
        val pages = paginator.paginate(FakeMeasuredChapter(lineCount = 10), pageHeightPx = 100)

        assertThat(pages[0].topPx).isEqualTo(0)
        assertThat(pages[1].topPx).isEqualTo(100)
    }

    @Test
    fun `a trailing partial page is still emitted`() {
        // 12 lines x 20px, 100px page => 5 + 5 + 2.
        val pages = paginator.paginate(FakeMeasuredChapter(lineCount = 12), pageHeightPx = 100)

        assertThat(pages).hasSize(3)
        assertThat(pages[2].startLine).isEqualTo(10)
        assertThat(pages[2].endLine).isEqualTo(11)
    }

    @Test
    fun `hard page break forces a new page`() {
        val chapter = FakeMeasuredChapter(lineCount = 10, hardBreaks = setOf(3))
        val pages = paginator.paginate(chapter, pageHeightPx = 10_000)

        assertThat(pages).hasSize(2)
        assertThat(pages[0].endLine).isEqualTo(2)
        assertThat(pages[1].startLine).isEqualTo(3)
    }

    @Test
    fun `a line taller than the page gets its own page and does not loop forever`() {
        val chapter = FakeMeasuredChapter(lineCount = 3, lineHeightPx = 200)
        val pages = paginator.paginate(chapter, pageHeightPx = 100)

        assertThat(pages).hasSize(3)
        assertThat(pages.map { it.startLine }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `pages are indexed consecutively from zero`() {
        val pages = paginator.paginate(FakeMeasuredChapter(lineCount = 12), pageHeightPx = 100)
        assertThat(pages.map { it.index }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `empty chapter paginates to no pages`() {
        assertThat(paginator.paginate(FakeMeasuredChapter(lineCount = 0), pageHeightPx = 100)).isEmpty()
    }

    @Test
    fun `rejects a non-positive page height`() {
        val e = runCatching {
            paginator.paginate(FakeMeasuredChapter(lineCount = 5), pageHeightPx = 0)
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `pageIndexFor finds the page holding an offset`() {
        val pages = paginator.paginate(FakeMeasuredChapter(lineCount = 10), pageHeightPx = 100)

        assertThat(pageIndexFor(pages, 0)).isEqualTo(0)
        assertThat(pageIndexFor(pages, 199)).isEqualTo(0)
        assertThat(pageIndexFor(pages, 200)).isEqualTo(1)
        assertThat(pageIndexFor(pages, 399)).isEqualTo(1)
    }

    @Test
    fun `pageIndexFor clamps an offset past the end to the last page`() {
        val pages = paginator.paginate(FakeMeasuredChapter(lineCount = 10), pageHeightPx = 100)
        assertThat(pageIndexFor(pages, 99_999)).isEqualTo(1)
    }
}
