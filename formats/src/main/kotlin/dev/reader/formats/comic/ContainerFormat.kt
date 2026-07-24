package dev.reader.formats.comic

import java.io.File

/** What a comic file actually is, decided by its leading bytes — never by its extension. */
enum class ContainerFormat { ZIP, RAR, UNKNOWN }

private val ZIP_SIG = byteArrayOf(0x50, 0x4B, 0x03, 0x04)            // "PK\x03\x04"
private val RAR_SIG = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) // "Rar!\x1A\x07" (RAR4 & RAR5)

private fun ByteArray.startsWith(sig: ByteArray): Boolean {
    if (size < sig.size) return false
    for (i in sig.indices) if (this[i] != sig[i]) return false
    return true
}

fun detectContainer(header: ByteArray): ContainerFormat = when {
    header.startsWith(ZIP_SIG) -> ContainerFormat.ZIP
    header.startsWith(RAR_SIG) -> ContainerFormat.RAR
    else -> ContainerFormat.UNKNOWN
}

fun detectContainer(file: File): ContainerFormat {
    val header = ByteArray(8)
    val read = file.inputStream().use { it.read(header) }
    return if (read < 0) ContainerFormat.UNKNOWN
    else detectContainer(header.copyOf(read.coerceAtLeast(0)))
}
