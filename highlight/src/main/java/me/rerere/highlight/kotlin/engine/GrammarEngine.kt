package me.rerere.highlight.kotlin.engine

import me.rerere.highlight.HighlightToken

internal class GrammarEngine(
    languages: List<LanguageDefinition>,
) {
    private companion object {
        const val MAX_EMBEDDING_DEPTH = 16
    }

    private val languagesByAlias = buildMap {
        languages.forEach { language ->
            language.aliases.forEach { alias ->
                require(put(alias.lowercase(), language) == null) {
                    "Duplicate language alias: $alias"
                }
            }
        }
    }

    fun supports(language: String): Boolean {
        return languagesByAlias.containsKey(language.trim().lowercase())
    }

    fun highlight(code: String, language: String): List<HighlightToken>? {
        val definition = languagesByAlias[language.trim().lowercase()] ?: return null
        return scan(
            source = code,
            startIndex = 0,
            endIndex = code.length,
            language = definition,
            embeddingDepth = 0,
        ).tokens
    }

    internal fun highlightBalanced(
        source: String,
        startIndex: Int,
        endIndex: Int,
        language: LanguageDefinition,
        embeddingDepth: Int,
    ): ScanResult {
        return scan(
            source = source,
            startIndex = startIndex,
            endIndex = endIndex,
            language = language,
            stopAtClosingBrace = true,
            embeddingDepth = embeddingDepth,
        )
    }

    internal fun highlightRange(
        source: String,
        startIndex: Int,
        endIndex: Int,
        language: LanguageDefinition,
        embeddingDepth: Int,
    ): ScanResult {
        return scan(
            source = source,
            startIndex = startIndex,
            endIndex = endIndex,
            language = language,
            embeddingDepth = embeddingDepth,
        )
    }

    internal fun highlightEmbeddedRange(
        source: String,
        startIndex: Int,
        endIndex: Int,
        language: String,
        embeddingDepth: Int,
    ): ScanResult {
        val definition = languagesByAlias[language.trim().lowercase()]
        if (definition == null || embeddingDepth >= MAX_EMBEDDING_DEPTH) {
            return plainResult(source, startIndex, endIndex)
        }

        return scan(
            source = source,
            startIndex = startIndex,
            endIndex = endIndex,
            language = definition,
            embeddingDepth = embeddingDepth + 1,
        )
    }

    private fun scan(
        source: String,
        startIndex: Int,
        endIndex: Int,
        language: LanguageDefinition,
        stopAtClosingBrace: Boolean = false,
        embeddingDepth: Int,
    ): ScanResult {
        val emitter = TokenEmitter()
        var index = startIndex
        var previousKind = LexemeKind.Start
        var braceDepth = 0

        while (index < endIndex) {
            if (stopAtClosingBrace && source[index] == '}' && braceDepth == 0) {
                break
            }

            val context = MatchContext(
                source = source,
                index = index,
                endIndex = endIndex,
                previousKind = previousKind,
                language = language,
                engine = this,
                embeddingDepth = embeddingDepth,
            )
            val match = language.rules.firstNotNullOfOrNull { it.match(context) }

            if (match == null) {
                emitter.plain(source[index].toString())
                index++
                continue
            }

            require(match.endIndex in (index + 1)..endIndex) {
                "Grammar rule for ${language.name} returned an invalid range: " +
                    "$index..${match.endIndex}"
            }

            emitter.appendAll(match.tokens)
            if (stopAtClosingBrace && match.endIndex == index + 1) {
                when (source[index]) {
                    '{' -> braceDepth++
                    '}' -> braceDepth--
                }
            }
            match.nextKind?.let { previousKind = it }
            index = match.endIndex
        }

        return ScanResult(
            tokens = emitter.build(),
            endIndex = index,
        )
    }

    private fun plainResult(
        source: String,
        startIndex: Int,
        endIndex: Int,
    ): ScanResult {
        val content = source.substring(startIndex, endIndex)
        return ScanResult(
            tokens = if (content.isEmpty()) emptyList() else listOf(HighlightToken.Plain(content)),
            endIndex = endIndex,
        )
    }
}
