package dev.reader.engine

/**
 * The chapter title to show for a bookmark at [spineIndex]: the title of the last [TocEntry] at or
 * before that spine index. A bookmarked spine item without a TOC entry of its own resolves to the
 * chapter it sits in; null when the TOC is empty or has no entry at or before the index (a
 * degenerate book — the caller shows a neutral placeholder). Pure, so it is unit-testable without a
 * document.
 */
fun chapterTitleFor(toc: List<TocEntry>, spineIndex: Int): String? =
    toc.filter { it.spineIndex <= spineIndex }.maxByOrNull { it.spineIndex }?.title

/**
 * Whether [charOffset] falls on [page] — a half-open range `[startOffset, endOffset)`, matching how
 * [pageIndexFor] assigns an offset to a page (`charOffset < endOffset`). Used to decide, range-based
 * rather than exact-offset, whether the current page carries a bookmark — so the decision survives a
 * re-pagination that moved the page's boundaries.
 */
fun pageContainsOffset(page: Page, charOffset: Int): Boolean =
    charOffset >= page.startOffset && charOffset < page.endOffset
