package me.rerere.highlight.kotlin.languages.cfamily.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.cfamily.CFamilyDialect
import me.rerere.highlight.kotlin.languages.cfamily.CFamilyGrammar

internal class CFamilyIdentifierRule(
    private val dialect: CFamilyDialect,
) : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (!CFamilyGrammar.isIdentifierStart(context.source[context.index])) return null

        var cursor = context.index + 1
        while (
            cursor < context.endIndex &&
            CFamilyGrammar.isIdentifierPart(context.source[cursor])
        ) {
            cursor++
        }

        val word = context.source.substring(context.index, cursor)
        val next = context.nextNonWhitespace(cursor)
        val scope = when {
            word == "true" || word == "false" -> TokenScope.BOOLEAN
            word == "NULL" || word == "nullptr" || word == "nullopt" ->
                TokenScope.CONSTANT
            word in dialect.types || typeName.matches(word) -> TokenScope.CLASS_NAME
            word in dialect.keywords -> TokenScope.KEYWORD
            context.previousKind == LexemeKind.ClassDeclaration -> TokenScope.CLASS_NAME
            word in dialect.builtIns -> TokenScope.FUNCTION
            next < context.endIndex && context.source[next] == '(' -> TokenScope.FUNCTION
            context.previousKind == LexemeKind.PropertyAccess -> TokenScope.PROPERTY
            upperCaseConstant.matches(word) -> TokenScope.CONSTANT
            word.firstOrNull()?.isUpperCase() == true -> TokenScope.CLASS_NAME
            else -> null
        }
        val nextKind = when {
            word in dialect.typeDeclarationKeywords -> LexemeKind.ClassDeclaration
            word in dialect.keywords -> LexemeKind.Keyword
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

    private val typeName = Regex("""(?:[A-Za-z0-9_]+_t|atomic_[A-Za-z0-9_]+)""")
    private val upperCaseConstant = Regex("""[A-Z][A-Z0-9_]+""")
}
