package me.rerere.highlight.core

import java.util.regex.Pattern

/**
 * Turns a [Language] into its compiled form, mirroring `compileLanguage()` in `highlight.js`.
 *
 * Compilation mutates the modes in place — just like upstream — so every [Language] must be built
 * from freshly constructed modes. Modes shared between languages are marked with [frozen] and are
 * copied before being touched.
 */
internal class ModeCompiler(private val language: Language) {

    fun compile(): Mode {
        require(language.root.contains.none { it === Mode.SELF }) {
            "contains `self` is not supported at the top-level of a language"
        }
        return compileMode(language.root, parent = null)
    }

    private fun langRe(source: String): Pattern = compilePattern(
        source = source,
        caseInsensitive = language.caseInsensitive,
        unicode = language.unicodeRegex,
    )

    private fun compileMode(mode: Mode, parent: Mode?): Mode {
        if (mode.isCompiled) return mode

        compileMatch(mode)
        multiClass(mode)
        beforeMatchExt(mode)

        mode.beforeBegin = null
        beginKeywordsExt(mode, parent)
        compileIllegal(mode)
        compileRelevance(mode)

        // Set before descending into `contains` so that `SELF` can resolve back to this mode.
        mode.isCompiled = true

        val keywords = mode.keywords
        mode.keywordPatternRe = langRe(keywords?.pattern ?: DEFAULT_KEYWORD_PATTERN)
        if (keywords != null) {
            mode.compiledKeywords = compileKeywords(keywords, language.caseInsensitive)
        }

        if (parent != null) {
            if (mode.begin == null) mode.begin = EMPTY_MATCH
            mode.beginRe = langRe(mode.begin!!)
            if (mode.end == null && !mode.endsWithParent) mode.end = EMPTY_MATCH
            mode.end?.let { mode.endRe = langRe(it) }
            mode.terminatorEnd = mode.end.orEmpty()
            if (mode.endsWithParent && !parent.terminatorEnd.isNullOrEmpty()) {
                mode.terminatorEnd =
                    mode.terminatorEnd + (if (mode.end != null) "|" else "") + parent.terminatorEnd
            }
        }
        mode.illegal?.let { mode.illegalRe = langRe(it) }

        mode.contains = mode.contains.flatMap { contained ->
            expandOrCloneMode(if (contained === Mode.SELF) mode else contained)
        }
        mode.contains.forEach { compileMode(it, mode) }

        mode.starts?.let { starts ->
            val target = if (starts.frozen) starts.copy().also { mode.starts = it } else starts
            compileMode(target, parent)
        }

        mode.matcher = buildModeRegex(mode)
        return mode
    }

    private fun buildModeRegex(mode: Mode): ResumableMultiRegex {
        val matcher = ResumableMultiRegex(language.caseInsensitive, language.unicodeRegex)
        mode.contains.forEach { contained ->
            matcher.addRule(contained.begin!!, RuleInfo(contained, MatchType.BEGIN))
        }
        if (!mode.terminatorEnd.isNullOrEmpty()) {
            matcher.addRule(mode.terminatorEnd!!, RuleInfo(null, MatchType.END))
        }
        mode.illegal?.let { matcher.addRule(it, RuleInfo(null, MatchType.ILLEGAL)) }
        return matcher
    }

    // ---- compiler extensions ----------------------------------------------------------------

    /** `match` is sugar for a mode that only has a `begin`. */
    private fun compileMatch(mode: Mode) {
        if (mode.match == null && mode.matchList == null) return
        require(mode.begin == null && mode.end == null) {
            "begin & end are not supported with match"
        }
        mode.begin = mode.match
        mode.beginList = mode.matchList
        mode.match = null
        mode.matchList = null
    }

    /** Resolves `scope: {}`, `beginScope` and `endScope` into their compiled form. */
    private fun multiClass(mode: Mode) {
        mode.scopes?.let { scopes ->
            mode.beginScopes = scopes
            mode.scopes = null
        }
        mode.beginScope?.let { mode.compiledBeginScope = CompiledScope(wrap = it) }
        mode.endScope?.let { mode.compiledEndScope = CompiledScope(wrap = it) }

        mode.beginList?.let { regexes ->
            require(!mode.skip && !mode.excludeBegin && !mode.returnBegin) {
                "skip, excludeBegin, returnBegin not compatible with beginScope: {}"
            }
            val scopes = requireNotNull(mode.beginScopes) { "beginScope must be provided" }
            mode.compiledBeginScope = remapScopeNames(scopes, regexes)
            mode.begin = rewriteBackreferences(regexes, joinWith = "")
            mode.beginList = null
        }
        mode.endList?.let { regexes ->
            require(!mode.skip && !mode.excludeEnd && !mode.returnEnd) {
                "skip, excludeEnd, returnEnd not compatible with endScope: {}"
            }
            val scopes = requireNotNull(mode.endScopes) { "endScope must be provided" }
            mode.compiledEndScope = remapScopeNames(scopes, regexes)
            mode.end = rewriteBackreferences(regexes, joinWith = "")
            mode.endList = null
        }
    }

