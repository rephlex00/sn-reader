package dev.reader.formats.mobi

import dev.reader.formats.BookException

/** Why a MOBI could not be opened. Mirrors `EpubException` so callers can treat both alike. */
sealed class MobiException(message: String) : BookException(message) {

    /** The file is not a MOBI at all — wrong container, wrong Palm type, or unreadable. */
    class NotAMobi(message: String) : MobiException(message)

    /** It is a MOBI, but a damaged one: bad offsets, a truncated header, an impossible length. */
    class Malformed(message: String) : MobiException(message)

    /** It is a MOBI, but encrypted. Reader does not remove DRM; the book simply cannot be opened. */
    class DrmProtected(message: String) : MobiException(message)

    /**
     * It is a readable, unencrypted MOBI of a kind this reader does not implement — a KF8/AZW3
     * payload, or HUFF/CDIC compression. Distinct from [Malformed] because nothing is wrong with
     * the file: the ceiling is Reader's, and the message says so rather than blaming the book.
     */
    class UnsupportedVariant(message: String) : MobiException(message)
}
