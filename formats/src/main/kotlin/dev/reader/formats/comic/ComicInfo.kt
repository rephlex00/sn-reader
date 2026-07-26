package dev.reader.formats.comic

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** The subset of the ComicInfo.xml schema Reader uses. Every field is optional. */
data class ComicInfo(
    val series: String?,
    val number: String?,
    val title: String?,
    val writer: String?,
    val rightToLeft: Boolean?,
    val blackAndWhite: Boolean?,
)

/** Parses ComicInfo.xml. Never throws: a malformed document yields an all-null [ComicInfo]. */
fun parseComicInfo(xml: String): ComicInfo {
    val doc = try {
        Jsoup.parse(xml, "", Parser.xmlParser())
    } catch (e: Exception) {
        return ComicInfo(null, null, null, null, null, null)
    }
    fun text(tag: String): String? =
        doc.selectFirst(tag)?.text()?.trim()?.takeIf { it.isNotEmpty() }

    val manga = text("Manga")
    val rtl = when {
        manga == null -> null
        manga.equals("YesAndRightToLeft", ignoreCase = true) -> true
        else -> false
    }
    val bw = text("BlackAndWhite")?.let { it.equals("Yes", ignoreCase = true) }
    return ComicInfo(
        series = text("Series"),
        number = text("Number"),
        title = text("Title"),
        writer = text("Writer"),
        rightToLeft = rtl,
        blackAndWhite = bw,
    )
}
