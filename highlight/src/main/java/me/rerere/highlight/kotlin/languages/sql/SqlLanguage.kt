package me.rerere.highlight.kotlin.languages.sql

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object SqlLanguage {
    val definition = LanguageDefinition(
        name = "SQL",
        aliases = setOf("sql"),
        rules = createSqlRules(),
    )
}
