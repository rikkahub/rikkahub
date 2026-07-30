package me.rerere.highlight.kotlin.languages.rust.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.rust.RustGrammar

internal object RustIdentifierRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val identifier = readIdentifier(context) ?: return null
        val content = context.source.substring(context.index, identifier.endIndex)
        val word = content.removePrefix("r#")
        val next = context.nextNonWhitespace(identifier.endIndex)
        val isRaw = content.startsWith("r#")

        val scope = when {
            !isRaw && (word == "true" || word == "false") -> TokenScope.BOOLEAN
            !isRaw && word in RustGrammar.constants -> TokenScope.CONSTANT
            !isRaw && word in RustGrammar.builtInTypes -> TokenScope.CLASS_NAME
            !isRaw && word in RustGrammar.builtInTraits -> TokenScope.CLASS_NAME
            !isRaw && word in RustGrammar.keywords -> TokenScope.KEYWORD
            context.previousKind == LexemeKind.ClassDeclaration -> TokenScope.CLASS_NAME
            context.previousKind == LexemeKind.FunctionDeclaration -> TokenScope.FUNCTION
            upperCaseConstant.matches(word) -> TokenScope.CONSTANT
            word.firstOrNull()?.isUpperCase() == true -> TokenScope.CLASS_NAME
            context.previousKind == LexemeKind.VariableDeclaration -> TokenScope.VARIABLE
            !isRaw && word in RustGrammar.builtInFunctions -> TokenScope.FUNCTION
            next < context.endIndex && context.source[next] == '(' -> TokenScope.FUNCTION
            context.previousKind == LexemeKind.PropertyAccess -> TokenScope.PROPERTY
            else -> null
        }
        val nextKind = when {
            context.previousKind == LexemeKind.VariableDeclaration &&
                (word == "mut" || word == "ref") -> LexemeKind.VariableDeclaration
            context.previousKind == LexemeKind.VariableDeclaration &&
                next < context.endIndex &&
                context.source[next] == ',' -> LexemeKind.VariableDeclaration
            !isRaw && word == "fn" -> LexemeKind.FunctionDeclaration
            !isRaw && (word == "let" || word == "const" || word == "static" || word == "for") ->
                LexemeKind.VariableDeclaration
            !isRaw && word in RustGrammar.typeDeclarationKeywords ->
                LexemeKind.ClassDeclaration
            !isRaw && word in RustGrammar.keywords -> LexemeKind.Keyword
            else -> LexemeKind.Value
        }
        return context.tokenMatch(
            matchEndIndex = identifier.endIndex,
            scope = scope,
            nextKind = nextKind,
        )
    }

    private fun readIdentifier(context: MatchContext): Identifier? {
        var cursor = context.index
        val isRaw = context.source.startsWith("r#", cursor)
        if (isRaw) cursor += 2
        if (
            cursor >= context.endIndex ||
            !RustGrammar.isIdentifierStart(context.source[cursor])
        ) {
            return null
        }

        cursor++
        while (
            cursor < context.endIndex &&
            RustGrammar.isIdentifierPart(context.source[cursor])
        ) {
            cursor++
        }
        return Identifier(cursor)
    }

    private fun MatchContext.nextNonWhitespace(startIndex: Int): Int {
        var cursor = startIndex
        while (cursor < endIndex && source[cursor].isWhitespace()) cursor++
        return cursor
    }

    private data class Identifier(
        val endIndex: Int,
    )

    private val upperCaseConstant = Regex("""[A-Z][A-Z0-9_]+""")
}
