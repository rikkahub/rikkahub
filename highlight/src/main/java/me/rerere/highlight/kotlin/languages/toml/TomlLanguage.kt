package me.rerere.highlight.kotlin.languages.toml

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object TomlLanguage {
    val definition = LanguageDefinition(
        name = "TOML",
        aliases = setOf("toml"),
        rules = createTomlRules(),
    )
}
