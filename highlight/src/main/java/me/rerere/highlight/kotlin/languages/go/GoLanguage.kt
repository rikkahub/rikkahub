package me.rerere.highlight.kotlin.languages.go

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object GoLanguage {
    val definition = LanguageDefinition(
        name = "Go",
        aliases = setOf("go", "golang"),
        rules = createGoRules(),
    )
}
