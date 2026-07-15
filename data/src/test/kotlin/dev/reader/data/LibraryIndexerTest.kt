package dev.reader.data

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LibraryIndexerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: LibraryDatabase
    private lateinit var dao: BookDao
    private lateinit var root: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LibraryDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.bookDao()
        root = tempFolder.newFolder("Books")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun writeEpub(name: String, contents: String = "stub"): File {
        val file = File(root, name)
        file.writeText(contents)
        return file
    }

    /** Records every call so tests can assert exactly which files were (re)opened. */
    private class FakeExtractor(
        private val results: MutableMap<String, BookMetadataResult> = mutableMapOf(),
    ) : MetadataExtractor {
        val calls = mutableListOf<String>()

        fun onPath(path: String, result: BookMetadataResult) {
            results[path] = result
        }

        override fun extract(file: File): BookMetadataResult {
            calls += file.path
            return results[file.path] ?: BookMetadataResult.Success(title = file.nameWithoutExtension)
        }
    }

    @Test
    fun `first scan indexes every epub under the root`(): Unit = runBlocking {
        writeEpub("a.epub")
        writeEpub("b.epub")
        val extractor = FakeExtractor()
        val indexer = LibraryIndexer(dao, listOf(root), extractor)

        val result = indexer.sync()

        assertThat(result.added).isEqualTo(2)
        assertThat(result.updated).isEqualTo(0)
        assertThat(result.removed).isEqualTo(0)
        assertThat(extractor.calls).hasSize(2)
        val rows = dao.getAllStats()
        assertThat(rows.map { it.path }).containsExactly(
            File(root, "a.epub").path,
            File(root, "b.epub").path,
        )
    }

    @Test
    fun `a second scan with no changes opens zero files`(): Unit = runBlocking {
        val file = writeEpub("a.epub")
        val extractor = FakeExtractor()
        val indexer = LibraryIndexer(dao, listOf(root), extractor)
        indexer.sync()
        extractor.calls.clear()

        val result = indexer.sync()

        assertThat(extractor.calls).isEmpty()
        assertThat(result.added).isEqualTo(0)
        assertThat(result.updated).isEqualTo(0)
        assertThat(result.removed).isEqualTo(0)
        // sanity: the file really is still there and unchanged
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `a changed mtime reopens exactly that one file`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        val b = writeEpub("b.epub")
        val extractor = FakeExtractor()
        val indexer = LibraryIndexer(dao, listOf(root), extractor)
        indexer.sync()
        extractor.calls.clear()

        // Bump a's mtime (and change its size, to be unambiguous) without touching b.
        a.writeText("stub-but-longer-now")
        a.setLastModified(a.lastModified() + 60_000)

        val result = indexer.sync()

        assertThat(extractor.calls).containsExactly(a.path)
        assertThat(result.added).isEqualTo(0)
        assertThat(result.updated).isEqualTo(1)
        assertThat(b.path !in extractor.calls).isTrue()
    }

    @Test
    fun `a vanished file is deleted from the index`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        writeEpub("b.epub")
        val indexer = LibraryIndexer(dao, listOf(root), FakeExtractor())
        indexer.sync()

        assertThat(a.delete()).isTrue()
        val result = indexer.sync()

        assertThat(result.removed).isEqualTo(1)
        val rows = dao.getAllStats()
        assertThat(rows.map { it.path }).containsExactly(File(root, "b.epub").path)
    }

    @Test
    fun `an unreadable file is not reopened on an unchanged rescan`(): Unit = runBlocking {
        val broken = writeEpub("broken.epub")
        val extractor = FakeExtractor()
        extractor.onPath(broken.path, BookMetadataResult.Failure("Malformed OPF"))
        val indexer = LibraryIndexer(dao, listOf(root), extractor)

        val first = indexer.sync()
        assertThat(first.unreadable).isEqualTo(1)
        val row = dao.getByPath(broken.path)!!
        assertThat(row.unreadable).isTrue()
        assertThat(row.unreadableReason).isEqualTo("Malformed OPF")

        extractor.calls.clear()
        val second = indexer.sync()

        assertThat(extractor.calls).isEmpty()
        assertThat(second.unreadable).isEqualTo(0)
        assertThat(second.added).isEqualTo(0)
        assertThat(second.updated).isEqualTo(0)
    }

    @Test
    fun `one failing file does not abort the others`(): Unit = runBlocking {
        val good1 = writeEpub("good1.epub")
        val bad = writeEpub("bad.epub")
        val good2 = writeEpub("good2.epub")
        val extractor = FakeExtractor()
        extractor.onPath(bad.path, BookMetadataResult.Failure("Not a zip"))
        val indexer = LibraryIndexer(dao, listOf(root), extractor)

        val result = indexer.sync()

        assertThat(result.added).isEqualTo(3)
        assertThat(result.unreadable).isEqualTo(1)
        assertThat(dao.getByPath(good1.path)?.unreadable).isFalse()
        assertThat(dao.getByPath(good2.path)?.unreadable).isFalse()
        assertThat(dao.getByPath(bad.path)?.unreadable).isTrue()
    }

    @Test
    fun `an extractor that throws is treated as a failure, not a crash`(): Unit = runBlocking {
        val boom = writeEpub("boom.epub")
        val fine = writeEpub("fine.epub")
        val extractor = object : MetadataExtractor {
            override fun extract(file: File): BookMetadataResult {
                if (file.path == boom.path) throw SecurityException("denied")
                return BookMetadataResult.Success(title = file.nameWithoutExtension)
            }
        }
        val indexer = LibraryIndexer(dao, listOf(root), extractor)

        val result = indexer.sync()

        assertThat(result.added).isEqualTo(2)
        assertThat(result.unreadable).isEqualTo(1)
        val row = dao.getByPath(boom.path)!!
        assertThat(row.unreadable).isTrue()
        assertThat(row.unreadableReason).isEqualTo("denied")
        assertThat(dao.getByPath(fine.path)?.unreadable).isFalse()
    }

    @Test
    fun `a newly discovered unreadable file is indexed via upsert, not markUnreadable`(): Unit = runBlocking {
        // Regression guard for the Task 2 report correction: markUnreadable is UPDATE-only and
        // cannot create a row for a path that has never been seen before.
        val broken = writeEpub("broken.epub")
        val extractor = FakeExtractor()
        extractor.onPath(broken.path, BookMetadataResult.Failure("DRM protected"))
        val indexer = LibraryIndexer(dao, listOf(root), extractor)

        indexer.sync()

        val row = dao.getByPath(broken.path)
        assertThat(row).isNotNull()
        assertThat(row!!.unreadable).isTrue()
        assertThat(row.unreadableReason).isEqualTo("DRM protected")
    }

    @Test
    fun `re-indexing a changed file preserves its prior reading position`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        val indexer = LibraryIndexer(dao, listOf(root), FakeExtractor())
        indexer.sync()
        dao.updatePosition(a.path, spineIndex = 4, charOffset = 250)

        a.writeText("stub-changed")
        a.setLastModified(a.lastModified() + 60_000)
        indexer.sync()

        val row = dao.getByPath(a.path)!!
        assertThat(row.spineIndex).isEqualTo(4)
        assertThat(row.charOffset).isEqualTo(250)
    }

    @Test
    fun `multiple roots are all scanned`(): Unit = runBlocking {
        val root2 = tempFolder.newFolder("Books2")
        writeEpub("a.epub")
        File(root2, "c.epub").writeText("stub")
        val indexer = LibraryIndexer(dao, listOf(root, root2), FakeExtractor())

        val result = indexer.sync()

        assertThat(result.added).isEqualTo(2)
    }
}
