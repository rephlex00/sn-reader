package dev.reader.formats.epub

/**
 * A deliberately minimal CSS cascade.
 *
 * This is NOT a general CSS engine. It exists to answer exactly two semantic
 * questions when parsing calibre-style EPUBs that encode all emphasis and
 * structure as classes rather than semantic tags: "is this run italic/bold?"
 * and "is this short paragraph actually a heading?". Publisher CSS never
 * touches presentation in this reader - we only read enough of it to recover
 * authorial intent that would otherwise be silently dropped.
 *
 * Supported selectors: `tag`, `.class`, `tag.class`, and comma-separated
 * lists of the above. Everything else (descendant/child/attribute/pseudo
 * selectors, at-rules, etc.) is ignored without disturbing neighbouring
 * rules. Cascade order is: inline `style=` beats class rules (including
 * `tag.class` rules), which beat plain tag rules; among equals, the rule
 * that appears later in the stylesheet wins. This is intentionally not real
 * CSS specificity.
 *
 * [parse] never throws: malformed EPUB CSS (unterminated blocks, stray
 * braces, garbage) degrades to whatever prefix was parseable.
 */
class CssRules private constructor(
    private val tagRules: Map<String, Map<String, String>>,
    private val classRules: Map<String, Map<String, String>>,
    private val compoundRules: Map<String, Map<String, String>>,
) {

    /**
     * Resolves the declarations that apply to an element with the given
     * [tag] name, [classes], and optional [inlineStyle] attribute value.
     *
     * Precedence: inline style > class/tag.class rules > tag rules. Within
     * the class tier, classes are applied in the order given in [classes]
     * (a plain class rule immediately followed by its `tag.class`
     * counterpart, if any), so a later entry always overlays an earlier one
     * - consistent with "later wins" among equals.
     *
     * Returns an empty map (never null) when nothing matches, including for
     * [EMPTY].
     */
    fun declarationsFor(tag: String, classes: List<String>, inlineStyle: String?): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val lowerTag = tag.lowercase()

        tagRules[lowerTag]?.let { result.putAll(it) }
        for (className in classes) {
            val lowerClass = className.lowercase()
            classRules[lowerClass]?.let { result.putAll(it) }
            compoundRules["$lowerTag.$lowerClass"]?.let { result.putAll(it) }
        }
        inlineStyle?.let { result.putAll(parseDeclarations(it)) }

        return result
    }

    companion object {

        val EMPTY = CssRules(emptyMap(), emptyMap(), emptyMap())

        /**
         * Parses a stylesheet into a [CssRules]. Never throws: any block
         * that can't be understood (unterminated, unsupported selector, at-
         * rule) is skipped without affecting rules parsed before or after
         * it.
         */
        fun parse(css: String): CssRules {
            val tagRules = LinkedHashMap<String, MutableMap<String, String>>()
            val classRules = LinkedHashMap<String, MutableMap<String, String>>()
            val compoundRules = LinkedHashMap<String, MutableMap<String, String>>()

            val stripped = stripComments(css)
            var i = 0
            val n = stripped.length

            while (i < n) {
                val braceIndex = stripped.indexOf('{', i)
                if (braceIndex < 0) break // no more rules; whatever's left is a trailing fragment

                val selectorText = stripped.substring(i, braceIndex)

                val closeIndex = findMatchingClose(stripped, braceIndex)
                if (closeIndex < 0) break // unterminated block: stop, keep everything parsed so far

                val body = stripped.substring(braceIndex + 1, closeIndex)
                i = closeIndex + 1

                // Skip at-rules (e.g. `@media screen { ... }`, `@font-face { ... }`).
                if (selectorText.trimStart().startsWith("@")) continue

                val declarations = parseDeclarations(body)
                if (declarations.isEmpty()) continue

                for (rawSelector in selectorText.split(",")) {
                    val selector = rawSelector.trim().lowercase()
                    if (selector.isEmpty()) continue

                    when (val parsed = parseSimpleSelector(selector)) {
                        is SimpleSelector.Tag ->
                            tagRules.getOrPut(parsed.tag) { LinkedHashMap() }.putAll(declarations)
                        is SimpleSelector.Class ->
                            classRules.getOrPut(parsed.className) { LinkedHashMap() }.putAll(declarations)
                        is SimpleSelector.TagAndClass -> {
                            val key = "${parsed.tag}.${parsed.className}"
                            compoundRules.getOrPut(key) { LinkedHashMap() }.putAll(declarations)
                        }
                        null -> Unit // unsupported selector (descendant, pseudo, attribute, ...): ignore
                    }
                }
            }

            return CssRules(tagRules, classRules, compoundRules)
        }

        private fun stripComments(css: String): String {
            val sb = StringBuilder(css.length)
            var i = 0
            val n = css.length
            while (i < n) {
                if (i + 1 < n && css[i] == '/' && css[i + 1] == '*') {
                    val end = css.indexOf("*/", i + 2)
                    i = if (end < 0) n else end + 2
                } else {
                    sb.append(css[i])
                    i++
                }
            }
            return sb.toString()
        }

        /** Finds the `}` matching the `{` at [openIndex], or -1 if unterminated. */
        private fun findMatchingClose(css: String, openIndex: Int): Int {
            var depth = 1
            var i = openIndex + 1
            while (i < css.length) {
                when (css[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return -1
        }

        private val importantRegex = Regex("(?i)!\\s*important")

        private fun parseDeclarations(body: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (rawDecl in body.split(";")) {
                val decl = rawDecl.trim()
                if (decl.isEmpty()) continue

                val colonIndex = decl.indexOf(':')
                if (colonIndex < 0) continue // malformed declaration, skip it

                val prop = decl.substring(0, colonIndex).trim().lowercase()
                if (prop.isEmpty()) continue

                var value = decl.substring(colonIndex + 1).trim()
                value = value.replace(importantRegex, "").trim()
                value = value.lowercase()
                if (value.isEmpty()) continue

                result[prop] = value
            }
            return result
        }

        private sealed class SimpleSelector {
            data class Tag(val tag: String) : SimpleSelector()
            data class Class(val className: String) : SimpleSelector()
            data class TagAndClass(val tag: String, val className: String) : SimpleSelector()
        }

        private const val IDENTIFIER = "[a-z][a-z0-9_-]*"
        private val tagSelectorRegex = Regex("^($IDENTIFIER)$")
        private val classSelectorRegex = Regex("^\\.($IDENTIFIER)$")
        private val tagAndClassSelectorRegex = Regex("^($IDENTIFIER)\\.($IDENTIFIER)$")

        private fun parseSimpleSelector(selector: String): SimpleSelector? {
            tagAndClassSelectorRegex.matchEntire(selector)?.let {
                return SimpleSelector.TagAndClass(it.groupValues[1], it.groupValues[2])
            }
            classSelectorRegex.matchEntire(selector)?.let {
                return SimpleSelector.Class(it.groupValues[1])
            }
            tagSelectorRegex.matchEntire(selector)?.let {
                return SimpleSelector.Tag(it.groupValues[1])
            }
            return null
        }
    }
}
