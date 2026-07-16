package dev.reader.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What [MetadataExtractor.extract] found (or didn't) for one candidate file.
 *
 * [Success.coverPath] is the resolved path (under `context.filesDir`) of a pre-scaled,
 * grayscale thumbnail file that `:app`'s EPUB-backed implementation produces via
 * `EpubCoverExtractor` (`:formats`) — never an archive-internal reference, and never a blob:
 * `:data` only ever stores a file path, in [BookEntity.coverPath]. Defaults to `null` — a
 * source-compatible addition, so a caller/fake that predates cover extraction still compiles
 * unchanged and simply never sets one.
 */
sealed interface BookMetadataResult {
    data class Success(
        val title: String,
        val author: String? = null,
        val coverPath: String? = null,
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
            // Capture cover paths before the row disappears — dao.deleteByPaths only removes the
            // row; the thumbnail file it pointed at is not Room's to know about, so it would
            // otherwise leak on disk forever (the "vanished book" half of the brief's Task 4 leak).
            val vanishedCoverPaths = vanished.mapNotNull { known[it]?.coverPath }
            dao.deleteByPaths(vanished.toList())
            vanishedCoverPaths.forEach(::deleteCoverFile)
        }

        val toUpsert = mutableListOf<BookEntity>()
        // Rows whose content is unchanged get a partial metadata UPDATE, never a whole-row
        // upsert: the position columns must not round-trip through the `known` snapshot read at
        // the top of this sync, or a BookDao.updatePosition that commits mid-scan (the reader
        // returning to the library while a slow sync is still walking) would be silently
        // reverted by the flush below. See BookDao.updateMetadata's KDoc.
        val toUpdate = mutableListOf<MetadataUpdate>()
        // Old cover files that a replaced/re-extracted row no longer references — deleted only
        // after the DB writes succeed below, the "replaced book" half of the same leak: a changed
        // book that gets a new cover (or loses one entirely, on a fresh crack failure) must not
        // leave its old thumbnail orphaned on disk.
        val staleCoverPaths = mutableListOf<String>()
        var added = 0
        var updated = 0
        var newlyUnreadable = 0

        for ((path, stat) in onDisk) {
            // The loop's only guaranteed cancellation check: a first scan over an all-new
            // library has no suspend call in the loop at all (dao.getByPath below only runs for
            // a same-content re-crack that failed) and couldn't be cancelled mid-walk without
            // this. Cancellation lands before any DB write, so a cancelled sync writes nothing
            // and the next full run heals completely.
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
            // (cloud sync, `touch`, a re-copy, or a BookDao.clearStat retry) — the row keeps its
            // identity and its reading position; only the metadata columns are refreshed, via the
            // partial-update path below. A changed sizeBytes means the bytes are genuinely
            // different: a stored spineIndex/charOffset would be a coordinate into a book the
            // reader never opened, and an old title would misdescribe the new content, so the
            // whole row is rewritten.
            val sameContent = existing != null && existing.sizeBytes == stat.sizeBytes

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

            val newCoverPath: String?
            if (sameContent) {
                val update = when (result) {
                    // A fresh Success always trusts the just-produced coverPath, exactly like it
                    // trusts result.title/result.author over anything the row holds — if the
                    // caller's extractor re-ran, it re-extracted (or regenerated a placeholder
                    // for) the cover too, at a path of its own choosing.
                    is BookMetadataResult.Success -> MetadataUpdate(
                        path = path,
                        title = result.title,
                        author = result.author,
                        coverPath = result.coverPath,
                        sizeBytes = stat.sizeBytes,
                        modifiedAtMs = stat.modifiedAtMs,
                        unreadable = false,
                        unreadableReason = null,
                    )

                    // Same content, failed crack (a transient I/O error, most likely): keep what
                    // the last successful crack produced. This getByPath reads only
                    // title/author/coverPath — never the position columns — so the snapshot it
                    // takes cannot revert a concurrent updatePosition.
                    is BookMetadataResult.Failure -> {
                        val prior = dao.getByPath(path)
                        MetadataUpdate(
                            path = path,
                            title = prior?.title ?: file.name,
                            author = prior?.author,
                            coverPath = prior?.coverPath,
                            sizeBytes = stat.sizeBytes,
                            modifiedAtMs = stat.modifiedAtMs,
                            unreadable = true,
                            unreadableReason = result.reason,
                        )
                    }
                }
                toUpdate += update
                newCoverPath = update.coverPath
                updated++
                if (update.unreadable) newlyUnreadable++
            } else {
                // New row, or genuinely different bytes at a known path: a full row, with the
                // position deliberately reset. addedAtMs (and lastOpenedAtMs) are still carried
                // forward on a content replacement — a new version of a book is not a new
                // acquisition, and neither is a coordinate into file content the way
                // spineIndex/charOffset are.
                val entity = when (result) {
                    is BookMetadataResult.Success -> BookEntity(
                        path = path,
                        sizeBytes = stat.sizeBytes,
                        modifiedAtMs = stat.modifiedAtMs,
                        title = result.title,
                        author = result.author,
                        coverPath = result.coverPath,
                        spineIndex = 0,
                        charOffset = 0,
                        unreadable = false,
                        unreadableReason = null,
                        addedAtMs = existing?.addedAtMs ?: clock(),
                        lastOpenedAtMs = existing?.lastOpenedAtMs,
                    )

                    // A replaced book that fails to crack has nothing valid to carry: the old
                    // title would misdescribe the new bytes and the old cover depicts content
                    // that is gone — null coverPath is exactly what marks it stale below.
                    is BookMetadataResult.Failure -> BookEntity(
                        path = path,
                        sizeBytes = stat.sizeBytes,
                        modifiedAtMs = stat.modifiedAtMs,
                        title = file.name,
                        author = null,
                        coverPath = null,
                        spineIndex = 0,
                        charOffset = 0,
                        unreadable = true,
                        unreadableReason = result.reason,
                        addedAtMs = existing?.addedAtMs ?: clock(),
                        lastOpenedAtMs = existing?.lastOpenedAtMs,
                    )
                }
                toUpsert += entity
                newCoverPath = entity.coverPath
                if (existing == null) added++ else updated++
                if (entity.unreadable) newlyUnreadable++
            }

            // The old cover this path used to point at is stale the moment the new row points
            // somewhere else (a new file, or nowhere) — queue it for deletion once the DB write
            // that stops referencing it has actually landed.
            if (existing?.coverPath != null && existing.coverPath != newCoverPath) {
                staleCoverPaths += existing.coverPath
            }
        }

        if (toUpsert.isNotEmpty()) {
            dao.upsertAll(toUpsert)
        }
        for (update in toUpdate) {
            dao.updateMetadata(
                path = update.path,
                title = update.title,
                author = update.author,
                coverPath = update.coverPath,
                sizeBytes = update.sizeBytes,
                modifiedAtMs = update.modifiedAtMs,
                unreadable = update.unreadable,
                unreadableReason = update.unreadableReason,
            )
        }
        // Deleted only after the writes above have committed: deleting first and then failing
        // the write would leave a row still pointing at a file that no longer exists.
        staleCoverPaths.forEach(::deleteCoverFile)

        IndexResult(
            added = added,
            updated = updated,
            removed = vanished.size,
            newlyUnreadable = newlyUnreadable,
        )
    }

    /**
     * Best-effort delete of a cover thumbnail file that no row references anymore. A denied or
     * failed delete leaves an orphaned file on disk — a storage leak, not a correctness bug, since
     * the DB row is already gone or already updated to point elsewhere — so this never throws.
     */
    private fun deleteCoverFile(path: String) {
        try {
            File(path).delete()
        } catch (e: SecurityException) {
            // See above: best-effort only.
        }
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

    /** One pending [BookDao.updateMetadata] call — the content-unchanged write path's payload. */
    private data class MetadataUpdate(
        val path: String,
        val title: String,
        val author: String?,
        val coverPath: String?,
        val sizeBytes: Long,
        val modifiedAtMs: Long,
        val unreadable: Boolean,
        val unreadableReason: String?,
    )
}
