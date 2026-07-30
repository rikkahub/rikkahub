package me.rerere.highlight.kotlin.languages.javascript.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.isIdentifierPart
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.isJsxAttributePart
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.isJsxAttributeStart
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.isJsxNamePart
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.isJsxNameStart

internal object JsxRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (!isJsxTagStart(context, context.index)) return null

        val emitter = TokenEmitter()
        val endIndex = parseElement(
            context = context,
            startIndex = context.index,
            emitter = emitter,
        )
        return RuleMatch(
            endIndex = endIndex,
            tokens = emitter.build(),
            nextKind = LexemeKind.Value,
        )
    }

    private fun parseElement(
        context: MatchContext,
        startIndex: Int,
        emitter: TokenEmitter,
    ): Int {
        val openingTag = parseTag(context, startIndex, emitter)
        if (openingTag.closing || openingTag.selfClosing) return openingTag.endIndex

        var cursor = openingTag.endIndex
        while (cursor < context.endIndex) {
            if (isMatchingClosingTag(context, cursor, openingTag.name)) {
                return parseTag(context, cursor, emitter).endIndex
            }

            when {
                context.source[cursor] == '<' && isJsxTagStart(context, cursor) -> {
                    val nestedTag = inspectTag(context, cursor)
                    cursor = if (nestedTag.closing) {
                        parseTag(context, cursor, emitter).endIndex
                    } else {
                        parseElement(context, cursor, emitter)
                    }
                }

                context.source[cursor] == '{' -> {
                    emitter.token("{", TokenScope.PUNCTUATION)
                    cursor++
                    val expression = context.highlightBalanced(cursor)
                    emitter.appendAll(expression.tokens)
                    cursor = expression.endIndex
                    if (
                        cursor < context.endIndex &&
                        context.source[cursor] == '}'
                    ) {
                        emitter.token("}", TokenScope.PUNCTUATION)
                        cursor++
                    }
                }

                else -> {
                    val textStart = cursor
                    if (context.source[cursor] == '<') {
                        cursor++
                    } else {
                        while (
                            cursor < context.endIndex &&
                            context.source[cursor] != '<' &&
                            context.source[cursor] != '{'
                        ) {
                            cursor++
                        }
                    }
                    emitter.plain(context.source.substring(textStart, cursor))
                }
            }
        }
        return cursor
    }

    private fun parseTag(
        context: MatchContext,
        startIndex: Int,
        emitter: TokenEmitter,
    ): ParsedTag {
        val inspected = inspectTag(context, startIndex)
        var cursor = startIndex
        val openingLength = if (inspected.closing) 2 else 1
        emitter.token(
            content = context.source.substring(cursor, cursor + openingLength),
            type = TokenScope.PUNCTUATION,
        )
        cursor += openingLength

        if (context.source.getOrNull(cursor) == '>') {
            emitter.token(">", TokenScope.PUNCTUATION)
            return inspected.copy(endIndex = cursor + 1)
        }

        val nameStart = cursor
        while (
            cursor < context.endIndex &&
            isJsxNamePart(context.source[cursor])
        ) {
            cursor++
        }
        emitter.token(
            content = context.source.substring(nameStart, cursor),
            type = TokenScope.TAG,
        )

        while (cursor < context.endIndex) {
            when {
                context.source.startsWith("/>", cursor) -> {
                    emitter.token("/>", TokenScope.PUNCTUATION)
                    return inspected.copy(
                        endIndex = cursor + 2,
                        selfClosing = true,
                    )
                }

                context.source[cursor] == '>' -> {
                    emitter.token(">", TokenScope.PUNCTUATION)
                    return inspected.copy(endIndex = cursor + 1)
                }

                context.source[cursor].isWhitespace() -> {
                    val whitespaceStart = cursor
                    while (
                        cursor < context.endIndex &&
                        context.source[cursor].isWhitespace()
                    ) {
                        cursor++
                    }
                    emitter.plain(context.source.substring(whitespaceStart, cursor))
                }

                context.source[cursor] == '=' -> {
                    emitter.token("=", TokenScope.OPERATOR)
                    cursor++
                }

                context.source[cursor] == '\'' || context.source[cursor] == '"' -> {
                    val quote = context.source[cursor]
                    val valueStart = cursor
                    cursor++
                    while (cursor < context.endIndex) {
                        when (context.source[cursor]) {
                            '\\' -> cursor = (cursor + 2).coerceAtMost(context.endIndex)
                            quote -> {
                                cursor++
                                break
                            }

                            else -> cursor++
                        }
                    }
                    emitter.token(
                        content = context.source.substring(valueStart, cursor),
                        type = TokenScope.ATTR_VALUE,
                    )
                }

                context.source[cursor] == '{' -> {
                    emitter.token("{", TokenScope.PUNCTUATION)
                    cursor++
                    val expression = context.highlightBalanced(cursor)
                    emitter.appendAll(expression.tokens)
                    cursor = expression.endIndex
                    if (
                        cursor < context.endIndex &&
                        context.source[cursor] == '}'
                    ) {
                        emitter.token("}", TokenScope.PUNCTUATION)
                        cursor++
                    }
                }

                isJsxAttributeStart(context.source[cursor]) -> {
                    val attributeStart = cursor
                    cursor++
                    while (
                        cursor < context.endIndex &&
                        isJsxAttributePart(context.source[cursor])
                    ) {
                        cursor++
                    }
                    emitter.token(
                        content = context.source.substring(attributeStart, cursor),
                        type = TokenScope.ATTR_NAME,
                    )
                }

                else -> {
                    emitter.token(
                        content = context.source[cursor].toString(),
                        type = TokenScope.PUNCTUATION,
                    )
                    cursor++
                }
            }
        }
        return inspected.copy(endIndex = cursor)
    }

    private fun isJsxTagStart(context: MatchContext, startIndex: Int): Boolean {
        if (
            context.source.startsWith("<>", startIndex) ||
            context.source.startsWith("</>", startIndex)
        ) {
            return true
        }

        val tag = inspectTag(context, startIndex)
        if (tag.endIndex == -1 || tag.name == null) return false
        if (tag.closing || tag.selfClosing) return true

        var cursor = startIndex + 1 + tag.name.length
        while (cursor < context.endIndex && context.source[cursor].isWhitespace()) cursor++
        if (
            context.source.getOrNull(cursor) == '<' ||
            context.source.getOrNull(cursor) == ',' ||
            context.source.getOrNull(cursor) == '='
        ) {
            return false
        }
        if (
            context.source.startsWith("extends", cursor) &&
            !isIdentifierPart(context.source.getOrNull(cursor + 7) ?: '\u0000')
        ) {
            return false
        }

        return hasClosingTag(
            context = context,
            tagName = tag.name,
            startIndex = tag.endIndex,
        )
    }

    private fun inspectTag(context: MatchContext, startIndex: Int): ParsedTag {
        if (context.source.getOrNull(startIndex) != '<') return ParsedTag.Invalid

        var cursor = startIndex + 1
        val closing = context.source.getOrNull(cursor) == '/'
        if (closing) cursor++

        if (context.source.getOrNull(cursor) == '>') {
            return ParsedTag(
                name = null,
                closing = closing,
                selfClosing = false,
                endIndex = cursor + 1,
            )
        }
        if (!isJsxNameStart(context.source.getOrNull(cursor) ?: '\u0000')) {
            return ParsedTag.Invalid
        }

        val nameStart = cursor
        cursor++
        while (
            cursor < context.endIndex &&
            isJsxNamePart(context.source[cursor])
        ) {
            cursor++
        }
        val name = context.source.substring(nameStart, cursor)
        val tagEnd = findTagEnd(context, cursor)
        if (tagEnd == -1) return ParsedTag.Invalid

        return ParsedTag(
            name = name,
            closing = closing,
            selfClosing = context.source.getOrNull(tagEnd - 2) == '/',
            endIndex = tagEnd,
        )
    }

    private fun findTagEnd(context: MatchContext, startIndex: Int): Int {
        var cursor = startIndex
        var braceDepth = 0
        while (cursor < context.endIndex) {
            when (context.source[cursor]) {
                '\'', '"' -> cursor = skipQuoted(context, cursor, context.source[cursor])
                '{' -> {
                    braceDepth++
                    cursor++
                }

                '}' -> {
                    if (braceDepth > 0) braceDepth--
                    cursor++
                }

                '>' -> {
                    if (braceDepth == 0) return cursor + 1
                    cursor++
                }

                else -> cursor++
            }
        }
        return -1
    }

    private fun skipQuoted(context: MatchContext, startIndex: Int, quote: Char): Int {
        var cursor = startIndex + 1
        while (cursor < context.endIndex) {
            when (context.source[cursor]) {
                '\\' -> cursor = (cursor + 2).coerceAtMost(context.endIndex)
                quote -> return cursor + 1
                else -> cursor++
            }
        }
        return cursor
    }

    private fun hasClosingTag(
        context: MatchContext,
        tagName: String,
        startIndex: Int,
    ): Boolean {
        var cursor = context.source.indexOf("</$tagName", startIndex)
        while (cursor != -1 && cursor < context.endIndex) {
            val afterName = context.source.getOrNull(cursor + tagName.length + 2)
            if (afterName == '>' || afterName?.isWhitespace() == true) return true
            cursor = context.source.indexOf("</$tagName", cursor + 2)
        }
        return false
    }

    private fun isMatchingClosingTag(
        context: MatchContext,
        startIndex: Int,
        tagName: String?,
    ): Boolean {
        if (tagName == null) return context.source.startsWith("</>", startIndex)
        if (!context.source.startsWith("</$tagName", startIndex)) return false

        val afterName = context.source.getOrNull(startIndex + tagName.length + 2)
        return afterName == '>' || afterName?.isWhitespace() == true
    }

    private data class ParsedTag(
        val name: String?,
        val closing: Boolean,
        val selfClosing: Boolean,
        val endIndex: Int,
    ) {
        companion object {
            val Invalid = ParsedTag(
                name = null,
                closing = false,
                selfClosing = false,
                endIndex = -1,
            )
        }
    }
}
