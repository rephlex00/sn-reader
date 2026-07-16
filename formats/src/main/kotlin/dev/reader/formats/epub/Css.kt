package dev.reader.formats.epub

/**
 * The context an element contributes to a cascade lookup: its [tag] name, the
 * [classes] on its `class` attribute, its optional [id], and the raw text of
 * its inline `style` attribute. A resolution walks a *chain* of these from the
 * document root down to the element being resolved, so descendant/child
 * combinators and inheritance can see the ancestor context.
 */
data class ElementCtx(
    val tag: String,
    val classes: List<String> = emptyList(),
    val id: String? = null,
    val inlineStyle: String? = null,
)

/**
 * The resolved style of one element after running the cascade over its whole
 * ancestor [chain][CssRules.resolve]: specificity-ranked matching rules plus the
 * inline `style` attribute, with inheritable properties flowing down from
 * ancestors.
 *
 * Property values are the **raw declared CSS strings** (lowercased, with
 * `!important` stripped and shorthands expanded) — this class deliberately does
 * *not* interpret units, colours, keywords, etc. That is the consumer's job
 * (Plan 3 Task 3, which maps these into the render model). [get]/[asMap] expose
 * that flat property map.
 *
 * The one value this class resolves numerically is [fontSizeRatio]: relative
 * font-sizes (`em`/`%`) are multiplicative against the parent's size and only
 * the cascade knows the chain, so they are **composed here**. Absolute sizes
 * (`px`/`pt`) are left for Task 3 to resolve against the document baseline — see
 * [fontSizeRatio]'s contract.
 */
class ComputedStyle internal constructor(
    private val properties: Map<String, String>,
    /**
     * The element's font-size as a ratio against the document baseline, obtained
     * by composing the relative (`em`/`rem`/`%`/size-keyword) font-sizes down the
     * ancestor chain — e.g. `body { 1.2em }` then `p { 0.5em }` yields `0.6`.
     *
     * `null` means the ratio is unavailable and the raw `font-size` value (if any)
     * must be interpreted instead. That happens when: no element in the chain set a
     * font-size at all (publisher said nothing — use the reader's size); or a
     * contributing font-size uses an absolute unit (`px`/`pt`) or an unparseable
     * value, which breaks composition. In the absolute case the raw winning value
     * is still present as `this["font-size"]` for Task 3 to resolve against the
     * document baseline. Consumers should prefer this ratio when it is non-null and
     * fall back to parsing `this["font-size"]` when it is null.
     */
    val fontSizeRatio: Float?,
) : Map<String, String> by properties {
    /** The full flat property map (raw declared values). Never null. */
    fun asMap(): Map<String, String> = properties

    override fun toString(): String = "ComputedStyle(properties=$properties, fontSizeRatio=$fontSizeRatio)"
}

/**
 * A deliberately minimal CSS cascade — enough to answer "what is this element's
 * computed style?" for a reflowable EPUB, and nothing more. It is NOT a general
 * CSS engine: layout properties, at-rule *evaluation*, sibling/attribute/pseudo
 * selectors and `@media` matching are all out of scope.
 *
 * **Selectors understood:** simple `tag` / `.class` / `#id` and any compound of
 * them (`p.foo`, `a#x.bar`), joined by descendant (` `) or child (`>`)
 * combinators (`div p`, `.chapter > em`). A comma-separated list is split into
 * independent selectors. Everything else — sibling combinators (`+`/`~`),
 * attribute selectors (`[…]`), pseudo-classes/elements (`:first-child`, `::before`)
 * and at-rules (`@media`, `@font-face`) — is ignored **without dropping the rules
 * around it**. A selector carrying an unsupported feature (e.g. `p:first-child`)
 * is dropped whole, but its neighbours in the same stylesheet are unaffected.
 *
 * **Cascade:** real CSS specificity, `(id, class, type)` compared left to right;
 * the inline `style` attribute beats every rule; ties are broken by stylesheet
 * source order (later wins). Because id selectors *are* matched, the id column is
 * honored even though reflowable EPUB stylesheets rarely use them.
 *
 * **Inheritance:** [INHERITED_PROPERTIES] flow down the ancestor chain; the rest
 * apply only to the element that declares them. `font-size` additionally composes
 * (see [ComputedStyle.fontSizeRatio]).
 *
 * **Shorthands** are expanded at parse time for exactly what this reader honors:
 * `margin` → `margin-top`/`margin-bottom` (left/right ignored), and the
 * `font-size`/`line-height` slice of `font` (the rest of `font` is ignored, not
 * half-parsed).
 *
 * [parse] never throws: malformed EPUB CSS (unterminated blocks, stray braces,
 * garbage) degrades to whatever prefix was parseable. [resolve] never throws
 * either — an empty chain resolves to an empty style.
 */
