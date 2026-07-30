package me.rerere.highlight.kotlin.languages.javascript.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptDialect
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.CLASS_REFERENCE_PATTERN
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.FUNCTION_TYPE_END_CHARS
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.NULL_CHAR
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.UPPER_CASE_CONSTANT_PATTERN
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.allTypeScriptKeywords
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.booleanLiterals
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.builtInGlobals
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.builtInTypes
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.builtInVariables
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.classDeclarationKeywords
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.constantLiterals
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.errorTypes
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.expressionStarterKeywords
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.isIdentifierPart
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.isIdentifierStart
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.javaScriptKeywords
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.typeScriptTypes

internal class IdentifierRule(
    private val dialect: JavaScriptDialect,
) : GrammarRule {
    private val keywords =
        if (dialect == JavaScriptDialect.TypeScript) allTypeScriptKeywords else javaScriptKeywords

    override fun match(context: MatchContext): RuleMatch? {
        val first = context.source[context.index]
        val second = context.source.getOrNull(context.index + 1) ?: NULL_CHAR
        if (!isIdentifierStart(first) && (first != '#' || !isIdentifierStart(second))) {
            return null
        }

        var cursor = context.index
        if (context.source[cursor] == '#') cursor++
        cursor++
        while (cursor < context.endIndex && isIdentifierPart(context.source[cursor])) cursor++

        val word = context.source.substring(context.index, cursor)
        val bareWord = word.removePrefix("#")
        val next = context.nextNonWhitespace(cursor)

        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = classifyIdentifier(
                context = context,
                word = bareWord,
                next = next,
            ),
            nextKind = classifyKind(
                previous = context.previousKind,
                word = bareWord,
            ),
        )
    }

    private fun classifyIdentifier(
        context: MatchContext,
        word: String,
        next: Int,
    ): String? {
        if (context.previousKind == LexemeKind.PropertyAccess) return TokenScope.PROPERTY

        if (word in booleanLiterals) return TokenScope.BOOLEAN
        if (word in constantLiterals) return TokenScope.CONSTANT
        if (word in keywords) return TokenScope.KEYWORD
        if (dialect == JavaScriptDialect.TypeScript && word in typeScriptTypes) {
            return TokenScope.CLASS_NAME
        }
        if (word in builtInVariables) return TokenScope.VARIABLE
        if (context.previousKind == LexemeKind.ClassDeclaration) return TokenScope.CLASS_NAME

        if (
            next < context.endIndex &&
            context.source[next] == ':' &&
            context.source.getOrNull(next + 1) != ':'
        ) {
            return TokenScope.PROPERTY
        }
        if (
            dialect == JavaScriptDialect.TypeScript &&
            context.source.getOrNull(next) == '?' &&
            context.source.getOrNull(next + 1) == ':'
        ) {
            return TokenScope.PROPERTY
        }
        if (next < context.endIndex && context.source[next] == '(') return TokenScope.FUNCTION
        if (looksLikeFunctionVariable(context, cursor = next)) return TokenScope.FUNCTION

        if (word in builtInGlobals || word in builtInTypes || word in errorTypes) {
            return TokenScope.CLASS_NAME
        }
        if (UPPER_CASE_CONSTANT_PATTERN.matches(word)) return TokenScope.VARIABLE
        if (CLASS_REFERENCE_PATTERN.matches(word)) return TokenScope.CLASS_NAME

        return null
    }

    private fun classifyKind(previous: LexemeKind, word: String): LexemeKind {
        return when {
            previous == LexemeKind.PropertyAccess -> LexemeKind.Value
            word in expressionStarterKeywords -> LexemeKind.KeywordExpressionStarter
            word in classDeclarationKeywords -> LexemeKind.ClassDeclaration
            word in keywords -> LexemeKind.Keyword
            else -> LexemeKind.Value
        }
    }

    private fun looksLikeFunctionVariable(context: MatchContext, cursor: Int): Boolean {
        var position = cursor
        if (
            position >= context.endIndex ||
            (context.source[position] != '=' && context.source[position] != ':')
        ) {
            return false
        }
        position = context.nextNonWhitespace(position + 1)

        if (
            context.source.startsWith("async", position) &&
            !isIdentifierPart(context.source.getOrNull(position + 5) ?: NULL_CHAR)
        ) {
            position = context.nextNonWhitespace(position + 5)
        }
        if (
            context.source.startsWith("function", position) &&
            !isIdentifierPart(context.source.getOrNull(position + 8) ?: NULL_CHAR)
        ) {
            return true
        }

        if (isIdentifierStart(context.source.getOrNull(position) ?: NULL_CHAR)) {
            position++
            while (
                position < context.endIndex &&
                isIdentifierPart(context.source[position])
            ) {
                position++
            }
            position = context.nextNonWhitespace(position)
            return context.source.startsWith("=>", position)
        }

        if (context.source.getOrNull(position) != '(') return false
        position = skipBalanced(context, position, '(', ')')
        if (position == -1) return false
        position = context.nextNonWhitespace(position)

        if (context.source.getOrNull(position) == ':') {
            position++
            while (
                position < context.endIndex &&
                context.source[position] !in FUNCTION_TYPE_END_CHARS
            ) {
                position++
            }
            position = context.nextNonWhitespace(position)
        }
        return context.source.startsWith("=>", position)
    }

    private fun skipBalanced(
        context: MatchContext,
        start: Int,
        opening: Char,
        closing: Char,
    ): Int {
        var cursor = start
        var depth = 0
        while (cursor < context.endIndex) {
            when (context.source[cursor]) {
                '\'', '"' -> cursor = skipQuoted(context, cursor, context.source[cursor])
                opening -> {
                    depth++
                    cursor++
                }

                closing -> {
                    depth--
                    cursor++
                    if (depth == 0) return cursor
                }

                else -> cursor++
            }
        }
        return -1
    }

    private fun skipQuoted(context: MatchContext, start: Int, quote: Char): Int {
        var cursor = start + 1
        while (cursor < context.endIndex) {
            when (context.source[cursor]) {
                '\\' -> cursor = (cursor + 2).coerceAtMost(context.endIndex)
                quote -> return cursor + 1
                '\n', '\r' -> return cursor
                else -> cursor++
            }
        }
        return cursor
    }

    private fun MatchContext.nextNonWhitespace(start: Int): Int {
        var cursor = start
        while (cursor < endIndex && source[cursor].isWhitespace()) cursor++
        return cursor
    }
}
