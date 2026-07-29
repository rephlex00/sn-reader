package dev.reader.ui

/**
 * Cleans a raw stored title for display, everywhere a title is shown — the library grid and list,
 * the reader chrome, the comic chrome, and the panels' title slots.
 *
 * Two kinds of grime reach the stored value and both are left in the database on purpose (display-
 * time cleaning, the [formatAuthor] precedent — no migration, no re-index, and the raw value stays
 * available if a later rule wants it):
 *
 *  * Filenames. A comic with no ComicInfo.xml titles itself `file.nameWithoutExtension`
 *    ("Codex_Seraphinius_1983"), underscores and all; an unreadable book stores `file.name`.
 *  * Publisher metadata tics. `dc:title` values carry ASCII double-hyphen subtitle separators
 *    ("Artificial Condition--The Murderbot Diaries"), which the app's own typography would never
 *    set — it has a real dash for that.
 *
 * Pure and total: underscores become spaces, a double hyphen becomes an em dash set open, runs of
 * whitespace collapse, ends trim. Internal single hyphens, numbers and punctuation pass through
 * untouched — this repairs encoding, it does not editorialise.
 */
internal fun displayTitle(raw: String?): String =
    raw.orEmpty()
        .replace('_', ' ')
        .replace("--", " — ")
        .replace(Regex("\\s+"), " ")
        .trim()
