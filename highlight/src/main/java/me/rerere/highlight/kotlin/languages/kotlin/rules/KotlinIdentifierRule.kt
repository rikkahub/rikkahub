package me.rerere.highlight.kotlin.languages.kotlin.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.kotlin.KotlinGrammar

internal object KotlinIdentifierRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val range = readIdentifier(context) ?: return null
        val content = context.source.substring(range.first, range.last + 1)
        val word = content.removeSurrounding("`")
        val next = context.nextNonWhitespace(range.last + 1)

        val scope = when {
            word == "true" || word == "false" -> TokenScope.BOOLEAN
            word == "null" -> TokenScope.CONSTANT
            word in KotlinGrammar.keywords -> TokenScope.KEYWORD
            word in KotlinGrammar.builtInTypes -> TokenScope.CLASS_NAME
            context.previousKind == LexemeKind.VariableDeclaration -> TokenScope.VARIABLE
            context.previousKind == LexemeKind.ClassDeclaration -> TokenScope.CLASS_NAME
            context.previousKind == LexemeKind.FunctionDeclaration &&
                next < context.endIndex &&
                context.source[next] == '.' -> TokenScope.CLASS_NAME
            context.previousKind == LexemeKind.FunctionDeclaration -> TokenScope.FUNCTION
            next < context.endIndex && context.source[next] == '(' -> TokenScope.FUNCTION
            context.previousKind == LexemeKind.PropertyAccess -> TokenScope.PROPERTY
            word.firstOrNull()?.isUpperCase() == true -> TokenScope.CLASS_NAME
            upperCaseConstant.matches(word) -> TokenScope.CONSTANT
            else -> null
        }
        val nextKind = when {
            context.previousKind == LexemeKind.VariableDeclaration &&
                next < context.endIndex &&
                context.source[next] == ',' -> LexemeKind.VariableDeclaration
            word == "fun" -> LexemeKind.FunctionDeclaration
            word == "val" || word == "var" -> LexemeKind.VariableDeclaration
            word in KotlinGrammar.typeDeclarationKeywords -> LexemeKind.ClassDeclaration
            word in KotlinGrammar.keywords -> LexemeKind.Keyword
            else -> LexemeKind.Value
        }
        return context.tokenMatch(
            matchEndIndex = range.last + 1,
            scope = scope,
            nextKind = nextKind,
        )
    }

    private fun readIdentifier(context: MatchContext): IntRange? {
        if (context.source[context.index] == '`') {
            var cursor = context.index + 1
            while (
                cursor < context.endIndex &&
                context.source[cursor] != '`' &&
                context.source[cursor] != '\n' &&
                context.source[cursor] != '\r'
            ) {
                cursor++
            }
            if (cursor < context.endIndex && context.source[cursor] == '`') cursor++
            return (context.index until cursor).takeIf { !it.isEmpty() }
        }
        if (!KotlinGrammar.isIdentifierStart(context.source[context.index])) return null

        var cursor = context.index + 1
        while (
            cursor < context.endIndex &&
            KotlinGrammar.isIdentifierPart(context.source[cursor])
        ) {
            cursor++
        }
        return context.index until cursor
    }

    private fun MatchContext.nextNonWhitespace(startIndex: Int): Int {
        var cursor = startIndex
        while (cursor < endIndex && source[cursor].isWhitespace()) cursor++
        return cursor
    }

    private val upperCaseConstant = Regex("""[A-Z][A-Z0-9_]+""")
}
