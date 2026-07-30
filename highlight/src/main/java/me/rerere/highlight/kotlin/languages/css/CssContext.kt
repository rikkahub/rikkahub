package me.rerere.highlight.kotlin.languages.css

import me.rerere.highlight.kotlin.engine.MatchContext

internal fun MatchContext.isInDeclarationValue(position: Int = index): Boolean {
    val blockStart = findContainingBlock(position) ?: return false
    var cursor = blockStart + 1
    var segmentStart = cursor
    var parentheses = 0
    var brackets = 0
    var quote: Char? = null
    var colon = -1

    while (cursor < position) {
        val char = source[cursor]
        when {
            quote != null -> {
                when {
                    char == '\\' -> cursor++
                    char == quote -> quote = null
                }
            }
            char == '"' || char == '\'' -> quote = char
            source.startsWith("/*", cursor) -> {
                val commentEnd = source.indexOf("*/", cursor + 2)
                cursor = if (commentEnd == -1 || commentEnd >= position) {
                    position
                } else {
                    commentEnd + 1
                }
            }
            char == '(' -> parentheses++
            char == ')' && parentheses > 0 -> parentheses--
            char == '[' -> brackets++
            char == ']' && brackets > 0 -> brackets--
            parentheses == 0 && brackets == 0 && char == ';' -> {
                segmentStart = cursor + 1
                colon = -1
            }
            parentheses == 0 && brackets == 0 && char == ':' && colon == -1 -> colon = cursor
        }
        cursor++
    }

    if (colon == -1) return false
    val property = source.substring(segmentStart, colon).trim()
    return CssGrammar.propertyPattern.matches(property)
}

internal fun MatchContext.isInSelector(position: Int = index): Boolean {
    if (isInDeclarationValue(position)) return false

    var cursor = position
    var quote: Char? = null
    var parentheses = 0
    var brackets = 0
    while (cursor < endIndex) {
        val char = source[cursor]
        when {
            quote != null -> {
                when {
                    char == '\\' -> cursor++
                    char == quote -> quote = null
                }
            }
            char == '"' || char == '\'' -> quote = char
            source.startsWith("/*", cursor) -> {
                val commentEnd = source.indexOf("*/", cursor + 2)
                cursor = if (commentEnd == -1) endIndex else commentEnd + 1
            }
            char == '(' -> parentheses++
            char == ')' && parentheses > 0 -> parentheses--
            char == '[' -> brackets++
            char == ']' && brackets > 0 -> brackets--
            parentheses == 0 && brackets == 0 && char == '{' -> return true
            parentheses == 0 && brackets == 0 && (char == ';' || char == '}') -> return false
        }
        cursor++
    }
    return false
}

private fun MatchContext.findContainingBlock(position: Int): Int? {
    val openings = ArrayDeque<Int>()
    var cursor = 0
    var quote: Char? = null
    while (cursor < position) {
        val char = source[cursor]
        when {
            quote != null -> {
                when {
                    char == '\\' -> cursor++
                    char == quote -> quote = null
                }
            }
            char == '"' || char == '\'' -> quote = char
            source.startsWith("/*", cursor) -> {
                val commentEnd = source.indexOf("*/", cursor + 2)
                cursor = if (commentEnd == -1 || commentEnd >= position) {
                    position
                } else {
                    commentEnd + 1
                }
            }
            char == '{' -> openings.addLast(cursor)
            char == '}' && openings.isNotEmpty() -> openings.removeLast()
        }
        cursor++
    }
    return openings.lastOrNull()
}
