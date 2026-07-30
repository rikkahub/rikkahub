package me.rerere.highlight.core

import java.util.regex.Pattern

internal enum class MatchType { BEGIN, END, ILLEGAL }

/** Metadata registered alongside a terminator regex, mirroring the `opts` object upstream. */
internal class RuleInfo(
    val rule: Mode?,
    val type: MatchType,
) {
    var position: Int = 0

    fun copy(): RuleInfo = RuleInfo(rule, type)
}

/** The result of a terminator scan: a regex match enriched with the rule that produced it. */
internal class MultiMatch(
    override val index: Int,
    override val input: String,
    private val groups: List<String?>,
    val rule: Mode?,
    val type: MatchType,
    val position: Int,
) : MatchData {
    override fun get(group: Int): String? = groups.getOrNull(group)

    /** Number of capture groups still addressable through [get], excluding the full match. */
    val groupCount: Int get() = groups.size - 1
}

/**
 * Stores multiple regular expressions and searches for all of them simultaneously, returning the
 * first match. Mirrors `MultiRegex` in `highlight.js`.
 *
 * Every expression is wrapped in its own capture group and joined with `|`; whichever group carries
 * content identifies the rule that matched.
 */
private class MultiRegex(
    private val caseInsensitive: Boolean,
    private val unicodeRegex: Boolean,
) {
    private val matchIndexes = HashMap<Int, RuleInfo>()
    private val regexes = mutableListOf<Pair<RuleInfo, String>>()
    private var matchAt = 1
    private var position = 0
    private var matcherRe: Pattern? = null

    var lastIndex: Int = 0

    fun addRule(re: String, info: RuleInfo) {
        info.position = position++
        matchIndexes[matchAt] = info
        regexes += info to re
        matchAt += countMatchGroups(re) + 1
    }

    fun compile() {
        if (regexes.isEmpty()) return
        matcherRe = compilePattern(
            source = rewriteBackreferences(regexes.map { it.second }, joinWith = "|"),
            caseInsensitive = caseInsensitive,
            unicode = unicodeRegex,
        )
        lastIndex = 0
    }

    fun exec(input: String): MultiMatch? {
        val pattern = matcherRe ?: return null
        if (lastIndex > input.length) return null

        val matcher = pattern.matcher(input)
        if (!matcher.find(lastIndex)) return null

        // Find the first group that took part in the match: it identifies the originating rule.
        var group = 1
        while (group <= matcher.groupCount() && matcher.group(group) == null) group++
        if (group > matcher.groupCount()) return null

        val info = matchIndexes[group] ?: return null
        // Trim the leading groups belonging to the other rules, so index 0 is the rule's own match.
        val groups = (group..matcher.groupCount()).map { matcher.group(it) }

        return MultiMatch(
            index = matcher.start(),
            input = input,
            groups = groups,
            rule = info.rule,
            type = info.type,
            position = info.position,
        )
    }
}

/**
 * Creates [MultiRegex] instances on demand so that scanning can resume at the same position while
 * skipping the rules that were already tried. Mirrors `ResumableMultiRegex` in `highlight.js`.
 *
 * Unlike upstream, every generated matcher receives its own copy of the rule metadata: upstream
 * shares one metadata object across matchers, so building a second matcher renumbers the positions
 * the first one still refers to.
 */
internal class ResumableMultiRegex(
    private val caseInsensitive: Boolean,
    private val unicodeRegex: Boolean,
) {
    private val rules = mutableListOf<Pair<String, RuleInfo>>()
    private val multiRegexes = HashMap<Int, MultiRegex>()
    private var count = 0

    var lastIndex: Int = 0
    var regexIndex: Int = 0

    fun addRule(re: String, info: RuleInfo) {
        rules += re to info
        if (info.type == MatchType.BEGIN) count++
    }

    fun considerAll() {
        regexIndex = 0
    }

    private fun resumingScanAtSamePosition(): Boolean = regexIndex != 0

    private fun getMatcher(index: Int): MultiRegex = multiRegexes.getOrPut(index) {
        MultiRegex(caseInsensitive, unicodeRegex).apply {
            rules.drop(index).forEach { (re, info) -> addRule(re, info.copy()) }
            compile()
        }
    }

    fun exec(input: String): MultiMatch? {
        val matcher = getMatcher(regexIndex)
        matcher.lastIndex = lastIndex
        var result = matcher.exec(input)

        // There is no way to say "resume at this position but skip only the rule we just ignored";
        // resuming skips every earlier rule too. So when resuming we also run the full matcher one
        // character further along and keep whichever match comes first.
        if (resumingScanAtSamePosition() && !(result != null && result.index == lastIndex)) {
            val fullMatcher = getMatcher(0)
            fullMatcher.lastIndex = lastIndex + 1
            result = fullMatcher.exec(input)
        }

        if (result != null) {
            regexIndex += result.position + 1
            if (regexIndex == count) considerAll()
        }
        return result
    }
}
