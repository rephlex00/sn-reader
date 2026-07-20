package dev.reader.formats.render

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import dev.reader.engine.Block
import dev.reader.engine.BlockStyle
import dev.reader.engine.RenderConfig
import dev.reader.engine.StyledText
import dev.reader.engine.TextAlign
import dev.reader.engine.dropCapLength
import dev.reader.engine.isSeparatorLine
import dev.reader.formats.epub.sampleSizeFor
import kotlin.math.roundToInt
import android.text.style.StyleSpan as AndroidStyleSpan

/**
 * A chapter as a single styled string, plus the offsets where a hard page break falls and the
 * character ranges (`start until end`) each heading block occupies. [headingRanges] feed
 * [AndroidMeasuredChapter.isHeadingLine] so the paginator can keep a heading with its body.
 */
data class ChapterText(
    val text: Spanned,
    val breakOffsets: Set<Int>,
    val headingRanges: List<IntRange> = emptyList(),
)

private const val BLOCK_SEPARATOR = "\n\n"

/**
 * The separator joining two consecutive flowing body paragraphs — a single newline, so the
 * paragraph gap equals line spacing instead of a full blank line. Everything else (headings,
 * images, quotes, list items, and scene-break paragraphs) still gets [BLOCK_SEPARATOR]'s
 * breathing room; see [separatorBetween].
 */
private const val PARAGRAPH_JOIN = "\n"

/** The reader's own first-line indent for a body paragraph, in ems of the reader's text size. */
private const val PARAGRAPH_INDENT_EM = 1.5f

private val HEADING_SCALE = mapOf(1 to 1.6f, 2 to 1.4f, 3 to 1.25f, 4 to 1.15f, 5 to 1.1f, 6 to 1.05f)

/**
 * The single character an [ImageSpan] is drawn over — the standard idiom is one placeholder
 * carrying the span, so the image occupies exactly one character like any other span run and
 * the page-break offset logic keeps working unchanged. U+FFFC OBJECT REPLACEMENT CHARACTER.
 */
private const val IMAGE_PLACEHOLDER = "￼"

/** How many lines the chapter-opening drop cap spans — the enlarged initial's height. */
private const val DROP_CAP_LINES = 3

/**
 * Flattens [Block]s into one Spanned per chapter. One string per chapter (rather than
 * per block) is what lets a single StaticLayout do all the shaping, and what makes a
 * character offset a meaningful locator.
 *
 * [typefaces] resolves [RenderConfig.fontFamily] to the reader's [Typeface] — needed only so the
 * chapter-opening drop cap ([DropCapSpan]) is painted in the same face as the body text. Defaults
 * to [TypefaceProvider.Platform] so non-rendering callers (metadata/cover extraction, tests) keep
 * working unchanged; production passes the bundled provider so the cap matches the chosen font.
 */
class SpannedChapterBuilder(private val typefaces: TypefaceProvider = TypefaceProvider.Platform) {

