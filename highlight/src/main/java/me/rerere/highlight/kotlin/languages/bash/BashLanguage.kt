package me.rerere.highlight.kotlin.languages.bash

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object BashLanguage {
    val definition = LanguageDefinition(
        name = "Bash",
        aliases = setOf("bash", "sh", "zsh", "shell"),
        rules = createBashRules(),
    )
}
