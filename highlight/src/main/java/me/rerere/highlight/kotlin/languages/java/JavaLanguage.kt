package me.rerere.highlight.kotlin.languages.java

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object JavaLanguage {
    val definition = LanguageDefinition(
        name = "Java",
        aliases = setOf("java", "jsp"),
        rules = createJavaRules(),
    )
}
