package dev.reader.formats.comic

/** Why a comic archive could not be opened. Mirrors EpubException: malformed comics never crash. */
sealed class ComicException(message: String) : Exception(message) {
    /** Not a zip and not a RAR — an unknown container we cannot read. */
    class NotAComic(message: String) : ComicException(message)

    /** A genuine RAR archive. Unsupported in v1; surfaced as a specific, honest message. */
    class RarUnsupported(message: String) : ComicException(message)

    /** A readable zip that holds no image entries — an empty book, not a page-zero book. */
    class NoImages(message: String) : ComicException(message)

    /** A container that opened but could not be read as a comic (corrupt, unreadable entries). */
    class Malformed(message: String) : ComicException(message)
}
