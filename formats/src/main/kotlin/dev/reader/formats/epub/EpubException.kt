package dev.reader.formats.epub

/**
 * Typed failures. The reading UI names the reason to the user; a malformed book
 * must never crash the app.
 */
sealed class EpubException(message: String) : Exception(message) {
    /** The container is not an EPUB at all. */
    class NotAnEpub(message: String) : EpubException(message)

    /** It claims to be an EPUB but violates the spec in a way we cannot recover from. */
    class Malformed(message: String) : EpubException(message)

    /** Encrypted content. We do not support DRM and say so explicitly. */
    class DrmProtected(message: String) : EpubException(message)
}
