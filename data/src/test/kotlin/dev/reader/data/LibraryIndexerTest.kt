package dev.reader.data

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        assertThat(first.newlyUnreadable).isEqualTo(1)
        val row = dao.getByPath(broken.path)!!
        assertThat(row.unreadable).isTrue()
        assertThat(row.unreadableReason).isEqualTo("Malformed OPF")

        extractor.calls.clear()
        val second = indexer.sync()

        assertThat(extractor.calls).isEmpty()
        assertThat(second.newlyUnreadable).isEqualTo(0)
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
        assertThat(result.newlyUnreadable).isEqualTo(1)
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
        assertThat(result.newlyUnreadable).isEqualTo(1)
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
    fun `re-indexing a size-changed file resets its prior reading position`(): Unit = runBlocking {
        // The bytes are genuinely different content now: a stored spineIndex/charOffset is a
        // coordinate into the OLD book and must not be silently carried into the new one.
        val a = writeEpub("a.epub")
        val indexer = LibraryIndexer(dao, listOf(root), FakeExtractor())
        indexer.sync()
        dao.updatePosition(a.path, spineIndex = 4, charOffset = 250, progressFraction = 0.5f, lastOpenedAtMs = 1_000L)

        a.writeText("stub-changed")
        a.setLastModified(a.lastModified() + 60_000)
        indexer.sync()

        val row = dao.getByPath(a.path)!!
        assertThat(row.spineIndex).isEqualTo(0)
        assertThat(row.charOffset).isEqualTo(0)
    }

    @Test
    fun `re-indexing a touched file with unchanged bytes preserves its prior reading position`(): Unit = runBlocking {
        // Same size, bumped mtime only: a re-sync/touch of identical content, not a new book.
        val a = writeEpub("a.epub")
        val indexer = LibraryIndexer(dao, listOf(root), FakeExtractor())
        indexer.sync()
        dao.updatePosition(a.path, spineIndex = 4, charOffset = 250, progressFraction = 0.5f, lastOpenedAtMs = 1_000L)

        a.writeText("stub")
        a.setLastModified(a.lastModified() + 60_000)
        indexer.sync()

        val row = dao.getByPath(a.path)!!
        assertThat(row.spineIndex).isEqualTo(4)
        assertThat(row.charOffset).isEqualTo(250)
    }

    @Test
    fun `a size-changed file that now fails to crack does not keep the old title`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        val extractor = FakeExtractor()
        val indexer = LibraryIndexer(dao, listOf(root), extractor)
        indexer.sync()
        val oldRow = dao.getByPath(a.path)!!
        assertThat(oldRow.title).isEqualTo("a")
        assertThat(oldRow.unreadable).isFalse()

        // Content genuinely changed and the new bytes no longer crack.
        a.writeText("stub-but-now-corrupt-and-longer")
        a.setLastModified(a.lastModified() + 60_000)
        extractor.onPath(a.path, BookMetadataResult.Failure("Malformed OPF"))
        indexer.sync()

        val row = dao.getByPath(a.path)!!
        assertThat(row.unreadable).isTrue()
        assertThat(row.title).isEqualTo(a.name)
        assertThat(row.author).isNull()
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

    @Test
    fun `a new book gets addedAtMs from the injected clock, not the wall clock`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        val indexer = LibraryIndexer(dao, listOf(root), FakeExtractor(), clock = { 5_000L })

        indexer.sync()

        assertThat(dao.getByPath(a.path)!!.addedAtMs).isEqualTo(5_000L)
    }

    @Test
    fun `a size-changed file keeps its original addedAtMs, not the resync time`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        val firstSync = LibraryIndexer(dao, listOf(root), FakeExtractor(), clock = { 1_000L })
        firstSync.sync()

        a.writeText("stub-changed")
        a.setLastModified(a.lastModified() + 60_000)
        val secondSync = LibraryIndexer(dao, listOf(root), FakeExtractor(), clock = { 9_000L })
        secondSync.sync()

        assertThat(dao.getByPath(a.path)!!.addedAtMs).isEqualTo(1_000L)
    }

    @Test
    fun `a touched file with unchanged bytes keeps its original addedAtMs`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        val firstSync = LibraryIndexer(dao, listOf(root), FakeExtractor(), clock = { 1_000L })
        firstSync.sync()

        a.writeText("stub")
        a.setLastModified(a.lastModified() + 60_000)
        val secondSync = LibraryIndexer(dao, listOf(root), FakeExtractor(), clock = { 9_000L })
        secondSync.sync()

        assertThat(dao.getByPath(a.path)!!.addedAtMs).isEqualTo(1_000L)
    }

    @Test
    fun `a vanished book's cover file is deleted along with its row`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        val cover = tempFolder.newFile("a-cover.png")
        val extractor = FakeExtractor()
        extractor.onPath(a.path, BookMetadataResult.Success(title = "A", coverPath = cover.path))
        val indexer = LibraryIndexer(dao, listOf(root), extractor)
        indexer.sync()
        assertThat(cover.exists()).isTrue()

        assertThat(a.delete()).isTrue()
        indexer.sync()

        assertThat(cover.exists()).isFalse()
    }

    @Test
    fun `an unchanged rescan does not touch an existing cover file`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        val cover = tempFolder.newFile("cover.png")
        val extractor = FakeExtractor()
        extractor.onPath(a.path, BookMetadataResult.Success(title = "A", coverPath = cover.path))
        val indexer = LibraryIndexer(dao, listOf(root), extractor)
        indexer.sync()
        extractor.calls.clear()

        indexer.sync()

        assertThat(extractor.calls).isEmpty()
        assertThat(cover.exists()).isTrue()
        assertThat(dao.getByPath(a.path)!!.coverPath).isEqualTo(cover.path)
    }

    @Test
    fun `a replaced book's stale cover file is deleted once the new one is stored`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        val oldCover = tempFolder.newFile("old-cover.png")
        val extractor = FakeExtractor()
        extractor.onPath(a.path, BookMetadataResult.Success(title = "A", coverPath = oldCover.path))
        val indexer = LibraryIndexer(dao, listOf(root), extractor)
        indexer.sync()
        assertThat(oldCover.exists()).isTrue()

        // The bytes are genuinely different content now: sizeBytes changes.
        a.writeText("stub-but-longer-now")
        a.setLastModified(a.lastModified() + 60_000)
        val newCover = tempFolder.newFile("new-cover.png")
        extractor.onPath(a.path, BookMetadataResult.Success(title = "A", coverPath = newCover.path))
        indexer.sync()

        assertThat(oldCover.exists()).isFalse()
        assertThat(newCover.exists()).isTrue()
        assertThat(dao.getByPath(a.path)!!.coverPath).isEqualTo(newCover.path)
    }

    @Test
    fun `a size-changed file that now fails to crack deletes its stale cover file`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        val cover = tempFolder.newFile("cover.png")
        val extractor = FakeExtractor()
        extractor.onPath(a.path, BookMetadataResult.Success(title = "A", coverPath = cover.path))
        val indexer = LibraryIndexer(dao, listOf(root), extractor)
        indexer.sync()

        a.writeText("stub-but-now-corrupt-and-longer")
        a.setLastModified(a.lastModified() + 60_000)
        extractor.onPath(a.path, BookMetadataResult.Failure("Malformed OPF"))
        indexer.sync()

        assertThat(cover.exists()).isFalse()
        assertThat(dao.getByPath(a.path)!!.coverPath).isNull()
    }

    @Test
    fun `a touched file with unchanged bytes that fails to crack preserves its prior cover file`(): Unit = runBlocking {
        // Same size, bumped mtime only: same content as before, just now momentarily
        // unreadable (e.g. a transient I/O error) — the existing cover is still valid and
        // must not be deleted or orphaned.
        val a = writeEpub("a.epub")
        val cover = tempFolder.newFile("cover.png")
        val extractor = FakeExtractor()
        extractor.onPath(a.path, BookMetadataResult.Success(title = "A", coverPath = cover.path))
        val indexer = LibraryIndexer(dao, listOf(root), extractor)
        indexer.sync()

        a.writeText("stub")
        a.setLastModified(a.lastModified() + 60_000)
        extractor.onPath(a.path, BookMetadataResult.Failure("Transient error"))
        indexer.sync()

        assertThat(cover.exists()).isTrue()
        assertThat(dao.getByPath(a.path)!!.coverPath).isEqualTo(cover.path)
    }

    @Test
    fun `a position written mid-sync, after the stat but before the flush, survives the flush`(): Unit = runBlocking {
        // The Task 6 reader will call updatePosition on every return from a book. A sync that
        // round-trips the position columns through a whole-row snapshot read seconds earlier
        // would silently revert that write — this pins the fix: content-unchanged rows get a
        // partial metadata UPDATE that never touches spineIndex/charOffset/lastOpenedAtMs.
        val a = writeEpub("a.epub")
        LibraryIndexer(dao, listOf(root), FakeExtractor()).sync()
        dao.updatePosition(a.path, spineIndex = 1, charOffset = 10, progressFraction = 0.5f, lastOpenedAtMs = 500L)

        // Touch: same bytes, bumped mtime — the content-unchanged re-index path.
        a.writeText("stub")
        a.setLastModified(a.lastModified() + 60_000)

        // Deterministic interleaving without threads: by the time extract() runs for a path the
        // indexer has already read whatever prior row it is going to read, and its flush is
        // still ahead — so a position write issued from inside extract() lands exactly in the
        // window under test.
        val racingExtractor = MetadataExtractor { file ->
            runBlocking {
                dao.updatePosition(file.path, spineIndex = 7, charOffset = 99, progressFraction = 0.5f, lastOpenedAtMs = 2_000L)
            }
            BookMetadataResult.Success(title = "A")
        }
        LibraryIndexer(dao, listOf(root), racingExtractor).sync()

        val row = dao.getByPath(a.path)!!
        assertThat(row.spineIndex).isEqualTo(7)
        assertThat(row.charOffset).isEqualTo(99)
        assertThat(row.lastOpenedAtMs).isEqualTo(2_000L)
        // The metadata refresh itself still landed — only the position columns are off-limits.
        assertThat(row.title).isEqualTo("A")
    }

    @Test
    fun `a cleared stat makes the next sync re-crack an otherwise unchanged file`(): Unit = runBlocking {
        // The transiently-unreadable escape hatch: tapping an unreadable book clears its stored
        // stat (BookDao.clearStat), so the next sync re-cracks that one file even though its
        // on-disk (size, mtime) never changed.
        val broken = writeEpub("broken.epub")
        val extractor = FakeExtractor()
        extractor.onPath(broken.path, BookMetadataResult.Failure("half-synced"))
        val indexer = LibraryIndexer(dao, listOf(root), extractor)
        indexer.sync()
        assertThat(dao.getByPath(broken.path)!!.unreadable).isTrue()

        dao.clearStat(broken.path)
        extractor.onPath(broken.path, BookMetadataResult.Success(title = "Recovered"))
        extractor.calls.clear()
        indexer.sync()

        val row = dao.getByPath(broken.path)!!
        assertThat(extractor.calls).containsExactly(broken.path)
        assertThat(row.unreadable).isFalse()
        assertThat(row.unreadableReason).isNull()
        assertThat(row.title).isEqualTo("Recovered")
        // The real mtime is stored back, so the retry is exactly one retry: a further sync is
        // back to being a pure stat diff that opens nothing.
        assertThat(row.modifiedAtMs).isEqualTo(broken.lastModified())
        extractor.calls.clear()
        indexer.sync()
        assertThat(extractor.calls).isEmpty()
    }

    @Test
    fun `a sync cancelled mid-loop writes nothing and a full re-run heals`(): Unit = runBlocking {
        // The ensureActive() claim in the loop: cancellation aborts before the flush, so a
        // cancelled sync leaves either the old state or the new state — never a torn one — and
        // the next full run converges to the complete index.
        writeEpub("a.epub")
        writeEpub("b.epub")
        writeEpub("c.epub")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val blockingExtractor = MetadataExtractor { file ->
            // Block inside the second extract — a non-suspending section, like a real slow
            // crack — so the test can cancel mid-loop deterministically.
            if (calls.incrementAndGet() == 2) {
                entered.countDown()
                release.await()
            }
            BookMetadataResult.Success(title = file.nameWithoutExtension)
        }
        // Dispatchers.IO, not runBlocking's own event loop: entered.await() below BLOCKS this
        // thread, so a job launched on the default (inherited) dispatcher would never even start.
        val job = launch(Dispatchers.IO) {
            LibraryIndexer(dao, listOf(root), blockingExtractor).sync()
        }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        job.cancel()
        release.countDown()
        job.join()

        // The loop's ensureActive() fired on the next iteration, before the flush: nothing was
        // half-written.
        assertThat(dao.getAllStats()).isEmpty()

        val healed = LibraryIndexer(dao, listOf(root), FakeExtractor()).sync()
        assertThat(healed.added).isEqualTo(3)
        assertThat(dao.getAllStats()).hasSize(3)
    }

    @Test
    fun `narrowing the root leaves out-of-root rows and their positions untouched`(): Unit = runBlocking {
        // The data-loss guard: re-pointing the root must HIDE books outside it, never delete them.
        // A row whose path is under no current root is not a "vanished" file — the walk simply
        // wasn't asked to look there — so its reading position must survive intact.
        val rootA = tempFolder.newFolder("A")
        val rootB = tempFolder.newFolder("B")
        val a = File(rootA, "a.epub").apply { writeText("stub") }
        val b = File(rootB, "b.epub").apply { writeText("stub") }
        LibraryIndexer(dao, listOf(rootA, rootB), FakeExtractor()).sync()
        dao.updatePosition(b.path, spineIndex = 3, charOffset = 120, progressFraction = 0.5f, lastOpenedAtMs = 7_000L)
        val bBefore = dao.getByPath(b.path)!!

        // Root narrowed to just A. b.epub is under B, which is no longer walked.
        val result = LibraryIndexer(dao, listOf(rootA), FakeExtractor()).sync()

        // Nothing was removed — b is out of scope, not vanished.
        assertThat(result.removed).isEqualTo(0)
        val bAfter = dao.getByPath(b.path)!!
        assertThat(bAfter.spineIndex).isEqualTo(3)
        assertThat(bAfter.charOffset).isEqualTo(120)
        assertThat(bAfter.lastOpenedAtMs).isEqualTo(7_000L)
        assertThat(bAfter.addedAtMs).isEqualTo(bBefore.addedAtMs)
        // a is still present too.
        assertThat(dao.getByPath(a.path)).isNotNull()
    }

    @Test
    fun `restoring the root reinstates the survivors without re-cracking them`(): Unit = runBlocking {
        // After a narrow-then-widen round trip the survivor's (size, mtime) is unchanged, so it
        // falls straight into the untouched bucket — no extractor call, position still intact.
        val rootA = tempFolder.newFolder("A")
        val rootB = tempFolder.newFolder("B")
        File(rootA, "a.epub").writeText("stub")
        val b = File(rootB, "b.epub").apply { writeText("stub") }
        LibraryIndexer(dao, listOf(rootA, rootB), FakeExtractor()).sync()
        dao.updatePosition(b.path, spineIndex = 3, charOffset = 120, progressFraction = 0.5f, lastOpenedAtMs = 7_000L)

        // Narrow to A (b survives, hidden), then widen back to both.
        LibraryIndexer(dao, listOf(rootA), FakeExtractor()).sync()
        val extractor = FakeExtractor()
        val result = LibraryIndexer(dao, listOf(rootA, rootB), extractor).sync()

        // b reappears with no work: its stat matched, so it was never reopened.
        assertThat(extractor.calls).isEmpty()
        assertThat(result.added).isEqualTo(0)
        assertThat(result.updated).isEqualTo(0)
        val row = dao.getByPath(b.path)!!
        assertThat(row.spineIndex).isEqualTo(3)
        assertThat(row.charOffset).isEqualTo(120)
        assertThat(row.lastOpenedAtMs).isEqualTo(7_000L)
    }

    @Test
    fun `a file deleted from disk under the current root is still removed`(): Unit = runBlocking {
        // The scoping must not blunt the genuine case: a file gone from a root that IS walked is a
        // real deletion and its row must go, exactly as before.
        val rootA = tempFolder.newFolder("A")
        val rootB = tempFolder.newFolder("B")
        val a = File(rootA, "a.epub").apply { writeText("stub") }
        File(rootB, "b.epub").writeText("stub")
        LibraryIndexer(dao, listOf(rootA, rootB), FakeExtractor()).sync()

        assertThat(a.delete()).isTrue()
        val result = LibraryIndexer(dao, listOf(rootA, rootB), FakeExtractor()).sync()

        assertThat(result.removed).isEqualTo(1)
        assertThat(dao.getByPath(a.path)).isNull()
    }

    @Test
    fun `a sibling-prefix root does not treat an outside row as vanished`(): Unit = runBlocking {
        // Segment-correct ancestry: a "/Document" root must not claim "/Documents/x.epub". A naive
        // startsWith(root.path) would see the row as under the root, find its file missing from the
        // (empty) Document walk, and delete it — destroying a position on a folder-name near-miss.
        val storage = tempFolder.newFolder("storage")
        val document = File(storage, "Document").apply { mkdirs() }
        val documents = File(storage, "Documents").apply { mkdirs() }
        val x = File(documents, "x.epub").apply { writeText("stub") }
        LibraryIndexer(dao, listOf(documents), FakeExtractor()).sync()
        dao.updatePosition(x.path, spineIndex = 2, charOffset = 50, progressFraction = 0.5f, lastOpenedAtMs = 4_000L)

        // Re-point the root to the sibling directory whose name is a prefix of the real one.
        val result = LibraryIndexer(dao, listOf(document), FakeExtractor()).sync()

        assertThat(result.removed).isEqualTo(0)
        val row = dao.getByPath(x.path)!!
        assertThat(row.spineIndex).isEqualTo(2)
        assertThat(row.charOffset).isEqualTo(50)
    }

    @Test
    fun `multiple vanished books each have their own cover file deleted`(): Unit = runBlocking {
        val a = writeEpub("a.epub")
        val b = writeEpub("b.epub")
        val coverA = tempFolder.newFile("cover-a.png")
        val coverB = tempFolder.newFile("cover-b.png")
        val extractor = FakeExtractor()
        extractor.onPath(a.path, BookMetadataResult.Success(title = "A", coverPath = coverA.path))
        extractor.onPath(b.path, BookMetadataResult.Success(title = "B", coverPath = coverB.path))
        val indexer = LibraryIndexer(dao, listOf(root), extractor)
        indexer.sync()

        assertThat(a.delete()).isTrue()
        assertThat(b.delete()).isTrue()
        indexer.sync()

        assertThat(coverA.exists()).isFalse()
        assertThat(coverB.exists()).isFalse()
    }
}
