package me.rerere.highlight.core

/**
 * Raw keyword declaration of a [Mode], mirroring the `keywords` property in `highlight.js`.
 *
 * Upstream accepts a space separated string, an array of strings, or an object mapping a scope name
 * to either of those plus an optional `$pattern`. All three shapes are expressed by [groups] and
 * [pattern] here.
 */
internal class Keywords private constructor(
    val pattern: String?,
    val groups: List<Group>,
) {
    class Group(val scope: String, val words: List<String>)

    companion object {
        const val DEFAULT_SCOPE = "keyword"

        fun of(words: String): Keywords = of(splitWords(words))

        fun of(words: List<String>): Keywords =
            Keywords(pattern = null, groups = listOf(Group(DEFAULT_SCOPE, words)))

        fun build(block: KeywordsBuilder.() -> Unit): Keywords {
            val builder = KeywordsBuilder().apply(block)
            return Keywords(pattern = builder.pattern, groups = builder.groups.toList())
        }

        /**
         * Upstream splits on a single space; splitting on any run of whitespace additionally lets
         * grammars keep long keyword lists readable across several lines.
         */
        fun splitWords(words: String): List<String> =
            words.split(WHITESPACE).filter { it.isNotEmpty() }

        private val WHITESPACE = Regex("""\s+""")
    }
}

internal class KeywordsBuilder {
    /** `$pattern` upstream: the regex used to carve keyword candidates out of a mode buffer. */
    var pattern: String? = null

    internal val groups = mutableListOf<Keywords.Group>()

    fun scope(name: String, words: String) {
        groups += Keywords.Group(name, Keywords.splitWords(words))
    }

    fun scope(name: String, words: List<String>) {
        groups += Keywords.Group(name, words)
    }

    fun keyword(words: String) = scope("keyword", words)

    fun keyword(words: List<String>) = scope("keyword", words)

    fun literal(words: String) = scope("literal", words)

    fun literal(words: List<String>) = scope("literal", words)

    fun builtIn(words: String) = scope("built_in", words)

    fun builtIn(words: List<String>) = scope("built_in", words)

    fun type(words: String) = scope("type", words)

    fun type(words: List<String>) = scope("type", words)

    fun symbol(words: String) = scope("symbol", words)

    fun symbol(words: List<String>) = scope("symbol", words)

    fun variable(words: String) = scope("variable", words)

    fun variable(words: List<String>) = scope("variable", words)

    fun title(words: String) = scope("title", words)

    fun meta(words: String) = scope("meta", words)

    fun section(words: String) = scope("section", words)
}

internal fun keywords(words: String): Keywords = Keywords.of(words)

internal fun keywords(words: List<String>): Keywords = Keywords.of(words)

internal fun keywords(block: KeywordsBuilder.() -> Unit): Keywords = Keywords.build(block)

/** A compiled keyword: the scope it should be emitted under, plus its relevance score. */
internal class KeywordData(val scope: String, val relevance: Double)

/** Keywords that carry no relevance by default, mirroring `COMMON_KEYWORDS` upstream. */
private val COMMON_KEYWORDS = setOf(
    "of", "and", "for", "in", "not", "or", "if", "then", "parent", "list", "value",
)

/** Mirrors `compileKeywords()` upstream. */
internal fun compileKeywords(
    keywords: Keywords,
    caseInsensitive: Boolean,
): Map<String, KeywordData> {
    val compiled = LinkedHashMap<String, KeywordData>()
    keywords.groups.forEach { group ->
        group.words.forEach { word ->
            val keyword = if (caseInsensitive) word.lowercase() else word
            val separator = keyword.indexOf('|')
            val name = if (separator == -1) keyword else keyword.substring(0, separator)
            val providedScore = if (separator == -1) null else keyword.substring(separator + 1)
            compiled[name] = KeywordData(group.scope, scoreForKeyword(name, providedScore))
        }
    }
    return compiled
}

/** Mirrors `scoreForKeyword()` upstream: manual scores always win over common keywords. */
private fun scoreForKeyword(keyword: String, providedScore: String?): Double {
    if (!providedScore.isNullOrEmpty()) {
        return providedScore.toDoubleOrNull() ?: 1.0
    }
    return if (keyword.lowercase() in COMMON_KEYWORDS) 0.0 else 1.0
}
