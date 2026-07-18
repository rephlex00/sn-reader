package dev.reader.data

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HighlightDaoTest {

    private lateinit var db: LibraryDatabase
    private lateinit var books: BookDao
    private lateinit var highlights: HighlightDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LibraryDatabase::class.java,
        ).allowMainThreadQueries().build()
        books = db.bookDao()
        highlights = db.highlightDao()
    }

    @After
    fun tearDown() = db.close()

    private fun book(path: String) = BookEntity(
        path = path, sizeBytes = 1, modifiedAtMs = 1, title = "T", author = null, coverPath = null,
        spineIndex = 0, charOffset = 0, unreadable = false, unreadableReason = null,
        addedAtMs = 1, lastOpenedAtMs = null,
    )

    private fun hl(path: String, spine: Int, start: Int, end: Int) = HighlightEntity(
        bookPath = path, spineIndex = spine, startOffset = start, endOffset = end,
        text = "t", progressFraction = 0.5f, createdAtMs = 1,
    )

    @Test
    fun `highlightsForBook comes back in reading order`() = runBlocking {
        books.upsertAll(listOf(book("/a.epub")))
        highlights.insert(hl("/a.epub", spine = 5, start = 200, end = 210))
        highlights.insert(hl("/a.epub", spine = 2, start = 900, end = 950))
        highlights.insert(hl("/a.epub", spine = 2, start = 100, end = 120))

        val ordered = highlights.highlightsForBook("/a.epub").map { it.spineIndex to it.startOffset }
        assertThat(ordered).containsExactly(2 to 100, 2 to 900, 5 to 200).inOrder()
    }

    @Test
    fun `highlightsForChapter filters to one chapter`() = runBlocking {
        books.upsertAll(listOf(book("/a.epub")))
        highlights.insert(hl("/a.epub", spine = 2, start = 10, end = 20))
        highlights.insert(hl("/a.epub", spine = 3, start = 10, end = 20))
        assertThat(highlights.highlightsForChapter("/a.epub", 2)).hasSize(1)
    }

    @Test
    fun `deleteById removes exactly one highlight`(): Unit = runBlocking {
        books.upsertAll(listOf(book("/a.epub")))
        val id = highlights.insert(hl("/a.epub", 1, 0, 5))
        highlights.insert(hl("/a.epub", 2, 0, 5))
        highlights.deleteById(id)
        assertThat(highlights.highlightsForBook("/a.epub").map { it.spineIndex }).containsExactly(2)
    }

    @Test
    fun `deleting a book cascades to its highlights`() = runBlocking {
        books.upsertAll(listOf(book("/a.epub")))
        highlights.insert(hl("/a.epub", 1, 0, 5))
        books.deleteByPaths(listOf("/a.epub"))
        assertThat(highlights.highlightsForBook("/a.epub")).isEmpty()
    }

    @Test
    fun `replaceWithMerged deletes the subsumed rows and inserts the merged one`() = runBlocking {
        books.upsertAll(listOf(book("/a.epub")))
        val id1 = highlights.insert(hl("/a.epub", spine = 1, start = 0, end = 10))
        val id2 = highlights.insert(hl("/a.epub", spine = 1, start = 8, end = 20))

        highlights.replaceWithMerged(listOf(id1, id2), hl("/a.epub", spine = 1, start = 0, end = 20))

        val remaining = highlights.highlightsForBook("/a.epub")
        assertThat(remaining).hasSize(1)
        assertThat(remaining[0].startOffset).isEqualTo(0)
        assertThat(remaining[0].endOffset).isEqualTo(20)
    }
}
