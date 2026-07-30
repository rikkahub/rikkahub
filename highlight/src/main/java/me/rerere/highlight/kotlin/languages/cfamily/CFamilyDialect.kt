package me.rerere.highlight.kotlin.languages.cfamily

internal data class CFamilyDialect(
    val keywords: Set<String>,
    val types: Set<String>,
    val builtIns: Set<String>,
    val typeDeclarationKeywords: Set<String>,
)