    fun build(blocks: List<Block>, config: RenderConfig): ChapterText {
        val sb = SpannableStringBuilder()
        val breaks = mutableSetOf<Int>()
        val headingRanges = mutableListOf<IntRange>()
        var pendingBreak = false
        var prev: Block? = null
        // Whether any block has emitted text yet — true once `prev` is first set below.
        // Used to detect the chapter-OPENING heading (the chapter title): only the FIRST
        // emitted block, if it's a Block.Heading, gets the extra headroom + forced centering.
        // A leading PageBreak or a non-emitting image (null/undecodable bytes) before the
        // heading doesn't count as "first emitted" — this flag only flips once something
        // actually contributes text, exactly mirroring the `prev` update below.
        var hasEmitted = false
        // Whether a chapter-opening drop cap may still be placed. The enlarged initial goes on the
        // chapter's FIRST body paragraph, provided only the opening title(s) precede it. The first
        // other emitted block — an image, a scene-break line, a quote, a list item — means the
        // chapter doesn't open on prose, so no cap. Decided exactly once (see the drop-cap block
        // below), then this stays false for the rest of the chapter.
        var dropCapEligible = true

        for (block in blocks) {
            if (block is Block.PageBreak) {
                pendingBreak = true
                continue
            }
            if (sb.isNotEmpty()) sb.append(separatorBetween(prev, block))
            val start = sb.length
            // A body paragraph gets the reader's own first-line indent UNLESS it opens a
            // section: chapter start, or right after a heading/image/scene-break/page-break
            // (pendingBreak still true here — an explicit PageBreak occurred since the last
            // emitted block, even though the break itself contributed no text of its own).
            val indentParagraph = !pendingBreak && !isBreakLike(prev)
            // The chapter-opening title: the first block to actually emit text is a Heading.
            // Prepended BEFORE the heading's own text so the title itself (not the blank
            // lines) is what a search for its text finds at the front of the chapter.
            val isOpeningHeading = !hasEmitted && block is Block.Heading
            if (isOpeningHeading) sb.append(BLOCK_SEPARATOR)
            val headingStart = sb.length
            appendBlock(sb, block, config, indentParagraph)
            // Record the heading's own char range (past any opening-title headroom, from the
            // first heading character to sb.length) so AndroidMeasuredChapter can map it to the
            // heading's lines for the paginator's keep-heading rule. Only when the heading
            // actually emitted text — an empty heading contributes no line to keep.
            if (block is Block.Heading && sb.length > headingStart) {
                headingRanges += headingStart until sb.length
            }
            if (isOpeningHeading) {
                // Reader baseline, unconditional (regardless of config.publisherStyling): the
                // chapter title is centered — this is IN ADDITION to the existing heading
                // bold+scale, which appendBlock's own Heading branch already applied above.
                sb.setSpan(
                    AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    headingStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (sb.length > start) hasEmitted = true
            // Drop cap: reader baseline, applied regardless of config.publisherStyling (like the
            // centered opening title and scene breaks). Once a block actually emits text and we
            // haven't yet decided, classify the opener: an opening heading (the title) is
            // transparent — stay eligible for the paragraph that follows; a body paragraph is the
            // target — cap its initial when it's a letter (dropCapLength == 1), then done; anything
            // else opening the chapter (image, scene-break line, quote, list item) means no cap.
            if (dropCapEligible && sb.length > start) {
                when {
                    block is Block.Heading -> Unit // the opening title; the next prose still caps
                    block is Block.Paragraph && !isSeparatorLine(block.text.text) -> {
                        if (dropCapLength(block.text.text) == 1) applyDropCap(sb, start, config)
                        dropCapEligible = false
                    }
                    else -> dropCapEligible = false // image / scene break / quote / list opener
                }
            }
            // The break offset is recorded only once a block actually contributes text,
            // and pins to that block's own first character — after the separator, and
            // past any text-free block sitting between the break and the text. A block is
            // text-free when it appends nothing: a PageBreak always, and an image whose
            // bytes are null or don't decode. A decodable image DOES contribute text (its
            // one placeholder character), so a break before it pins to that character —
            // the image is text-bearing like any paragraph. Recording eagerly on the next
            // block regardless would land the break on the blank separator line, opening
            // the new page with an empty line (or, for a trailing break, a blank final
            // page). A pending break that never meets another text-bearing block
            // contributes no break at all.
            if (pendingBreak && sb.length > start) {
                breaks += start
                pendingBreak = false
            }
            if (sb.length > start) prev = block
        }
        return ChapterText(sb, breaks, headingRanges)
    }

    /**
     * Enlarges the initial at [textStart] into a chapter-opening drop cap. Two spans over exactly
     * `[textStart, textStart + 1)`, inserting/removing nothing so every offset is preserved:
     *  - a [DropCapSpan] that reserves the left margin over the first [DROP_CAP_LINES] lines and
     *    draws the big glyph once, in the reader's face (resolved via [typefaces]) and gray;
     *  - a [ZeroWidthSpan] so the ordinary-size glyph the layout would otherwise paint is both
     *    invisible AND zero-advance — the covered character contributes no inline width, so
     *    [DropCapSpan]'s margin can reserve one uniform width for every band line rather than
     *    special-casing the line that still contains the character. Applied last so it owns the
     *    character's rendering outright — a publisher colour/gray span on that same character no
     *    longer matters, since [ZeroWidthSpan] is what Android calls to draw the run, not it.
     *
     * The cap's size derives from the reader's typography: body size ([RenderConfig.textSizePx])
     * and line height (`textSizePx * lineSpacingMultiplier`), so it scales with the Aa settings.
     */
    private fun applyDropCap(sb: SpannableStringBuilder, textStart: Int, config: RenderConfig) {
        val lineHeightPx = config.textSizePx * config.lineSpacingMultiplier
        val span = DropCapSpan(
            initial = sb[textStart],
            linesSpanned = DROP_CAP_LINES,
            textSizePx = config.textSizePx,
            lineHeightPx = lineHeightPx,
            typeface = typefaces.get(config.fontFamily),
        )
        sb.setSpan(span, textStart, textStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(
            ZeroWidthSpan(),
            textStart, textStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /**
     * The separator between the previously-EMITTED block [prev] (null at chapter start) and
     * the block about to be appended, [cur]. A single [PARAGRAPH_JOIN] newline when both are
     * flowing body paragraphs — neither a scene-break line — so the gap between them equals
     * line spacing; [BLOCK_SEPARATOR]'s blank line otherwise (headings, images, quotes, list
     * items, and scene-break paragraphs all keep their breathing room).
     */
    private fun separatorBetween(prev: Block?, cur: Block): String {
        val bothFlowingParagraphs = prev is Block.Paragraph && cur is Block.Paragraph &&
            !isSeparatorLine(prev.text.text) && !isSeparatorLine(cur.text.text)
        return if (bothFlowingParagraphs) PARAGRAPH_JOIN else BLOCK_SEPARATOR
    }

    /**
     * Whether [prev] is a section-opening block — the reason a paragraph right after it stays
     * flush rather than getting the reader's first-line indent (see [PARAGRAPH_INDENT_EM]'s use
     * in the `Paragraph` branch of [appendBlock]). True for chapter start (`null`), a heading,
     * an image, or a scene-break paragraph. False for a quote or list item: those don't open a
     * new section, so the body paragraph following one still indents.
     */
    private fun isBreakLike(prev: Block?): Boolean = when (prev) {
        null -> true
        is Block.Heading -> true
        is Block.Image -> true
        is Block.Paragraph -> isSeparatorLine(prev.text.text)
        else -> false
    }

    private fun appendBlock(
        sb: SpannableStringBuilder,
        block: Block,
        config: RenderConfig,
        indentParagraph: Boolean,
    ) {
        when (block) {
            is Block.Paragraph -> {
                // A scene-break line ("***" etc.) renders as a band of blank vertical space,
                // not centered glyphs: no visible text, no AlignmentSpan. It still counts as
                // an emitted block — the appended "\n" advances sb.length, so the break-offset
                // / prev-emission logic in build() still sees it, and separatorBetween /
                // isBreakLike still classify it as a separator paragraph (not a body paragraph)
                // for the block-join and first-after-break-indent rules above.
                if (isSeparatorLine(block.text.text)) {
                    sb.append("\n")
                } else {
                    val start = sb.length
                    appendStyled(sb, block.text, config)
                    applyBlockStyle(sb, start, sb.length, block.style, config)

                    // Reader baseline, applied regardless of config.publisherStyling: a body
                    // paragraph gets the reader's own first-line indent, unless it opens a
                    // section (indentParagraph false) or the publisher already indented it.
                    if (indentParagraph && !(config.publisherStyling && block.style.textIndentEm != null)) {
                        sb.setSpan(
                            LeadingMarginSpan.Standard((PARAGRAPH_INDENT_EM * config.textSizePx).roundToInt(), 0),
                            start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }

            is Block.Heading -> {
                val start = sb.length
                appendStyled(sb, block.text, config)
                // A Heading may now also carry the publisher's own size on individual runs.
                // Applying both the semantic HEADING_SCALE and a run's RelativeSizeSpan would
                // double-enlarge that run, so the semantic scale is applied only to the sub-ranges
                // the publisher did NOT size — the scale is the fallback for text the publisher
                // said nothing about. A run the publisher sized keeps exactly its size; the rest
                // of the heading still reads as a heading. The whole heading stays bold either way.
                val scale = HEADING_SCALE.getValue(block.level)
                val sizedRuns = if (config.publisherStyling) {
                    block.text.spans.filter { it.style.sizeRatio != null }
                        .map { start + it.start to start + it.end }
                } else {
                    emptyList()
                }
                for ((from, to) in unsizedRanges(start, sb.length, sizedRuns)) {
                    // A fresh span per range: one RelativeSizeSpan instance can only occupy one range.
                    sb.setSpan(RelativeSizeSpan(scale), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                sb.setSpan(
                    AndroidStyleSpan(Typeface.BOLD),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                applyBlockStyle(sb, start, sb.length, block.style, config)
            }

            is Block.Quote -> {
                val start = sb.length
                appendStyled(sb, block.text, config)
                sb.setSpan(
                    LeadingMarginSpan.Standard(config.textSizePx.toInt()),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                sb.setSpan(
                    AndroidStyleSpan(Typeface.ITALIC),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                applyBlockStyle(sb, start, sb.length, block.style, config)
            }

            is Block.ListItem -> {
                val start = sb.length
                sb.append(block.ordinal?.let { "$it. " } ?: "• ")
                appendStyled(sb, block.text, config)
                applyBlockStyle(sb, start, sb.length, block.style, config)
            }

            // An image whose bytes resolved and decode contributes one placeholder character
            // carrying an ImageSpan; one whose bytes are null (unresolvable href) or don't
            // decode appends nothing, exactly as every image did before this plan. So an
            // image-ONLY chapter (a cover-image-first chapter, commonly) now paginates to ONE
            // page — the image — rather than the zero it used to; a chapter whose sole image
            // is broken still yields zero pages. The open path's fresh-open empty-skip
            // (ReaderActivity.advance / firstNonEmptyFrom) therefore no longer walks past a
            // cover chapter — a fresh open lands on the cover image, then the reader turns to
            // the text. A stored-position restore is unaffected: the offset still resolves.
            is Block.Image -> appendImage(sb, block, config)

            Block.PageBreak -> Unit
        }
    }

    /**
     * Decodes [block]'s resolved bytes into a downsampled, grayscale [ImageSpan] drawn over a
     * single [IMAGE_PLACEHOLDER] character. Degrades to appending nothing — never throws — when
     * the bytes are null (an unresolvable href) or don't decode as an image, matching the
     * pre-image-rendering behavior for those cases.
     *
     * Never decodes a full-resolution image into memory: [BitmapFactory.Options.inJustDecodeBounds]
     * reads the header first, [sampleSizeFor] picks a power-of-two downsample factor, and only
     * then is the image decoded for real — the same protection the cover extractor uses (a 16 MB
     * JPEG decodes to hundreds of MB otherwise). The decoded bitmap is bounded to the content box
     * ([RenderConfig.contentWidthPx] x [contentHeightPx], aspect preserved, only ever shrunk) and
     * rendered gray via a saturation-0 colour filter on the drawable — the e-ink panel has no
     * colour, and a filter avoids allocating a second bitmap just to drop the colour.
     */
    private fun appendImage(sb: SpannableStringBuilder, block: Block.Image, config: RenderConfig) {
        val bytes = block.bytes ?: return
        val drawable = decodeGrayscaleDrawable(bytes, config.contentWidthPx, config.contentHeightPx) ?: return
        val start = sb.length
        sb.append(IMAGE_PLACEHOLDER)
        sb.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BASELINE), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Reader baseline, regardless of config.publisherStyling: the image centers on its
        // own line rather than sitting flush left.
        sb.setSpan(
            AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
            start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun decodeGrayscaleDrawable(bytes: ByteArray, maxWidthPx: Int, maxHeightPx: Int): BitmapDrawable? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null // not decodable as an image — BitmapFactory reports this via -1, not a throw
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxWidthPx, maxHeightPx)
        }
        val sampled = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            // Scoped to the one allocation still sized by untrusted input (the source's real
            // dimensions, before sampling shrinks them). Should be unreachable given the sampling
            // above, but a hostile image must degrade to no image, not crash the chapter build.
            null
        } ?: return null

        // inSampleSize floors to a power of two >= the request, so the sampled bitmap can still
        // overshoot the content box by nearly 2x per axis. Trim it to the fit dimensions and drop
        // the oversized sampled bitmap — the same two-step the cover extractor uses — so what stays
        // resident in the chapter cache is the displayed size, not up to ~4x its pixel area. On a
        // memory-constrained e-ink device that difference matters across cached chapters.
        val (targetWidth, targetHeight) = fitWithin(sampled.width, sampled.height, maxWidthPx, maxHeightPx)
        val display = if (sampled.width == targetWidth && sampled.height == targetHeight) {
            sampled
        } else {
            Bitmap.createScaledBitmap(sampled, targetWidth, targetHeight, true)
                .also { if (it !== sampled) sampled.recycle() }
        }
        return BitmapDrawable(Resources.getSystem(), display).apply {
            setBounds(0, 0, display.width, display.height)
            // Saturation 0 renders the image gray; the panel is grayscale.
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
    }

    /** Fits `width` x `height` within `maxWidth` x `maxHeight`, aspect preserved, shrinking only. */
    private fun fitWithin(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height, 1f)
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /**
     * The maximal sub-ranges of `[from, to)` NOT covered by any of the [covered] ranges — the gaps
     * between publisher-sized heading runs, where the semantic heading scale still applies. [covered]
     * ranges are clamped to the heading and merged; the returned gaps are in order and non-empty.
     * With no covered ranges this is just `[from, to)` (the ordinary whole-heading scale).
     */
    private fun unsizedRanges(from: Int, to: Int, covered: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (covered.isEmpty()) return if (to > from) listOf(from to to) else emptyList()
        val merged = covered
            .map { (s, e) -> maxOf(s, from) to minOf(e, to) }
            .filter { (s, e) -> e > s }
            .sortedBy { it.first }
        val gaps = mutableListOf<Pair<Int, Int>>()
        var cursor = from
        for ((s, e) in merged) {
            if (s > cursor) gaps += cursor to s
            cursor = maxOf(cursor, e)
        }
        if (cursor < to) gaps += cursor to to
        return gaps
    }

    private fun appendStyled(sb: SpannableStringBuilder, styled: StyledText, config: RenderConfig) {
        val base = sb.length
        sb.append(styled.text)
        for (span in styled.spans) {
            val style = span.style
            val from = base + span.start
            val to = base + span.end
            fun set(what: Any) = sb.setSpan(what, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            // Emphasis is honored regardless of the toggle — it is the reader's baseline,
            // not publisher decoration. A run may now carry several fields at once (a Task 3
            // multi-property element emits one single-field span per property over the same
            // range), so each field is applied independently rather than as one exclusive arm.
            if (style.bold == true) set(AndroidStyleSpan(Typeface.BOLD))
            if (style.italic == true) set(AndroidStyleSpan(Typeface.ITALIC))
            if (style.monospace == true) set(TypefaceSpan("monospace"))

            // The remaining publisher decoration only applies when styling is on; when off
            // these fields are ignored entirely, leaving today's emphasis-only rendering.
            if (config.publisherStyling) {
                // Relative so the reader's base text size still governs — never absolute.
                style.sizeRatio?.let { set(RelativeSizeSpan(it)) }
                if (style.underline == true) set(UnderlineSpan())
                if (style.strikethrough == true) set(StrikethroughSpan())
                style.letterSpacingEm?.let { set(LetterSpacingSpan(it)) }
                style.grayLevel?.let {
                    val g = (it * 255f).roundToInt().coerceIn(0, 255)
                    set(ForegroundColorSpan(Color.rgb(g, g, g)))
                }
            }
        }
    }

    /**
     * Applies the publisher's block-level style over a block's text range [start, end).
     * A no-op when publisher styling is off (the reader's whole-chapter alignment,
     * line-spacing and margins govern instead). Never overrides the reader's text SIZE.
     *
     * Alignment: LEFT/RIGHT/CENTER map to an [AlignmentSpan]; JUSTIFY has no per-span
     * equivalent, so a publisher `text-align: justify` (and the `align == null` fallback
     * to the reader's default) is left to the whole-chapter justification set on the
     * StaticLayout in AndroidTextMeasurer. Consequence: whether a paragraph is justified
     * remains a whole-chapter setting; LEFT/RIGHT/CENTER overrides are honored per-paragraph.
     *
     * Line-height ([BlockStyle.lineHeightMultiplier]) is deliberately NOT applied — by owner
     * decision the reader's own whole-chapter line spacing always governs, so a publisher's
     * per-block line-height is never honored (unlike font-size, which scales relative to the
     * reader). It is still resolved into the model; the renderer simply ignores it.
     *
     * Margins ([BlockStyle.marginTopEm]/[marginBottomEm]) are deliberately NOT applied here:
     * inter-block spacing would mean a per-block, style-driven separator on top of the
     * reader's own two ([BLOCK_SEPARATOR] / [PARAGRAPH_JOIN]) — a third, publisher-sized
     * gap the hardware-tuned page-break offset logic in [build] has never been proven
     * against. [build]'s break-offset recording itself is fine with a variable-length
     * separator (it pins to the dynamic `sb.length`, not a fixed width) — margins are
     * deferred on their own terms, not because of that invariant. See the plan.
     */
    private fun applyBlockStyle(
        sb: SpannableStringBuilder,
        start: Int,
        end: Int,
        style: BlockStyle,
        config: RenderConfig,
    ) {
        if (!config.publisherStyling || end <= start) return
        fun set(what: Any) = sb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        style.align?.let { align ->
            when (align) {
                TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
                TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlign.JUSTIFY -> null // whole-chapter setting; see KDoc
            }?.let { set(AlignmentSpan.Standard(it)) }
        }
        style.textIndentEm?.let { indent ->
            set(LeadingMarginSpan.Standard((indent * config.textSizePx).roundToInt(), 0))
        }
        // lineHeightMultiplier: intentionally ignored — the reader's line spacing always wins.
        // marginTopEm / marginBottomEm: deferred — see KDoc.
    }
}