    /**
     * Renumbers labelled scopes to account for the capture groups nested inside each expression.
     *
     * `(a)(((b)))(c)` yields five groups, but only groups 1, 2 and 5 are the ones the grammar
     * author labelled.
     */
    private fun remapScopeNames(scopeNames: Map<Int, String?>, regexes: List<String>): CompiledScope {
        var offset = 0
        val positions = LinkedHashMap<Int, String?>()
        val emit = LinkedHashSet<Int>()
        for (index in 1..regexes.size) {
            positions[index + offset] = scopeNames[index]
            emit += index + offset
            offset += countMatchGroups(regexes[index - 1])
        }
        return CompiledScope(positions = positions, emit = emit)
    }

    /**
     * `beforeMatch` acts as a qualifier: the full match must be `[beforeMatch][begin]`, but only
     * `[begin]` belongs to the mode. Implemented by rewriting the mode into a zero width lookahead
     * whose `starts` mode carries the original rule.
     */
    private fun beforeMatchExt(mode: Mode) {
        val beforeMatch = mode.beforeMatch ?: return
        require(mode.starts == null) { "beforeMatch cannot be used with starts" }

        val original = mode.copy().apply {
            this.beforeMatch = null
            endsParent = true
        }
        val originalBegin = requireNotNull(original.begin) { "beforeMatch requires a begin" }

        mode.resetForRewrite()
        mode.keywords = original.keywords
        mode.begin = concat(beforeMatch, lookahead(originalBegin))
        mode.starts = mode {
            relevance = 0.0
            contains = listOf(original)
        }
        mode.relevance = 0.0
    }

    /** `beginKeywords` is sugar for a `begin` matching any of the listed keywords. */
    private fun beginKeywordsExt(mode: Mode, parent: Mode?) {
        if (parent == null) return
        val beginKeywords = mode.beginKeywords ?: return

        // Languages whose keywords contain non-word characters are not served by `\b` alone, so a
        // trailing whitespace boundary is accepted as well.
        mode.begin = "\\b(" + Keywords.splitWords(beginKeywords).joinToString("|") + ")(?!\\.)(?=\\b|\\s)"
        mode.beforeBegin = ::skipIfHasPrecedingDot
        if (mode.keywords == null) mode.keywords = keywords(beginKeywords)
        mode.beginKeywords = null

        // The keywords already provide relevance; the mode must not double it.
        if (mode.relevance == null) mode.relevance = 0.0
    }

    private fun compileIllegal(mode: Mode) {
        mode.illegalList?.let { patterns ->
            mode.illegal = either(patterns)
            mode.illegalList = null
        }
    }

    private fun compileRelevance(mode: Mode) {
        if (mode.relevance == null) mode.relevance = 1.0
    }

    private companion object {
        const val DEFAULT_KEYWORD_PATTERN = """\w+"""

        /** `\B|\b` matches the empty string everywhere, which is how upstream spells "no regex". */
        const val EMPTY_MATCH = """\B|\b"""
    }
}

/**
 * Skips a `beginKeywords` match that is preceded by a dot, so `bob.keyword.do()` is left alone.
 *
 * Upstream needs this because JavaScript regexes have no negative look-behind support.
 */
private fun skipIfHasPrecedingDot(match: MatchData, response: CallbackResponse) {
    if (match.index > 0 && match.input[match.index - 1] == '.') response.ignoreMatch()
}

/** Does [mode] — or anything it starts — depend on knowing its parent? */
private fun dependencyOnParent(mode: Mode?): Boolean {
    if (mode == null) return false
    return mode.endsWithParent || dependencyOnParent(mode.starts)
}

/**
 * Expands a mode into its variants, or copies it when it cannot safely be shared.
 *
 * Mirrors `expandOrCloneMode()` upstream.
 */
private fun expandOrCloneMode(mode: Mode): List<Mode> {
    mode.variants?.let { variants ->
        if (mode.cachedVariants == null) {
            mode.cachedVariants = variants.map { variant ->
                mode.copy().apply {
                    this.variants = null
                    variant()
                }
            }
        }
    }
    mode.cachedVariants?.let { return it }

    // A mode that reads its parent needs an instance per parent.
    if (dependencyOnParent(mode)) {
        return listOf(mode.copy().apply { starts = mode.starts?.copy() })
    }

    if (mode.frozen) return listOf(mode.copy())

    return listOf(mode)
}
