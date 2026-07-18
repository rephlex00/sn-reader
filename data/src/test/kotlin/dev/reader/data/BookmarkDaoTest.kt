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
class BookmarkDaoTest {

    private lateinit var db: LibraryDatabase
    private lateinit var books: BookDao
    private lateinit var bookmarks: BookmarkDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LibraryDatabase::class.java,
        ).allowMainThreadQueries().build()
        books = db.bookDao()
        bookmarks = db.bookmarkDao()
    }

    @After
    fun tearDown() = db.close()

    private fun book(path: String) = BookEntity(
        path = path, sizeBytes = 1, modifiedAtMs = 1, title = "T", author = null, coverPath = null,
        spineIndex = 0, charOffset = 0, unreadable = false, unreadableReason = null,
        addedAtMs = 1, lastOpenedAtMs = null,
    )

    private fun mark(path: String, spine: Int, off: Int) = BookmarkEntity(
        bookPath = path, spineIndex = spine, charOffset = off, progressFraction = 0.5f, createdAtMs = 1,
    )

    @Test
    fun `bookmarks come back in reading order regardless of insert order`() = runBlocking {
        books.upsertAll(listOf(book("/a.epub")))
        bookmarks.insert(mark("/a.epub", spine = 5, off = 200))
        bookmarks.insert(mark("/a.epub", spine = 2, off = 900))
        bookmarks.insert(mark("/a.epub", spine = 2, off = 100))

        val ordered = bookmarks.bookmarksFor("/a.epub").map { it.spineIndex to it.charOffset }
        assertThat(ordered).containsExactly(2 to 100, 2 to 900, 5 to 200).inOrder()
    }

    @Test
    fun `bookmarksFor is scoped to one book`() = runBlocking {
        books.upsertAll(listOf(book("/a.epub"), book("/b.epub")))
        bookmarks.insert(mark("/a.epub", 1, 0))
        bookmarks.insert(mark("/b.epub", 1, 0))
        assertThat(bookmarks.bookmarksFor("/a.epub")).hasSize(1)
    }

    @Test
    fun `deleteById removes exactly one bookmark`(): Unit = runBlocking {
        books.upsertAll(listOf(book("/a.epub")))
        val id = bookmarks.insert(mark("/a.epub", 1, 0))
        bookmarks.insert(mark("/a.epub", 2, 0))
        bookmarks.deleteById(id)
        assertThat(bookmarks.bookmarksFor("/a.epub").map { it.spineIndex }).containsExactly(2)
    }

    @Test
    fun `deleting a book cascades to its bookmarks`() = runBlocking {
        books.upsertAll(listOf(book("/a.epub")))
        bookmarks.insert(mark("/a.epub", 1, 0))
        books.deleteByPaths(listOf("/a.epub"))
        assertThat(bookmarks.bookmarksFor("/a.epub")).isEmpty()
    }
}