class CssRules private constructor(
    // Rules are pre-indexed by the "hook" of their rightmost (subject) compound
    // selector — its id, else one of its classes, else its tag — so resolving an
    // element only visits rules that could plausibly match it, not the whole
    // stylesheet. A rule lives in exactly one bucket; a lookup unions the buckets
    // the element could hit (its id, each of its classes, its tag, and the
    // universal bucket) and then full-matches each candidate.
    private val byId: Map<String, List<Rule>>,
    private val byClass: Map<String, List<Rule>>,
    private val byTag: Map<String, List<Rule>>,
    private val universal: List<Rule>,
) {

    /**
     * Resolves the [ComputedStyle] of the last element in [chain], where [chain]
     * runs root → element (so `chain.last()` is the element being resolved and the
     * earlier entries are its ancestors, nearest last). Combinator matching and
     * inheritance both read this chain.
     *
     * O(depth × candidate-rules): each element in the chain is matched once against
     * the rules its own hooks could reach, and inheritance is a single downward pass.
     */
    fun resolve(chain: List<ElementCtx>): ComputedStyle {
        if (chain.isEmpty()) return ComputedStyle(emptyMap(), null)

        // Inheritable properties carried from the parent, updated at each level.
        var inherited = emptyMap<String, String>()
        // Composed font-size ratio vs the document baseline (1.0), and whether any
        // element so far actually set a font-size. `ratio == null` means composition
        // broke on an absolute/unparseable size somewhere above.
        var ratio: Float? = 1.0f
        var anyFontSize = false

        var own: Map<String, String> = emptyMap()
        for (i in chain.indices) {
            own = winningDeclarations(chain, i)

            // font-size composition: the element's OWN winning font-size composes
            // onto the parent's ratio; an unset size inherits the parent's ratio.
            own["font-size"]?.let { fs ->
                anyFontSize = true
                ratio = composeFontSize(ratio, fs)
            }

            // The level's computed inheritable props = inherited-from-parent, then
            // this element's own declarations override (both inheritable and not,
            // but only inheritable ones are carried onward).
            if (i < chain.lastIndex) {
                val nextInherited = LinkedHashMap<String, String>()
                for (key in INHERITED_PROPERTIES) inherited[key]?.let { nextInherited[key] = it }
                for ((k, v) in own) if (k in INHERITED_PROPERTIES) nextInherited[k] = v
                inherited = nextInherited
            }
        }

        // Final element: inherited props from ancestors, then its own overrides all.
        val result = LinkedHashMap<String, String>()
        for (key in INHERITED_PROPERTIES) inherited[key]?.let { result[key] = it }
        result.putAll(own)

        return ComputedStyle(result, if (anyFontSize) ratio else null)
    }

    /**
     * Resolves the declarations that apply to a single element with the given
     * [tag], [classes] and optional [inlineStyle] — a one-element chain, so no
     * inheritance from ancestors. Kept for callers that reason about one element in
     * isolation (page-break requests, emphasis mining). Precedence is the full
     * cascade: inline style > higher specificity > later source order.
     *
     * Returns an empty map (never null) when nothing matches, including for [EMPTY].
     */
    fun declarationsFor(tag: String, classes: List<String>, inlineStyle: String?): Map<String, String> =
        resolve(listOf(ElementCtx(tag, classes, inlineStyle = inlineStyle))).asMap()

    /**
     * The winning raw declarations for the element at [index] in [chain]: every
     * matching rule sorted by (specificity, source order) ascending and layered so
     * later/stronger wins, then the inline `style` attribute layered last.
     */
    private fun winningDeclarations(chain: List<ElementCtx>, index: Int): Map<String, String> {
        val el = chain[index]
        val lowerTag = el.tag.lowercase()
        val lowerId = el.id?.lowercase()
        val lowerClasses = el.classes.map { it.lowercase() }

        // Gather candidate rules from every bucket this element could hit.
        val candidates = ArrayList<Rule>()
        lowerId?.let { byId[it]?.let(candidates::addAll) }
        for (c in lowerClasses) byClass[c]?.let(candidates::addAll)
        byTag[lowerTag]?.let(candidates::addAll)
        candidates.addAll(universal)

        val matched = candidates.filter { it.matches(chain, index) }
        // Ascending by specificity then source order, so a later putAll wins.
        val sorted = matched.sortedWith(compareBy({ it.specificity }, { it.order }))

        val result = LinkedHashMap<String, String>()
        for (rule in sorted) result.putAll(rule.declarations)
        el.inlineStyle?.let { result.putAll(parseDeclarations(it)) }
        return result
    }

    private fun composeFontSize(parentRatio: Float?, rawValue: String): Float? {
        val v = rawValue.trim()
        // em / % are relative to the PARENT's size → multiply.
        emRegex.matchEntire(v)?.let { m ->
            val f = m.groupValues[1].toFloatOrNull() ?: return null
            return parentRatio?.let { it * f }
        }
        percentRegex.matchEntire(v)?.let { m ->
            val f = m.groupValues[1].toFloatOrNull() ?: return null
            return parentRatio?.let { it * (f / 100f) }
        }
        // rem and the xx-small..xx-large keywords are relative to the ROOT/baseline → reset
        // composition. "larger"/"smaller" are the exception: real CSS makes them single steps
        // relative to the PARENT's computed size, so they multiply like em does (a "larger"
        // under a 2em body is ~2.4, not 1.2). With no parent ratio they step from the baseline.
        remRegex.matchEntire(v)?.let { m -> return m.groupValues[1].toFloatOrNull() }
        when (v) {
            "larger" -> return (parentRatio ?: 1f) * 1.2f
            "smaller" -> return (parentRatio ?: 1f) * 0.83f
        }
        keywordSizeRatios[v]?.let { return it }
        // px / pt / anything else: absolute or unknown → composition can't continue.
        return null
    }

    // --- Selector model -----------------------------------------------------

    /** Specificity as the CSS `(id, class, type)` triple, compared left to right. */
    private data class Specificity(val id: Int, val cls: Int, val type: Int) : Comparable<Specificity> {
        override fun compareTo(other: Specificity): Int {
            if (id != other.id) return id.compareTo(other.id)
            if (cls != other.cls) return cls.compareTo(other.cls)
            return type.compareTo(other.type)
        }
    }

    /** A simple compound selector: an optional tag, optional id, and any classes. */
    private class Compound(val tag: String?, val id: String?, val classes: List<String>) {
        fun matches(el: ElementCtx): Boolean {
            if (tag != null && tag != el.tag.lowercase()) return false
            if (id != null && id != el.id?.lowercase()) return false
            if (classes.isNotEmpty()) {
                val have = el.classes.map { it.lowercase() }
                if (!have.containsAll(classes)) return false
            }
            return true
        }
    }

    private enum class Combinator { DESCENDANT, CHILD }

    private class AncestorStep(val combinator: Combinator, val selector: Compound)

    private class Rule(
        val subject: Compound,
        /** Ancestor constraints, ordered nearest-first (right-to-left from [subject]). */
        val steps: List<AncestorStep>,
        val declarations: Map<String, String>,
        val specificity: Specificity,
        val order: Int,
    ) {
        fun matches(chain: List<ElementCtx>, index: Int): Boolean {
            if (!subject.matches(chain[index])) return false
            return matchSteps(0, index, chain)
        }

        // Standard right-to-left combinator match with descendant backtracking.
        private fun matchSteps(stepIdx: Int, elemIdx: Int, chain: List<ElementCtx>): Boolean {
            if (stepIdx >= steps.size) return true
            val step = steps[stepIdx]
            return when (step.combinator) {
                Combinator.CHILD -> {
                    val parent = elemIdx - 1
                    parent >= 0 && step.selector.matches(chain[parent]) &&
                        matchSteps(stepIdx + 1, parent, chain)
                }
                Combinator.DESCENDANT -> {
                    var anc = elemIdx - 1
                    while (anc >= 0) {
                        if (step.selector.matches(chain[anc]) && matchSteps(stepIdx + 1, anc, chain)) {
                            return true
                        }
                        anc--
                    }
                    false
                }
            }
        }
    }

    companion object {

        /**
         * Properties that inherit from parent to child. `font-size` is here too, but
         * additionally composes numerically — see [ComputedStyle.fontSizeRatio].
         */
        val INHERITED_PROPERTIES: Set<String> = setOf(
            "font-family", "font-size", "font-style", "font-weight", "line-height",
            "text-align", "text-indent", "color", "letter-spacing",
        )

        val EMPTY = CssRules(emptyMap(), emptyMap(), emptyMap(), emptyList())

        /**
         * Parses a stylesheet into a [CssRules]. Never throws: any block that can't be
         * understood (unterminated, unsupported selector, at-rule) is skipped without
         * affecting rules parsed before or after it.
         */
        fun parse(css: String): CssRules {
            val byId = LinkedHashMap<String, MutableList<Rule>>()
            val byClass = LinkedHashMap<String, MutableList<Rule>>()
            val byTag = LinkedHashMap<String, MutableList<Rule>>()
            val universal = mutableListOf<Rule>()
            var nextRuleIndex = 0

            val stripped = stripComments(css)
            var i = 0
            val n = stripped.length

            while (i < n) {
                val braceIndex = stripped.indexOf('{', i)
                if (braceIndex < 0) break // no more rules; whatever's left is a trailing fragment

                // Resync past any stray `}` left over from malformed CSS earlier in the
                // stylesheet, so it doesn't corrupt this selector's text (and silently
                // drop this rule).
                val rawSelectorText = stripped.substring(i, braceIndex)
                val staleClose = rawSelectorText.lastIndexOf('}')
                val selectorText = if (staleClose >= 0) rawSelectorText.substring(staleClose + 1) else rawSelectorText

                val closeIndex = findMatchingClose(stripped, braceIndex)
                if (closeIndex < 0) break // unterminated block: stop, keep everything parsed so far

                val body = stripped.substring(braceIndex + 1, closeIndex)
                i = closeIndex + 1

                // Skip at-rules (e.g. `@media screen { ... }`, `@font-face { ... }`).
                if (selectorText.trimStart().startsWith("@")) continue

                val declarations = parseDeclarations(body)
                if (declarations.isEmpty()) continue

                val order = nextRuleIndex++

                for (rawSelector in selectorText.split(",")) {
                    val selector = rawSelector.trim().lowercase()
                    if (selector.isEmpty()) continue

                    val rule = parseComplexSelector(selector, declarations, order) ?: continue
                    // File the rule under the most selective hook of its subject.
                    val subject = rule.subject
                    when {
                        subject.id != null ->
                            byId.getOrPut(subject.id) { mutableListOf() }.add(rule)
                        subject.classes.isNotEmpty() ->
                            byClass.getOrPut(subject.classes.first()) { mutableListOf() }.add(rule)
                        subject.tag != null ->
                            byTag.getOrPut(subject.tag) { mutableListOf() }.add(rule)
                        else -> universal.add(rule)
                    }
                }
            }

            return CssRules(byId, byClass, byTag, universal)
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

                // Expand the shorthands we honor in place, so a later explicit longhand
                // in the same block still overrides. Everything else passes through.
                when (prop) {
                    "margin" -> expandMargin(value)?.let { result.putAll(it) }
                    "font" -> expandFont(value)?.let { result.putAll(it) }
                    else -> result[prop] = value
                }
            }
            return result
        }

        /**
         * `margin: a [b [c [d]]]` → `margin-top`/`margin-bottom` only (left/right are
         * deliberately not honored by this reader). Returns null when there are no
         * tokens to expand.
         */
        private fun expandMargin(value: String): Map<String, String>? {
            val tokens = value.split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val top = tokens[0]
            // 1 value → all sides; 2 → v/h; 3 → t/h/b; 4 → t/r/b/l. Bottom is index 2
            // when ≥3 tokens, else it mirrors top.
            val bottom = if (tokens.size >= 3) tokens[2] else tokens[0]
            return linkedMapOf("margin-top" to top, "margin-bottom" to bottom)
        }

        /**
         * The `font` shorthand, honored only for its `font-size[/line-height]` slice —
         * the rest (style/variant/weight/stretch/family) is ignored rather than
         * half-parsed. Returns null when no size token can be confidently identified
         * (e.g. a system font like `font: menu`).
         */
        private fun expandFont(value: String): Map<String, String>? {
            for (token in value.split(WHITESPACE)) {
                if (token.isEmpty()) continue
                val slash = token.indexOf('/')
                val sizePart = if (slash >= 0) token.substring(0, slash) else token
                if (!isSizeToken(sizePart)) continue
                val out = LinkedHashMap<String, String>()
                out["font-size"] = sizePart
                if (slash >= 0) {
                    val lh = token.substring(slash + 1)
                    if (lh.isNotEmpty()) out["line-height"] = lh
                }
                return out
            }
            return null
        }

        /** A `font`-shorthand token that reads as a font-size: a length/%/size-keyword. */
        private fun isSizeToken(token: String): Boolean =
            fontSizeUnitRegex.matches(token) || token in keywordSizeRatios

        /**
         * Parses one complex selector (`div > p.foo em`) into a [Rule], or null when it
         * carries any feature this cascade does not support (sibling combinators,
         * attribute selectors, pseudo-classes/elements) — the whole selector is dropped,
         * but its comma-list siblings and stylesheet neighbours are unaffected.
         */
        private fun parseComplexSelector(selector: String, declarations: Map<String, String>, order: Int): Rule? {
            // Sibling combinators are unsupported; reject early so we never treat `~`/`+`
            // as part of a compound.
            if (selector.contains('+') || selector.contains('~')) return null

            // Tokenize into compounds and `>` combinators. Whitespace = descendant.
            val tokens = tokenizeSelector(selector) ?: return null
            if (tokens.isEmpty()) return null

            // tokens is a list alternating Compound-text and ">"; the last must be a
            // compound (the subject). Build steps right-to-left.
            val compounds = mutableListOf<Compound>()
            val combinators = mutableListOf<Combinator>() // combinator to the LEFT of compounds[k], k>=1
            var expectCompound = true
            for (tok in tokens) {
                if (tok == ">") {
                    if (expectCompound) return null // `> p` or `a > > b`
                    combinators.add(Combinator.CHILD)
                    expectCompound = true
                } else {
                    val compound = parseCompound(tok) ?: return null
                    if (!expectCompound) {
                        // Two compounds with only whitespace between them: descendant.
                        combinators.add(Combinator.DESCENDANT)
                    }
                    compounds.add(compound)
                    expectCompound = false
                }
            }
            if (expectCompound || compounds.isEmpty()) return null // trailing combinator

            val subject = compounds.last()
            // steps nearest-first: walk from the subject leftward.
            val steps = mutableListOf<AncestorStep>()
            for (k in compounds.lastIndex downTo 1) {
                steps.add(AncestorStep(combinators[k - 1], compounds[k - 1]))
            }

            var idCount = 0
            var clsCount = 0
            var typeCount = 0
            for (c in compounds) {
                if (c.id != null) idCount++
                clsCount += c.classes.size
                if (c.tag != null) typeCount++
            }
            return Rule(subject, steps, declarations, Specificity(idCount, clsCount, typeCount), order)
        }

        /**
         * Splits a selector into compound tokens and literal `>` combinators. Returns
         * null if a stray brace or other structural garbage is present. Whitespace runs
         * become token boundaries (descendant); `>` is emitted as its own token.
         */
        private fun tokenizeSelector(selector: String): List<String>? {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (ch in selector) {
                when {
                    ch == '>' -> {
                        if (sb.isNotBlank()) out.add(sb.toString())
                        sb.setLength(0)
                        out.add(">")
                    }
                    ch.isWhitespace() -> {
                        if (sb.isNotBlank()) out.add(sb.toString())
                        sb.setLength(0)
                    }
                    else -> sb.append(ch)
                }
            }
            if (sb.isNotBlank()) out.add(sb.toString())
            return out
        }

        /**
         * Parses one compound selector (`p`, `.foo`, `#x`, `a#x.bar`, `*`) into a
         * [Compound], or null if it contains any unsupported feature (attribute
         * selector, pseudo-class/element) or is otherwise malformed.
         */
        private fun parseCompound(token: String): Compound? {
            if (token.isEmpty()) return null
            // Anything with an attribute selector or pseudo is unsupported → drop.
            if (token.contains('[') || token.contains(']') || token.contains(':')) return null
            if (token == "*") return Compound(null, null, emptyList())

            var tag: String? = null
            var id: String? = null
            val classes = mutableListOf<String>()

            var i = 0
            val n = token.length
            // A leading identifier (no `.`/`#` prefix) is the type/tag.
            if (token[0] != '.' && token[0] != '#') {
                val end = readIdentifier(token, i) ?: return null
                tag = token.substring(i, end)
                i = end
            }
            while (i < n) {
                val marker = token[i]
                if (marker != '.' && marker != '#') return null
                val end = readIdentifier(token, i + 1) ?: return null
                val name = token.substring(i + 1, end)
                if (marker == '.') classes.add(name) else {
                    if (id != null) return null // two ids in one compound: malformed
                    id = name
                }
                i = end
            }
            if (tag == null && id == null && classes.isEmpty()) return null
            return Compound(tag, id, classes)
        }

        // A CSS identifier: can't start with a digit, but may start with `_`/`-` or a
        // non-ASCII letter (already lowercased upstream, so no A-Z range needed).
        private fun readIdentifier(s: String, start: Int): Int? {
            if (start >= s.length) return null
            val first = s[start]
            if (!(first == '_' || first == '-' || first.isLetter())) return null
            var i = start + 1
            while (i < s.length) {
                val c = s[i]
                if (c == '_' || c == '-' || c.isLetterOrDigit()) i++ else break
            }
            return i
        }

        private val WHITESPACE = Regex("\\s+")

        // A `font`-shorthand size token: a number with a length/percentage unit.
        private val fontSizeUnitRegex = Regex("^-?\\d*\\.?\\d+(px|pt|em|rem|ex|ch|vh|vw|vmin|vmax|%)$")

        private val emRegex = Regex("^(-?\\d*\\.?\\d+)em$")
        private val remRegex = Regex("^(-?\\d*\\.?\\d+)rem$")
        private val percentRegex = Regex("^(-?\\d*\\.?\\d+)%$")

        // Conventional CSS absolute-size-keyword ratios (CSS3 §Absolute Size Keywords),
        // relative to "medium" = 1. "larger"/"smaller" appear here only so shorthand/size-token
        // DETECTION recognizes them; composeFontSize intercepts both BEFORE this map is consulted
        // and multiplies the parent ratio instead, because real CSS makes them parent-relative
        // steps, not baseline resets. Their values below are the no-parent fallback step sizes.
        private val keywordSizeRatios = mapOf(
            "xx-small" to 0.6f,
            "x-small" to 0.75f,
            "small" to 0.889f,
            "medium" to 1.0f,
            "large" to 1.2f,
            "x-large" to 1.5f,
            "xx-large" to 2.0f,
            "larger" to 1.2f,
            "smaller" to 0.83f,
        )
    }
}
