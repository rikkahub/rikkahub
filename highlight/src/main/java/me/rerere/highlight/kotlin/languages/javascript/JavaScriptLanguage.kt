package me.rerere.highlight.kotlin.languages.javascript

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object JavaScriptLanguage {
    val definition = LanguageDefinition(
        name = "JavaScript",
        aliases = setOf("javascript", "js", "jsx", "mjs", "cjs"),
        rules = createJavaScriptRules(JavaScriptDialect.JavaScript),
    )
}
