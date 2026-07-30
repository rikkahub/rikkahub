package me.rerere.highlight.kotlin.languages.yaml

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object YamlLanguage {
    val definition = LanguageDefinition(
        name = "YAML",
        aliases = setOf("yaml", "yml"),
        rules = createYamlRules(),
    )
}
