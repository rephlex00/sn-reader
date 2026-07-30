package dev.reader.formats.mobi

import dev.reader.engine.Block
import dev.reader.engine.BookMetadata
import dev.reader.engine.RenderConfig
import dev.reader.engine.TextMeasurer
import dev.reader.engine.TocEntry
import dev.reader.formats.ReflowableDocument
import java.io.File

/**
 * A mobi7 book, presented to the reader exactly as an EPUB is.
 *
 * The structural difference the rest of the app never sees: a MOBI has no spine. Its whole text
 * is one HTML stream spread across fixed-size records, and "chapters" are byte offsets into that
 * stream. This class manufactures the chapter list the reader expects from the book's own
 * navigation, so `spineSize`, `toc`, `chapterWeights` and `readBlocks` all mean what they mean
 * for an EPUB and [ReflowableDocument] can do the rest.
 *
 * Chapter boundaries come, in order of preference:
 *  1. the `filepos` anchors on the book's own table-of-contents page (named, and what the author
 *     of the conversion intended as chapters);
 *  2. failing that, the `<mbp:pagebreak/>` marks in the text;
 *  3. failing that, the whole book as one chapter — always readable, just without navigation.
 *
 * Text is decompressed per chapter, never whole: a chapter spans a handful of 4KB records, and
 * decompressing a megabyte to show one page would be a visible pause on this hardware.
 */
