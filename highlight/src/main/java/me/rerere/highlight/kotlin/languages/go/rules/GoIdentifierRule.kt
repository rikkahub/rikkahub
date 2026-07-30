package me.rerere.highlight.kotlin.languages.go.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.go.GoGrammar

internal object GoIdentifierRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (!GoGrammar.isIdentifierStart(context.source[context.index])) return null

        var cursor = context.index + 1
        while (
            cursor < context.endIndex &&
            GoGrammar.isIdentifierPart(context.source[cursor])
        ) {
            cursor++
        }

        val word = context.source.substring(context.index, cursor)
        val next = context.nextNonWhitespace(cursor)
        val scope = when {
            word == "true" || word == "false" -> TokenScope.BOOLEAN
            word == "nil" || word == "iota" -> TokenScope.CONSTANT
            word in GoGrammar.keywords -> TokenScope.KEYWORD
            context.previousKind == LexemeKind.ClassDeclaration -> TokenScope.CLASS_NAME
            context.previousKind == LexemeKind.VariableDeclaration -> TokenScope.VARIABLE
            word in GoGrammar.builtInTypes -> TokenScope.CLASS_NAME
            word in GoGrammar.builtIns -> TokenScope.FUNCTION
            next < context.endIndex && context.source[next] == '(' -> TokenScope.FUNCTION
            context.previousKind == LexemeKind.PropertyAccess -> TokenScope.PROPERTY
            upperCaseConstant.matches(word) -> TokenScope.CONSTANT
            word.firstOrNull()?.isUpperCase() == true -> TokenScope.CLASS_NAME
            else -> null
        }
        val nextKind = when (word) {
            "type" -> LexemeKind.ClassDeclaration
            "const", "var" -> LexemeKind.VariableDeclaration
            in GoGrammar.keywords -> LexemeKind.Keyword
            else -> LexemeKind.Value
        }
        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = scope,
            nextKind = nextKind,
        )
    }

    private fun MatchContext.nextNonWhitespace(startIndex: Int): Int {
        var cursor = startIndex
        while (cursor < endIndex && source[cursor].isWhitespace()) cursor++
        return cursor
    }

    private val upperCaseConstant = Regex("""[A-Z][A-Z0-9_]+""")
}
