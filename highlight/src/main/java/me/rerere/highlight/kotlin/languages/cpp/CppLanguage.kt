package me.rerere.highlight.kotlin.languages.cpp

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object CppLanguage {
    val definition = LanguageDefinition(
        name = "C++",
        aliases = setOf("cpp", "cc", "c++", "h++", "hpp", "hh", "hxx", "cxx"),
        rules = createCppRules(),
    )
}
