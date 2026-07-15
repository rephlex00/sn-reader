package dev.reader.formats.epub

import dev.reader.engine.BookMetadata
import dev.reader.engine.MeasuredChapter
import dev.reader.engine.Page
import dev.reader.engine.Paginator
import dev.reader.engine.RenderConfig
import dev.reader.engine.TextMeasurer
import dev.reader.engine.TocEntry
import dev.reader.formats.Document
import dev.reader.formats.ResourceSource
import dev.reader.formats.ZipResourceSource
import java.io.File

/** One chapter, measured and sliced into pages. */
data class PaginatedChapter(val measured: MeasuredChapter, val pages: List<Page>)

class EpubDocument private constructor(
    private val source: ResourceSource,
    private val pkg: EpubPackage,
    private val measurer: TextMeasurer,
    override val toc: List<TocEntry>,
) : Document {

    private val blockParser = XhtmlBlockParser()
    private val paginator = Paginator()

    // Only the current config's chapters are worth holding; a settings change invalidates
    // all of them, and the locator puts the reader back on the same sentence anyway.
    private var cacheConfig: RenderConfig? = null
    private val cache = mutableMapOf<Int, PaginatedChapter>()

    override val metadata: BookMetadata get() = pkg.metadata
    override val spineSize: Int get() = pkg.spine.size

    /**
     * Measures and paginates chapter [spineIndex]. Cached per config, so paging within a
     * chapter costs nothing after the first call.
     */
    fun chapter(spineIndex: Int, config: RenderConfig): PaginatedChapter {
        require(spineIndex in pkg.spine.indices) {
            "spineIndex $spineIndex out of range 0..${pkg.spine.lastIndex}"
        }
        if (cacheConfig != config) {
            cache.clear()
            cacheConfig = config
        }
        return cache.getOrPut(spineIndex) {
            val blocks = readBlocks(spineIndex)
            val measured = measurer.measure(blocks, config)
            val pages = if (blocks.isEmpty()) {
                emptyList()
            } else {
                paginator.paginate(measured, config.contentHeightPx)
            }
            PaginatedChapter(measured, pages)
        }
    }

    private fun readBlocks(spineIndex: Int): List<dev.reader.engine.Block> {
        val item = pkg.manifest[pkg.spine[spineIndex]] ?: return emptyList()
        // A chapter listed in the spine but absent from the archive is a broken book, not
        // a crashing one: show it as empty and let the reader move on.
        val xhtml = source.readText(item.href) ?: return emptyList()
        return blockParser.parse(xhtml, item.href)
    }

    override fun close() = source.close()

    companion object {
        /** @throws EpubException if the file is not a readable, un-DRMed EPUB. */
        fun open(file: File, measurer: TextMeasurer): EpubDocument {
            // Constructing the source is itself a typed-error boundary: ZipFile throws
            // raw ZipException for a .txt renamed .epub or a truncated download, which
            // no caller catching EpubException would survive.
            val source = try {
                ZipResourceSource(file)
            } catch (e: java.io.IOException) {
                throw EpubException.NotAnEpub("Not a readable EPUB archive: ${e.message}")
            }
            try {
                val pkg = EpubPackageParser().parse(source)
                val toc = EpubTocParser().parse(source, pkg)
                return EpubDocument(source, pkg, measurer, toc)
            } catch (e: Throwable) {
                source.close()
                throw e
            }
        }
    }
}
