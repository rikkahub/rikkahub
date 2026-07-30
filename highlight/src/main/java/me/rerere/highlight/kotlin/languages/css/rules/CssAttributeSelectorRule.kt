package me.rerere.highlight.kotlin.languages.css.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.css.isInDeclarationValue

internal object CssAttributeSelectorRule : GrammarRule {
    private val namePattern = Regex("""[A-Za-z_][A-Za-z0-9_:-]*""")
    private val operatorPattern = Regex("""(?:~=|\|=|\^=|\$=|\*=|=)""")

    override fun match(context: MatchContext): RuleMatch? {
        if (
            context.source[context.index] != '[' ||
            context.isInDeclarationValue()
        ) {
            return null
        }

        val emitter = TokenEmitter()
        emitter.token("[", TokenScope.PUNCTUATION)
        var cursor = context.index + 1
        var matchedName = false
        while (cursor < context.endIndex && context.source[cursor] != ']') {
            when {
                context.source[cursor].isWhitespace() -> {
                    val start = cursor
                    while (cursor < context.endIndex && context.source[cursor].isWhitespace()) {
                        cursor++
                    }
                    emitter.plain(context.source.substring(start, cursor))
                }
                context.source[cursor] == '"' || context.source[cursor] == '\'' -> {
                    val start = cursor
                    cursor = skipString(context, cursor, context.source[cursor])
                    emitter.token(
                        context.source.substring(start, cursor),
                        TokenScope.ATTR_VALUE,
                    )
                }
                else -> {
                    val operator = operatorPattern.find(context.source, cursor)
                        ?.takeIf { it.range.first == cursor }
                    val name = namePattern.find(context.source, cursor)
                        ?.takeIf { it.range.first == cursor }
                    when {
                        operator != null -> {
                            emitter.token(operator.value, TokenScope.OPERATOR)
                            cursor = operator.range.last + 1
                        }
                        name != null -> {
                            emitter.token(
                                name.value,
                                if (matchedName) TokenScope.ATTR_VALUE else TokenScope.ATTR_NAME,
                            )
                            matchedName = true
                            cursor = name.range.last + 1
                        }
                        else -> {
                            emitter.plain(context.source[cursor].toString())
                            cursor++
                        }
                    }
                }
            }
        }
        if (cursor < context.endIndex && context.source[cursor] == ']') {
            emitter.token("]", TokenScope.PUNCTUATION)
            cursor++
        }
        return RuleMatch(
            endIndex = cursor,
            tokens = emitter.build(),
            nextKind = LexemeKind.Value,
        )
    }

    private fun skipString(context: MatchContext, startIndex: Int, quote: Char): Int {
        var cursor = startIndex + 1
        while (cursor < context.endIndex) {
            when {
                context.source[cursor] == '\\' -> {
                    cursor = (cursor + 2).coerceAtMost(context.endIndex)
                }
                context.source[cursor] == quote -> return cursor + 1
                else -> cursor++
            }
        }
        return cursor
    }
}
