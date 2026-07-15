package dev.reader.engine

/**
 * A stable position in a book. Survives font-size and margin changes, which is why
 * positions, bookmarks and highlights store this rather than a page number.
 */
data class Locator(val spineIndex: Int, val charOffset: Int) : Comparable<Locator> {
    init {
        require(spineIndex >= 0) { "spineIndex must be non-negative, was $spineIndex" }
        require(charOffset >= 0) { "charOffset must be non-negative, was $charOffset" }
    }

    override fun compareTo(other: Locator): Int =
        compareValuesBy(this, other, Locator::spineIndex, Locator::charOffset)
}

data class BookMetadata(
    val title: String,
    val author: String? = null,
    val language: String? = null,
    val coverHref: String? = null,
)

data class TocEntry(
    val title: String,
    val spineIndex: Int,
    val charOffset: Int = 0,
    val depth: Int = 0,
)
