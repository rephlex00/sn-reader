package dev.reader.data

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BookDaoTest {

    private lateinit var db: LibraryDatabase
    private lateinit var dao: BookDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LibraryDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.bookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun book(
        path: String = "/Document/Books/a.epub",
        sizeBytes: Long = 1000L,
        modifiedAtMs: Long = 1_700_000_000_000L,
        title: String = "A Book",
        author: String? = "An Author",
        coverPath: String? = null,
        spineIndex: Int = 0,
        charOffset: Int = 0,
        unreadable: Boolean = false,
        unreadableReason: String? = null,
        addedAtMs: Long = 1_650_000_000_000L,
        lastOpenedAtMs: Long? = null,
    ) = BookEntity(
        path = path,
        sizeBytes = sizeBytes,
        modifiedAtMs = modifiedAtMs,
        title = title,
        author = author,
        coverPath = coverPath,
        spineIndex = spineIndex,
        charOffset = charOffset,
        unreadable = unreadable,
        unreadableReason = unreadableReason,
        addedAtMs = addedAtMs,
        lastOpenedAtMs = lastOpenedAtMs,
    )

    @Test
    fun `insert then getByPath returns the row`(): Unit = runBlocking {
        dao.upsertAll(listOf(book()))

        val found = dao.getByPath("/Document/Books/a.epub")

        assertThat(found).isNotNull()
        assertThat(found!!.title).isEqualTo("A Book")
        assertThat(found.sizeBytes).isEqualTo(1000L)
    }

    @Test
    fun `getByPath returns null for a path that was never indexed`(): Unit = runBlocking {
        assertThat(dao.getByPath("/Document/Books/missing.epub")).isNull()
    }

    @Test
    fun `observeAll emits every upserted row`(): Unit = runBlocking {
        dao.upsertAll(listOf(book(path = "/a.epub"), book(path = "/b.epub", title = "B")))

        val rows = dao.observeAll().first()

        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.path }).containsExactly("/a.epub", "/b.epub")
    }

    @Test
    fun `re-upserting the same path updates the row instead of duplicating it`(): Unit = runBlocking {
        dao.upsertAll(listOf(book(path = "/a.epub", sizeBytes = 100L, title = "Old Title")))
        dao.upsertAll(listOf(book(path = "/a.epub", sizeBytes = 200L, title = "New Title")))

        val rows = dao.observeAll().first()

        assertThat(rows).hasSize(1)
        assertThat(rows.single().title).isEqualTo("New Title")
        assertThat(rows.single().sizeBytes).isEqualTo(200L)
    }

    @Test
    fun `deleteByPaths removes exactly the named rows`(): Unit = runBlocking {
        dao.upsertAll(listOf(book(path = "/a.epub"), book(path = "/b.epub"), book(path = "/c.epub")))

        dao.deleteByPaths(listOf("/a.epub", "/c.epub"))

        val rows = dao.observeAll().first()
        assertThat(rows.map { it.path }).containsExactly("/b.epub")
    }

    @Test
    fun `deleteByPaths on an empty list deletes nothing`(): Unit = runBlocking {
        dao.upsertAll(listOf(book(path = "/a.epub")))

        dao.deleteByPaths(emptyList())

        assertThat(dao.observeAll().first()).hasSize(1)
    }

    @Test
    fun `getAllStats projects path size and mtime without the rest of the row`(): Unit = runBlocking {
        dao.upsertAll(
            listOf(
                book(path = "/a.epub", sizeBytes = 111L, modifiedAtMs = 222L),
                book(path = "/b.epub", sizeBytes = 333L, modifiedAtMs = 444L),
            ),
        )

        val stats = dao.getAllStats()

        assertThat(stats).containsExactly(
            BookStat(
                path = "/a.epub",
                sizeBytes = 111L,
                modifiedAtMs = 222L,
                addedAtMs = 1_650_000_000_000L,
                lastOpenedAtMs = null,
            ),
            BookStat(
                path = "/b.epub",
                sizeBytes = 333L,
                modifiedAtMs = 444L,
                addedAtMs = 1_650_000_000_000L,
                lastOpenedAtMs = null,
            ),
        )
    }

    @Test
    fun `updatePosition round-trips spineIndex, charOffset, and lastOpenedAtMs without touching other columns`(): Unit =
        runBlocking {
            dao.upsertAll(listOf(book(path = "/a.epub", title = "Keep Me", spineIndex = 0, charOffset = 0)))

            dao.updatePosition(path = "/a.epub", spineIndex = 3, charOffset = 4521, lastOpenedAtMs = 9_000L)

            val found = dao.getByPath("/a.epub")!!
            assertThat(found.spineIndex).isEqualTo(3)
            assertThat(found.charOffset).isEqualTo(4521)
            assertThat(found.lastOpenedAtMs).isEqualTo(9_000L)
            assertThat(found.title).isEqualTo("Keep Me")
        }

    @Test
    fun `updatePosition on an unknown path is a no-op, not a crash`(): Unit = runBlocking {
        dao.updatePosition(path = "/nowhere.epub", spineIndex = 1, charOffset = 1, lastOpenedAtMs = 1L)

        assertThat(dao.observeAll().first()).isEmpty()
    }

    @Test
    fun `markUnreadable sets the flag and reason without touching position`(): Unit = runBlocking {
        dao.upsertAll(listOf(book(path = "/broken.epub", spineIndex = 2, charOffset = 99)))

        dao.markUnreadable(path = "/broken.epub", reason = "Malformed OPF")

        val found = dao.getByPath("/broken.epub")!!
        assertThat(found.unreadable).isTrue()
        assertThat(found.unreadableReason).isEqualTo("Malformed OPF")
        assertThat(found.spineIndex).isEqualTo(2)
        assertThat(found.charOffset).isEqualTo(99)
    }

    @Test
    fun `a book marked unreadable can still be read back through observeAll`(): Unit = runBlocking {
        dao.upsertAll(listOf(book(path = "/broken.epub")))
        dao.markUnreadable(path = "/broken.epub", reason = "DRM protected")

        val rows = dao.observeAll().first()

        assertThat(rows).hasSize(1)
        assertThat(rows.single().unreadable).isTrue()
        assertThat(rows.single().unreadableReason).isEqualTo("DRM protected")
    }

    @Test
    fun `observeAllSorted TITLE is case-insensitive ascending`(): Unit = runBlocking {
        dao.upsertAll(
            listOf(
                book(path = "/b.epub", title = "the Hobbit"),
                book(path = "/a.epub", title = "Anna Karenina"),
                book(path = "/c.epub", title = "The Great Gatsby"),
            ),
        )

        val rows = dao.observeAllSorted(SortOrder.TITLE).first()

        assertThat(rows.map { it.title })
            .containsExactly("Anna Karenina", "The Great Gatsby", "the Hobbit")
            .inOrder()
    }

    @Test
    fun `observeAllSorted AUTHOR is case-insensitive and sorts null authors last`(): Unit = runBlocking {
        dao.upsertAll(
            listOf(
                book(path = "/a.epub", author = "zorro"),
                book(path = "/b.epub", author = null),
                book(path = "/c.epub", author = "Anne"),
            ),
        )

        val rows = dao.observeAllSorted(SortOrder.AUTHOR).first()

        assertThat(rows.map { it.path }).containsExactly("/c.epub", "/a.epub", "/b.epub").inOrder()
    }

    @Test
    fun `observeAllSorted RECENTLY_ADDED orders newest addedAtMs first`(): Unit = runBlocking {
        dao.upsertAll(
            listOf(
                book(path = "/old.epub", addedAtMs = 1000L),
                book(path = "/new.epub", addedAtMs = 3000L),
                book(path = "/mid.epub", addedAtMs = 2000L),
            ),
        )

        val rows = dao.observeAllSorted(SortOrder.RECENTLY_ADDED).first()

        assertThat(rows.map { it.path }).containsExactly("/new.epub", "/mid.epub", "/old.epub").inOrder()
    }

    @Test
    fun `observeAllSorted RECENTLY_OPENED places never-opened books last`(): Unit = runBlocking {
        dao.upsertAll(
            listOf(
                book(path = "/never.epub", lastOpenedAtMs = null),
                book(path = "/old.epub", lastOpenedAtMs = 1000L),
                book(path = "/recent.epub", lastOpenedAtMs = 5000L),
            ),
        )

        val rows = dao.observeAllSorted(SortOrder.RECENTLY_OPENED).first()

        assertThat(rows.map { it.path })
            .containsExactly("/recent.epub", "/old.epub", "/never.epub")
            .inOrder()
    }
}
