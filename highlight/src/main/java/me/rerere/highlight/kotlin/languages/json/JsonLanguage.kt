package me.rerere.highlight.kotlin.languages.json

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object JsonLanguage {
    val definition = LanguageDefinition(
        name = "JSON",
        aliases = setOf("json", "jsonc", "json5"),
        rules = createJsonRules(),
    )
}
