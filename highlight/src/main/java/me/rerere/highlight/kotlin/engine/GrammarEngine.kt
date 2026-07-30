package me.rerere.highlight.kotlin.engine

import me.rerere.highlight.HighlightToken

internal class GrammarEngine(
    languages: List<LanguageDefinition>,
) {
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
        ).tokens
    }

    internal fun highlightBalanced(
        source: String,
        startIndex: Int,
        endIndex: Int,
        language: LanguageDefinition,
    ): ScanResult {
        return scan(
            source = source,
            startIndex = startIndex,
            endIndex = endIndex,
            language = language,
            stopAtClosingBrace = true,
        )
    }

    internal fun highlightRange(
        source: String,
        startIndex: Int,
        endIndex: Int,
        language: LanguageDefinition,
    ): ScanResult {
        return scan(
            source = source,
            startIndex = startIndex,
            endIndex = endIndex,
            language = language,
        )
    }

    private fun scan(
        source: String,
        startIndex: Int,
        endIndex: Int,
        language: LanguageDefinition,
        stopAtClosingBrace: Boolean = false,
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
}
