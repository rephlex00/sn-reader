package dev.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What [MetadataExtractor.extract] found (or didn't) for one candidate file.
 *
 * [Success.coverHref] is a reference *into* the book archive (e.g. an OPF manifest href) — this
 * module never decodes an image. Resolving it into an actual grayscale thumbnail file under
 * `filesDir` is Task 4's job (`EpubCoverExtractor`, in `:formats`/`:app`); `:data` only stores the
 * resulting file path, in [BookEntity.coverPath].
 */
sealed interface BookMetadataResult {
    data class Success(
        val title: String,
        val author: String? = null,
        val coverHref: String? = null,
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

/** Tallies from one [LibraryIndexer.sync] call. */
data class IndexResult(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val unreadable: Int,
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
 */
class LibraryIndexer(
    private val dao: BookDao,
    private val roots: List<File>,
    private val extractor: MetadataExtractor,
) {

    suspend fun sync(): IndexResult = withContext(Dispatchers.IO) {
        val onDisk = walk()
        val known = dao.getAllStats().associateBy { it.path }

        val vanished = known.keys - onDisk.keys
        if (vanished.isNotEmpty()) {
            dao.deleteByPaths(vanished.toList())
        }

        val toUpsert = mutableListOf<BookEntity>()
        var added = 0
        var updated = 0
        var unreadable = 0

        for ((path, stat) in onDisk) {
            val existing = known[path]
            if (existing != null &&
                existing.sizeBytes == stat.sizeBytes &&
                existing.modifiedAtMs == stat.modifiedAtMs
            ) {
                // Unchanged, including any row already marked unreadable — never reopened.
                continue
            }

            // Carry the prior reading position forward across a re-index (e.g. a re-synced file
            // with a bumped mtime but the same content). Only fetched for the small changed/new
            // set, and only when a row already exists.
            val priorPosition = if (existing != null) dao.getByPath(path) else null

            val file = File(path)
            val result = try {
                extractor.extract(file)
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
                )
            }
            toUpsert += entity

            if (existing == null) added++ else updated++
            if (entity.unreadable) unreadable++
        }

        if (toUpsert.isNotEmpty()) {
            dao.upsertAll(toUpsert)
        }

        IndexResult(added = added, updated = updated, removed = vanished.size, unreadable = unreadable)
    }

    /** `(path, size, mtime)` for every `.epub` file under [roots]. Opens nothing. */
    private fun walk(): Map<String, FileStat> {
        val result = LinkedHashMap<String, FileStat>()
        for (root in roots) {
            try {
                root.walkTopDown()
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