class MobiDocument private constructor(
    private val palm: PalmDb,
    private val header: MobiHeader,
    private val chapterStarts: List<Int>,
    private val chapterEnds: List<Int>,
    override val toc: List<TocEntry>,
    override val metadata: BookMetadata,
    measurer: TextMeasurer,
) : ReflowableDocument(measurer) {

    /**
     * Guards [palm], whose RandomAccessFile seeks are not thread-safe, and which the background
     * prefetch reaches through [readBlocks] concurrently with a main-thread chapter load. Held
     * only across the record reads, never across parsing or measuring.
     */
    private val recordLock = Any()

    override val spineSize: Int get() = chapterStarts.size

    /**
     * Each chapter's byte span in the decompressed stream — the closest thing a MOBI has to the
     * uncompressed-size weights an EPUB reads from its zip directory, and free to compute since
     * the boundaries are already known.
     */
    override val chapterWeights: List<Long> =
        chapterStarts.indices.map { (chapterEnds[it] - chapterStarts[it]).coerceAtLeast(0).toLong() }

    override fun readBlocks(spineIndex: Int, config: RenderConfig): List<Block> {
        val html = try {
            textRange(chapterStarts[spineIndex], chapterEnds[spineIndex])
        } catch (e: MobiException) {
            return emptyList() // a damaged chapter is a blank page to turn past, not a dead book
        }
        if (html.isBlank()) return emptyList()
        // A fresh parser per call: it holds per-chapter mutable state and the base class may call
        // this from the prefetch thread while the main thread reads another chapter.
        return Mobi7BlockParser().parse(html, config.inferHeadings, ::imageBytes)
    }

    /**
     * Decompresses the records covering `[from, until)` of the text stream and returns that slice.
     * Records hold exactly [MobiHeader.recordSize] uncompressed bytes each (the last may be
     * short), which is what makes a byte offset resolvable to a record without decompressing
     * everything before it.
     */
    private fun textRange(from: Int, until: Int): String {
        val size = header.recordSize
        val first = (from / size).coerceAtLeast(0)
        val last = ((until - 1) / size).coerceAtMost(header.textRecordCount - 1)
        if (last < first) return ""
        val buffer = java.io.ByteArrayOutputStream((last - first + 1) * size)
        synchronized(recordLock) {
            for (i in first..last) {
                // Text records are 1-based in the PalmDB: record 0 is the header.
                val index = i + 1
                if (index >= palm.recordCount) break
                buffer.write(decompress(palm.record(index)))
            }
        }
        val bytes = buffer.toByteArray()
        val startInSlice = (from - first * size).coerceIn(0, bytes.size)
        val endInSlice = (until - first * size).coerceIn(startInSlice, bytes.size)
        return String(bytes, startInSlice, endInSlice - startInSlice, header.charset)
    }

    private fun decompress(record: ByteArray): ByteArray {
        val trimmed = PalmDoc.trimTrailingEntries(record, header.extraDataFlags)
        return if (header.compression == PalmDoc.COMPRESSION_NONE) trimmed else PalmDoc.decompress(trimmed)
    }

    /**
     * An inline image's bytes by its 1-based `recindex`. Oversized or out-of-range indices yield
     * null, which the renderer draws as nothing — the same degradation an unreadable EPUB image
     * gets.
     */
    private fun imageBytes(recindex: Int): ByteArray? {
        if (header.firstImageIndex <= 0) return null
        val index = header.firstImageIndex + recindex - 1
        return synchronized(recordLock) {
            if (index !in 0 until palm.recordCount) return@synchronized null
            val bytes = palm.record(index)
            if (bytes.size > MAX_IMAGE_BYTES) null else bytes
        }
    }

    /**
     * Writes this book's library thumbnail to [destination] — its own cover art if it carries
     * any, a typographic placeholder if not — and reports which. The MOBI counterpart of
     * `EpubCoverExtractor.extract`, exposed here rather than as a free class because only this
     * object can reach the image records.
     */
    fun extractCover(destination: File): dev.reader.formats.epub.CoverOutcome =
        MobiCoverExtractor().extract(coverBytes(), metadata.title, destination)

    /** The cover image's bytes, or null when the book declares none. */
    private fun coverBytes(): ByteArray? {
        val index = header.coverRecordIndex ?: return null
        return synchronized(recordLock) {
            if (index !in 0 until palm.recordCount) return@synchronized null
            palm.record(index).takeIf { it.size in 1..MAX_IMAGE_BYTES }
        }
    }

    override fun close() = palm.close()

    companion object {
        /** Matches `EpubException`'s cap: an image larger than this is treated as unreadable. */
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024

        /** How much of the text to scan when hunting for chapter marks in the fallback path. */
        private const val MAX_SCAN_BYTES = 4 * 1024 * 1024

        /** `<a filepos=0000043599>Title</a>`, quoted or bare — mobi7 writes it both ways. */
        private val ANCHOR = Regex(
            """<a[^>]*\bfilepos\s*=\s*["']?(\d+)["']?[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val GUIDE_TOC = Regex(
            """<reference[^>]*\btype\s*=\s*["']?toc["']?[^>]*\bfilepos\s*=\s*["']?(\d+)["']?""",
            RegexOption.IGNORE_CASE,
        )
        private val TAGS = Regex("<[^>]+>")

        /** @throws MobiException if the file is not a readable, un-DRMed, mobi7 book. */
        fun open(file: File, measurer: TextMeasurer): MobiDocument {
            val palm = PalmDb.open(file)
            try {
                val header = MobiHeader.parse(palm.record(0))
                if (header.textRecordCount <= 0) throw MobiException.Malformed("MOBI has no text records")

                val reader = RecordReader(palm, header)
                val textLength = header.textLength.coerceAtMost(header.textRecordCount * header.recordSize)
                val nav = findChapters(reader, header, textLength)

                val title = header.title?.takeIf { it.isNotBlank() }
                    ?: palm.name.takeIf { it.isNotBlank() }
                    ?: file.nameWithoutExtension
                val metadata = BookMetadata(
                    title = title,
                    author = header.author,
                    language = null,
                    coverHref = null,
                )
                val toc = nav.titles.mapIndexedNotNull { index, name ->
                    name?.let { TocEntry(title = it, spineIndex = index, charOffset = 0) }
                }
                return MobiDocument(palm, header, nav.starts, nav.ends, toc, metadata, measurer)
            } catch (e: Throwable) {
                palm.close()
                throw when (e) {
                    is MobiException -> e
                    is java.util.concurrent.CancellationException -> e
                    else -> MobiException.Malformed("The book could not be read: ${e.message}")
                }
            }
        }

        private class Chapters(val starts: List<Int>, val ends: List<Int>, val titles: List<String?>)

        /** Reads decompressed byte ranges at open time, before a MobiDocument exists to do it. */
        private class RecordReader(private val palm: PalmDb, private val header: MobiHeader) {
            /**
             * The raw decompressed bytes of `[from, until)`. Callers that need offsets back out
             * must work here rather than on [range]'s String: a `filepos` is a BYTE offset, and
             * one non-ASCII character earlier in the stream puts every String index out of step
             * with it.
             */
            fun bytes(from: Int, until: Int): ByteArray {
                val size = header.recordSize
                val first = (from / size).coerceAtLeast(0)
                val last = ((until - 1) / size).coerceAtMost(header.textRecordCount - 1)
                if (last < first) return ByteArray(0)
                val out = java.io.ByteArrayOutputStream((last - first + 1) * size)
                for (i in first..last) {
                    val index = i + 1
                    if (index >= palm.recordCount) break
                    val trimmed = PalmDoc.trimTrailingEntries(palm.record(index), header.extraDataFlags)
                    out.write(
                        if (header.compression == PalmDoc.COMPRESSION_NONE) trimmed else PalmDoc.decompress(trimmed),
                    )
                }
                val all = out.toByteArray()
                val s = (from - first * size).coerceIn(0, all.size)
                val e = (until - first * size).coerceIn(s, all.size)
                return all.copyOfRange(s, e)
            }

            fun range(from: Int, until: Int): String = String(bytes(from, until), header.charset)
        }

        /**
         * Byte offsets of every `<mbp:pagebreak…>` tag's end within [haystack]. Searched in bytes
         * for the reason given on [RecordReader.bytes]; the tag is pure ASCII, and UTF-8 is
         * ASCII-transparent, so a byte search for it is exact.
         */
        private fun pageBreakOffsets(haystack: ByteArray): List<Int> {
            val needle = "<mbp:pagebreak".toByteArray(Charsets.US_ASCII)
            val out = mutableListOf<Int>()
            var i = 0
            outer@ while (i <= haystack.size - needle.size) {
                for (k in needle.indices) {
                    // Case-insensitive on the ASCII letters, since the tag is written both ways.
                    val a = haystack[i + k].toInt().toChar().lowercaseChar()
                    val b = needle[k].toInt().toChar()
                    if (a != b) { i++; continue@outer }
                }
                // Advance to just past the tag's closing '>'.
                var j = i + needle.size
                while (j < haystack.size && haystack[j] != '>'.code.toByte()) j++
                out += (j + 1).coerceAtMost(haystack.size)
                i = j + 1
            }
            return out
        }

        /**
         * Works out where the chapters are. Tries the book's own TOC page first — its anchors are
         * both named and deliberately placed — then page-break marks, then gives up gracefully and
         * calls the whole book one chapter.
         */
        private fun findChapters(reader: RecordReader, header: MobiHeader, textLength: Int): Chapters {
            fromGuideToc(reader, header, textLength)?.let { return it }
            fromPageBreaks(reader, textLength)?.let { return it }
            return Chapters(listOf(0), listOf(textLength), listOf(null))
        }

        /**
         * Chapter starts from the anchors on the book's TOC page, which the guide element points
         * at. The TOC page itself is excluded from the reading order — it is navigation, and the
         * reader has a Contents panel of its own.
         */
        private fun fromGuideToc(reader: RecordReader, header: MobiHeader, textLength: Int): Chapters? {
            // The guide lives in <head>, at the very start of the stream.
            val head = reader.range(0, minOf(HEAD_SCAN_BYTES, textLength))
            val tocStart = GUIDE_TOC.find(head)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (tocStart !in 1 until textLength) return null

            val tocPage = reader.range(tocStart, textLength)
            val anchors = ANCHOR.findAll(tocPage)
                .mapNotNull { m ->
                    val pos = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val name = TAGS.replace(m.groupValues[2], " ").replace(WS, " ").trim()
                    if (pos in 0 until tocStart) pos to name.takeIf { it.isNotEmpty() } else null
                }
                .distinctBy { it.first }
                .sortedBy { it.first }
                .toList()
            if (anchors.size < 2) return null

            // The first chapter absorbs whatever precedes the first anchor — in practice the
            // <html><head><guide> preamble, which carries no text.
            val starts = anchors.mapIndexed { i, (pos, _) -> if (i == 0) 0 else pos }
            val ends = starts.drop(1) + tocStart
            return Chapters(starts, ends, anchors.map { it.second })
        }

        /** Chapter starts from `<mbp:pagebreak/>` marks, for a book with no usable TOC page. */
        private fun fromPageBreaks(reader: RecordReader, textLength: Int): Chapters? {
            val scanned = minOf(MAX_SCAN_BYTES, textLength)
            val marks = pageBreakOffsets(reader.bytes(0, scanned))
            if (marks.size < 2) return null
            val starts = (listOf(0) + marks).filter { it < textLength }.distinct().sorted()
            val ends = starts.drop(1) + textLength
            return Chapters(starts, ends, starts.map { null })
        }

        private const val HEAD_SCAN_BYTES = 8 * 1024
        private val WS = Regex("\\s+")
    }
}
