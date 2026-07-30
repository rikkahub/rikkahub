package me.rerere.highlight.kotlin.engine

/**
 * Declarative language entry consumed by [GrammarEngine].
 *
 * Rules are evaluated in order and the first rule matching the current cursor wins.
 */
internal data class LanguageDefinition(
    val name: String,
    val aliases: Set<String>,
    val rules: List<GrammarRule>,
)
