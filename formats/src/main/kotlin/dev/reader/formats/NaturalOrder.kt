package dev.reader.formats

/**
 * Case-insensitive, numeric-aware string order: runs of digits compare by value, so `page9`
 * precedes `page10` rather than following it. Comic page ordering depends on this — lexicographic
 * order silently scrambles a whole book, which reads on the device as file corruption.
 */
val NATURAL_ORDER: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

private fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var i2 = i
            while (i2 < a.length && a[i2].isDigit()) i2++
            var j2 = j
            while (j2 < b.length && b[j2].isDigit()) j2++
            val na = a.substring(i, i2).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(j, j2).trimStart('0').ifEmpty { "0" }
            val cmp = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
            if (cmp != 0) return cmp
            i = i2
            j = j2
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return cmp
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
