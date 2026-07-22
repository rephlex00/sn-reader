package dev.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.reader.engine.RenderConfig
import dev.reader.formats.epub.EpubDocument
import dev.reader.formats.render.AndroidMeasuredChapter
import dev.reader.formats.render.AndroidTextMeasurer
import dev.reader.formats.render.SpannedChapterBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Renders, persists and serves the scrubbing preview's thumbnail strip: ~100 sampled pages per
 * (book, typography config), drawn once to half-screen bitmaps on disk, then served instantly on
 * every later drag. Generation is the project's ONE authorized exception to the 0%-idle rule —
 * a single low-priority, cancellable, one-shot coroutine after a book's first open under a given
 * config (measured whole-book pagination: 2.2–7.7 s on the Nomad; drawing adds a few more).
 *
 * Thread safety: [generate] opens its OWN EpubDocument. The reader's document has an
 * unsynchronized, main-thread-only chapter cache; the generator never touches it, and the LRU
 * serving the page being read is never evicted by generation.
 *
 * Crash safety: the index file is written LAST, atomically (temp + rename). A directory with no
 * index is a partial generation and [stripFor] treats it as absent — regenerated on next open.
 *
 * Landscape (`config.columnCount > 1`) is not perfected here: the chapter's Layout is measured at
 * column width (narrow), but [renderThumbnail] draws it into a full-viewport-width bitmap, leaving
 * blank space on the right. Acceptable for v2 — the reader is portrait-dominant, and a rotation
 * changes [configHash] anyway, so the strip simply regenerates for the new orientation.
 */
class PreviewStripStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, "previews")

    private fun bookDir(bookFile: File): File = File(root, sha256Hex(bookFile.absolutePath))

    private fun stripDir(bookFile: File, config: RenderConfig): File =
        File(bookDir(bookFile), configHash(config))

    fun thumbnailFile(bookFile: File, config: RenderConfig, entry: StripEntry): File =
        File(stripDir(bookFile, config), entry.fileName)

    /**
     * The valid strip for (book, config), or null when absent, partial, or stale (config hash or
     * the book's size/mtime moved on). A hit touches the book dir's mtime — the LRU signal
     * [evictOverBudget] orders by.
     */
    fun stripFor(bookFile: File, config: RenderConfig): StripIndex? {
        val indexFile = File(stripDir(bookFile, config), INDEX_FILE)
        if (!indexFile.isFile) return null
        val index = parseStripIndex(indexFile.readText()) ?: return null
        if (index.configHash != configHash(config)) return null
        if (index.bookSizeBytes != bookFile.length()) return null
        if (index.bookModifiedAtMs != bookFile.lastModified()) return null
        bookDir(bookFile).setLastModified(System.currentTimeMillis())
        return index
    }

    /**
     * The whole generation job. Runs on [Dispatchers.Default] (CPU-bound layout + compress), checks
     * cancellation between pages, and deletes any stale sibling strips (old configs) for this book
     * on success. Cancellation at ANY point is safe: the index is the last thing written.
     */
    suspend fun generate(bookFile: File, config: RenderConfig) = withContext(Dispatchers.Default) {
        val dir = stripDir(bookFile, config)
        dir.deleteRecursively()
        dir.mkdirs()

        val measurer = AndroidTextMeasurer(SpannedChapterBuilder(), BundledTypefaceProvider(context))
        EpubDocument.open(bookFile, measurer).use { doc ->
            val pageCounts = (0 until doc.spineSize).map { i ->
                ensureActive()
                doc.chapter(i, config).pages.size
            }
            val plan = samplePlan(pageCounts)
            val totalPages = pageCounts.sum().coerceAtLeast(1)
            val cumulative = pageCounts.runningFold(0) { acc, n -> acc + n }

            val entries = ArrayList<StripEntry>(plan.size)
            for ((n, sample) in plan.withIndex()) {
                ensureActive()
                val (spine, page) = sample
                val chapter = doc.chapter(spine, config)
                val layout = (chapter.measured as? AndroidMeasuredChapter)?.layout ?: continue
                val p = chapter.pages.getOrNull(page) ?: continue
                val fileName = "%03d.webp".format(n)
                val bitmap = renderThumbnail(layout, p, config)
                try {
                    File(dir, fileName).outputStream().use { out ->
                        bitmap.compress(THUMBNAIL_FORMAT, THUMBNAIL_QUALITY, out)
                    }
                } finally {
                    bitmap.recycle()
                }
                entries += StripEntry(
                    fraction = (cumulative[spine] + page).toFloat() / totalPages,
                    spineIndex = spine,
                    pageIndex = page,
                    fileName = fileName,
                )
            }

            val index = StripIndex(
                configHash = configHash(config),
                bookSizeBytes = bookFile.length(),
                bookModifiedAtMs = bookFile.lastModified(),
                totalPages = totalPages,
                entries = entries,
            )
            // Written last, atomically: a crash anywhere above leaves a partial dir stripFor ignores.
            val tmp = File(dir, "$INDEX_FILE.tmp")
            tmp.writeText(index.serialize())
            if (!tmp.renameTo(File(dir, INDEX_FILE))) tmp.delete()
        }

        // This config's strip is now the book's only valid one; siblings are stale configs.
        bookDir(bookFile).listFiles()?.forEach { if (it.isDirectory && it != dir) it.deleteRecursively() }
    }

    /**
     * Draws one page into a half-scale bitmap, mirroring [PageView.drawColumn]'s clip/translate:
     * white ground, content clipped to the page's own vertical span, layout translated so the
     * page's top sits at the margin. Text is already black-on-white; lossy WEBP compression
     * (see [THUMBNAIL_FORMAT]) is what actually shrinks these to a reasonable on-disk size.
     *
     * [Page] has no bottom/height field, so the clip bottom is computed exactly as
     * [PageView.pageClipBottom] does: the page's last line's bottom (translated into this
     * viewport's space), clamped to the content box bottom — the one case the paginator allows a
     * page to exceed the box (a single line taller than the whole page).
     *
     * Only ever called with a single-column [layout]/[page] (a thumbnail is always one page), so
     * unlike [PageView] there is no second column to draw.
     */
    private fun renderThumbnail(layout: android.text.Layout, page: dev.reader.engine.Page, config: RenderConfig): Bitmap {
        val bitmap = Bitmap.createBitmap(
            (config.viewportWidthPx * SCALE).toInt().coerceAtLeast(1),
            (config.viewportHeightPx * SCALE).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(SCALE, SCALE)
        val clipBottom = minOf(
            (config.viewportHeightPx - config.marginPx).toFloat(),
            config.marginPx + layout.getLineBottom(page.endLine).toFloat() - page.topPx,
        )
        canvas.clipRect(
            config.marginPx.toFloat(),
            config.marginPx.toFloat(),
            (config.viewportWidthPx - config.marginPx).toFloat(),
            clipBottom,
        )
        canvas.translate(config.marginPx.toFloat(), (config.marginPx - page.topPx).toFloat())
        layout.draw(canvas)
        return bitmap
    }

    /** Every strip this book owns, all configs — the config-change and book-replaced hammer. */
    fun deleteStripsFor(bookFile: File) {
        bookDir(bookFile).deleteRecursively()
    }

    /**
     * Deletes whole books' strip directories, oldest dir-mtime first (touched on every [stripFor]
     * hit), until total size is under [capBytes]. [keep]'s book is never evicted — it is the one
     * being read right now. Strips are always regenerable; nothing here touches reading data.
     */
    fun evictOverBudget(capBytes: Long = DEFAULT_CAP_BYTES, keep: File? = null) {
        val keepDir = keep?.let { bookDir(it) }
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        var total = dirs.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        if (total <= capBytes) return
        for (dir in dirs.sortedBy { it.lastModified() }) {
            if (dir == keepDir) continue
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            dir.deleteRecursively()
            total -= size
            if (total <= capBytes) return
        }
    }

    private companion object {
        const val INDEX_FILE = "index"
        const val SCALE = 0.5f
        val THUMBNAIL_FORMAT = Bitmap.CompressFormat.WEBP_LOSSY
        const val THUMBNAIL_QUALITY = 75
        const val DEFAULT_CAP_BYTES = 50L * 1024 * 1024

        fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return buildString(digest.size * 2) { digest.forEach { append("%02x".format(it)) } }
        }
    }
}
