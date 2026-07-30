package me.rerere.highlight.kotlin.languages.cpp

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.languages.cfamily.createCFamilyRules

internal fun createCppRules(): List<GrammarRule> {
    return createCFamilyRules(CppGrammar.dialect)
}
