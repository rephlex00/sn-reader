package dev.reader.ui

/**
 * Whether the page turn that brings the reader to [turnsSinceRefresh] turns since the last full
 * refresh should itself be a full clean refresh. [faster] false → always (every turn is a full
 * refresh; [everyN] is ignored). [faster] true → only on the [everyN]th turn. [everyN] is coerced to
 * at least 1 so a degenerate 0 means "every turn" rather than never. Pure — unit-tested without a
 * device. The caller increments [turnsSinceRefresh] before calling and resets it to 0 on a true.
 */
fun shouldFullRefresh(faster: Boolean, everyN: Int, turnsSinceRefresh: Int): Boolean =
    !faster || turnsSinceRefresh >= everyN.coerceAtLeast(1)
