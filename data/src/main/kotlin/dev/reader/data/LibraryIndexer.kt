package dev.reader.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What [MetadataExtractor.extract] found (or didn't) for one candidate file.
 *
 * [Success] deliberately has no cover reference yet — Task 4 (`EpubCoverExtractor`, in
 * `:formats`/`:app`) will add a `coverPath: String? = null` here once it can produce an actual
 * decoded, downsampled grayscale thumbnail file under `filesDir`; that addition is
 * source-compatible with every existing caller. `:data` only ever stores a resolved file path, in
 * [BookEntity.coverPath] — never an archive-internal reference — so there is nothing for this type
 * to hold in the meantime.
 */
sealed interface BookMetadataResult {
    data class Success(
        val title: String,
        val author: String? = null,
    ) : BookMetadataResult

    data class Failure(val reason: String) : BookMetadataResult
}

/**
 * The seam that keeps `:data` free of `:engine`/`:formats`. `:app` supplies the EPUB-backed
 * implementation (wrapping `EpubDocument.open`); tests supply a fake, so the incremental-diff
 * algorithm in [LibraryIndexer] is fully JVM-testable without Robolectric needing to touch any
 * real EPUB parsing.
 *
 * Implementations must not throw for a malformed file — return [BookMetadataResult.Failure]
 * instead. [LibraryIndexer] treats a thrown [SecurityException] or any other [Exception] as
 * equivalent to a [BookMetadataResult.Failure] (mark and continue), as a defensive second layer,
 * but a well-behaved extractor should not rely on that.
 */
fun interface MetadataExtractor {
    fun extract(file: File): BookMetadataResult
}

/**
 * Tallies from one [LibraryIndexer.sync] call.
 *
 * [newlyUnreadable] counts only the files that *this* sync attempted to open and found broken —
 * it is not the library's total broken-book count. A book marked unreadable on a prior sync whose
 * `(size, mtime)` is unchanged is never reopened (see class doc), so it does not contribute here
 * even though it is still sitting in the index as unreadable.
 */
data class IndexResult(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val newlyUnreadable: Int,
)

/**
 * Incremental library sync: walks [roots] collecting `(path, size, mtime)` only — no file is
 * opened — diffs that against [BookDao.getAllStats], and opens (via [extractor]) only the files
 * that are new or whose `(size, mtime)` changed. Vanished paths are deleted.
 *
 * A file already indexed as `unreadable` whose `(size, mtime)` is unchanged is never reopened:
 * that falls straight out of the diff (its stat still matches, so it lands in the untouched
 * bucket) — no separate "unreadable" query is needed.
 *
 * Call [sync] once, on library entry. Nothing here schedules itself; there is no service, worker,
 * or file observer. The whole body runs on [Dispatchers.IO] regardless of the caller's context, so
 * the blocking directory walk and file reads never land on the caller's thread.
 *
 * [clock] is injected (rather than reading [System.currentTimeMillis] directly) purely so tests
 * can assert on the `addedAtMs` a newly-discovered book receives.
 */
class LibraryIndexer(
    private val dao: BookDao,
    private val roots: List<File>,
    private val extractor: MetadataExtractor,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun sync(): IndexResult = withContext(Dispatchers.IO) {
        val onDisk = walk()
        val known = dao.getAllStats().associateBy { it.path }

        val vanished = known.keys - onDisk.keys
        if (vanished.isNotEmpty()) {
            // TODO(Task 4): this drops the row but leaks the cover file on disk. The brief's step 4
            // is "delete vanished rows and their cover files" — once Task 4 populates
            // BookEntity.coverPath, deleting a row here without also deleting that file is a
            // storage leak. It's a no-op today only because coverPath is always null.
            dao.deleteByPaths(vanished.toList())
        }

        val toUpsert = mutableListOf<BookEntity>()
        var added = 0
        var updated = 0
        var newlyUnreadable = 0

        for ((path, stat) in onDisk) {
            // The only suspension point below is the conditional dao.getByPath, which is skipped
            // for every newly-discovered file — without this, a large first scan over an
            // all-new library has no suspend call in the loop at all and can't be cancelled
            // mid-walk.
            ensureActive()

            val existing = known[path]
            if (existing != null &&
                existing.sizeBytes == stat.sizeBytes &&
                existing.modifiedAtMs == stat.modifiedAtMs
            ) {
                // Unchanged, including any row already marked unreadable — never reopened.
                continue
            }

            // A touch/re-sync that bumps mtime but leaves sizeBytes identical is the same content
            // (cloud sync, `touch`, a re-copy) — carry the reader's position, and title/author on a
            // re-break, forward. A changed sizeBytes means the bytes are genuinely different: a
            // stored spineIndex/charOffset would be a coordinate into a book the reader never
            // opened, and an old title would misdescribe the new content, so neither is preserved.
            val sameContent = existing != null && existing.sizeBytes == stat.sizeBytes
            val priorPosition = if (sameContent) dao.getByPath(path) else null

            // addedAtMs (and lastOpenedAtMs) are carried forward from the existing row on ANY
            // re-index of the same path, regardless of the sameContent gate above: even a genuine
            // content replacement (changed sizeBytes) is not a new acquisition, just a new version
            // of the same library entry. This is unlike spineIndex/charOffset, which really are
            // meaningless coordinates into content that no longer exists once the bytes change.
            val addedAtMs = existing?.addedAtMs ?: clock()
            val lastOpenedAtMs = existing?.lastOpenedAtMs

            val file = File(path)
            val result = try {
                extractor.extract(file)
            } catch (e: CancellationException) {
                // Never swallow cancellation into an unreadableReason — this project has already
                // been bitten by CancellationException (which extends Exception) being caught by a
                // catch-all below. An :app extractor implemented over runBlocking could let one
                // escape here.
                throw e
            } catch (e: SecurityException) {
                BookMetadataResult.Failure(e.message ?: "Permission denied")
            } catch (e: Exception) {
                // A well-behaved extractor returns Failure rather than throwing, but one bad book
                // must never abort the sync of the other fourteen — treat any escaping exception
                // the same way.
                BookMetadataResult.Failure(e.message ?: e.javaClass.simpleName)
            }

            val entity = when (result) {
                is BookMetadataResult.Success -> BookEntity(
                    path = path,
                    sizeBytes = stat.sizeBytes,
                    modifiedAtMs = stat.modifiedAtMs,
                    title = result.title,
                    author = result.author,
                    coverPath = null,
                    spineIndex = priorPosition?.spineIndex ?: 0,
                    charOffset = priorPosition?.charOffset ?: 0,
                    unreadable = false,
                    unreadableReason = null,
                    addedAtMs = addedAtMs,
                    lastOpenedAtMs = lastOpenedAtMs,
                )

                is BookMetadataResult.Failure -> BookEntity(
                    path = path,
                    sizeBytes = stat.sizeBytes,
                    modifiedAtMs = stat.modifiedAtMs,
                    title = priorPosition?.title ?: file.name,
                    author = priorPosition?.author,
                    coverPath = null,
                    spineIndex = priorPosition?.spineIndex ?: 0,
                    charOffset = priorPosition?.charOffset ?: 0,
                    unreadable = true,
                    unreadableReason = result.reason,
                    addedAtMs = addedAtMs,
                    lastOpenedAtMs = lastOpenedAtMs,
                )
            }
            toUpsert += entity

            if (existing == null) added++ else updated++
            if (entity.unreadable) newlyUnreadable++
        }

        if (toUpsert.isNotEmpty()) {
            dao.upsertAll(toUpsert)
        }

        IndexResult(
            added = added,
            updated = updated,
            removed = vanished.size,
            newlyUnreadable = newlyUnreadable,
        )
    }

    /** `(path, size, mtime)` for every `.epub` file under [roots]. Opens nothing. */
    private fun walk(): Map<String, FileStat> {
        val result = LinkedHashMap<String, FileStat>()
        for (root in roots) {
            try {
                root.walkTopDown()
                    .maxDepth(10) // Closes an unbounded symlink-loop walk; unreachable in practice
                    // on the Nomad's flat /Document layout, but free insurance.
                    .filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }
                    .forEach { file ->
                        result[file.path] = FileStat(file.length(), file.lastModified())
                    }
            } catch (e: SecurityException) {
                // A denied or half-revoked all-files-access grant can surface here as
                // walkTopDown touches directories; skip this root rather than aborting the
                // whole sync.
            }
        }
        return result
    }

    private data class FileStat(val sizeBytes: Long, val modifiedAtMs: Long)
}
