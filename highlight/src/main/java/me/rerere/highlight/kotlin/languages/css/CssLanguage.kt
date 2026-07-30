package me.rerere.highlight.kotlin.languages.css

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object CssLanguage {
    val definition = LanguageDefinition(
        name = "CSS",
        aliases = setOf("css"),
        rules = createCssRules(),
    )
}
