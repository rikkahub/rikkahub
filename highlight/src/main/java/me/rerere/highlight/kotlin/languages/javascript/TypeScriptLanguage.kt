package me.rerere.highlight.kotlin.languages.javascript

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object TypeScriptLanguage {
    val definition = LanguageDefinition(
        name = "TypeScript",
        aliases = setOf("typescript", "ts", "tsx", "mts", "cts"),
        rules = createJavaScriptRules(JavaScriptDialect.TypeScript),
    )
}
