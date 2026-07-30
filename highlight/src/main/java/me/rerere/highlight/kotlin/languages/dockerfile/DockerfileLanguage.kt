package me.rerere.highlight.kotlin.languages.dockerfile

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object DockerfileLanguage {
    val definition = LanguageDefinition(
        name = "Dockerfile",
        aliases = setOf("dockerfile", "docker"),
        rules = createDockerfileRules(),
    )
}
