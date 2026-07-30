package me.rerere.highlight.kotlin.languages.c

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.languages.cfamily.createCFamilyRules

internal fun createCRules(): List<GrammarRule> {
    return createCFamilyRules(CGrammar.dialect)
}
