package me.rerere.highlight.kotlin.engine

import me.rerere.highlight.HighlightToken

internal class TokenEmitter {
    private val tokens = mutableListOf<HighlightToken>()

    fun append(token: HighlightToken) {
        when (token) {
            is HighlightToken.Plain -> plain(token.content)
            is HighlightToken.Styled -> token(token.content, token.type)
        }
    }

    fun appendAll(newTokens: List<HighlightToken>) {
        newTokens.forEach(::append)
    }

    fun plain(content: String) {
        if (content.isEmpty()) return

        val previous = tokens.lastOrNull()
        if (previous is HighlightToken.Plain) {
            tokens[tokens.lastIndex] = HighlightToken.Plain(previous.content + content)
        } else {
            tokens += HighlightToken.Plain(content)
        }
    }

    fun token(content: String, type: String) {
        if (content.isEmpty()) return

        val previous = tokens.lastOrNull()
        if (previous is HighlightToken.Styled && previous.type == type) {
            val merged = previous.content + content
            tokens[tokens.lastIndex] = previous.copy(content = merged)
        } else {
            tokens += HighlightToken.Styled(
                content = content,
                type = type,
            )
        }
    }

    fun build(): List<HighlightToken> = tokens.toList()
}
