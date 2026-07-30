package me.rerere.highlight.kotlin.languages.python

import me.rerere.highlight.kotlin.engine.LanguageDefinition

internal object PythonLanguage {
    val definition = LanguageDefinition(
        name = "Python",
        aliases = setOf("python", "py", "gyp", "ipython"),
        rules = createPythonRules(),
    )
}
