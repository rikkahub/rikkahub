package me.rerere.highlight.kotlin.languages.rust

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object RustLanguage {
    val definition = LanguageDefinition(
        name = "Rust",
        aliases = setOf("rust", "rs"),
        rules = createRustRules(),
    )
}
