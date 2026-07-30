package dev.reader.formats

/**
 * A book could not be opened, whatever format it claimed to be.
 *
 * The reader catches this rather than each format's own exception. Every reason a book fails —
 * not that format at all, damaged, encrypted, a variant we do not read — is a reason the reader
 * handles identically: name it to the person holding the device and go back to the shelf. Which
 * parser produced it is an implementation detail, and making the reader catch two unrelated
 * sealed hierarchies would leak exactly the format distinction that is supposed to be invisible.
 *
 * Each format keeps its own subclasses (`EpubException`, `MobiException`) so a `when` inside that
 * format's own code still gets exhaustiveness.
 */
abstract class BookException(message: String) : Exception(message)
