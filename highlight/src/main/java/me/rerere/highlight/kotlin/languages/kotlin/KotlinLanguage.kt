package me.rerere.highlight.kotlin.languages.kotlin

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object KotlinLanguage {
    val definition = LanguageDefinition(
        name = "Kotlin",
        aliases = setOf("kotlin", "kt", "kts", "ktm", "ktx"),
        rules = createKotlinRules(),
    )
}
