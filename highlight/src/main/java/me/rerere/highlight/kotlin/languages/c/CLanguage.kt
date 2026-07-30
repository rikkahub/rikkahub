package me.rerere.highlight.kotlin.languages.c

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object CLanguage {
    val definition = LanguageDefinition(
        name = "C",
        aliases = setOf("c", "h"),
        rules = createCRules(),
    )
}
