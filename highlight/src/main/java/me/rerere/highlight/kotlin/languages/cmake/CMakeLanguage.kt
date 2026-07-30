package me.rerere.highlight.kotlin.languages.cmake

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object CMakeLanguage {
    val definition = LanguageDefinition(
        name = "CMake",
        aliases = setOf("cmake", "cmake.in"),
        rules = createCMakeRules(),
    )
}
