package me.rerere.highlight.kotlin.languages.java.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.java.JavaGrammar

internal object JavaIdentifierRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (!JavaGrammar.isIdentifierStart(context.source[context.index])) return null

        var cursor = context.index + 1
        while (
            cursor < context.endIndex &&
            JavaGrammar.isIdentifierPart(context.source[cursor])
        ) {
            cursor++
        }

        val word = context.source.substring(context.index, cursor)
        val next = context.nextNonWhitespace(cursor)
        val scope = when {
            word == "true" || word == "false" -> TokenScope.BOOLEAN
            word == "null" -> TokenScope.CONSTANT
            word in JavaGrammar.primitiveTypes -> TokenScope.CLASS_NAME
            word in JavaGrammar.keywords -> TokenScope.KEYWORD
            context.previousKind == LexemeKind.ClassDeclaration -> TokenScope.CLASS_NAME
            next < context.endIndex && context.source[next] == '(' -> TokenScope.FUNCTION
            context.previousKind == LexemeKind.PropertyAccess -> TokenScope.PROPERTY
            upperCaseConstant.matches(word) -> TokenScope.CONSTANT
            word.firstOrNull()?.isUpperCase() == true -> TokenScope.CLASS_NAME
            else -> null
        }
        val nextKind = when {
            word in JavaGrammar.typeDeclarationKeywords -> LexemeKind.ClassDeclaration
            word in JavaGrammar.keywords -> LexemeKind.Keyword
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
