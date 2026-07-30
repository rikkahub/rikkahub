package me.rerere.highlight

sealed interface HighlightToken {
    val content: String

    data class Plain(
        override val content: String,
    ) : HighlightToken

    data class Styled(
        override val content: String,
        val type: String,
    ) : HighlightToken
}
