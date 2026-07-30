package me.rerere.highlight.core

import java.util.regex.Pattern

/**
 * Regular expression helpers ported from `highlight.js` 11.11.1 (`lib/core.js`).
 *
 * Grammars are written with JavaScript flavoured regular expression *sources*, exactly like
 * upstream. [compilePattern] is the single place that translates such a source into a
 * [java.util.regex.Pattern].
 */

internal fun lookahead(re: String): String = "(?=$re)"

internal fun anyNumberOfTimes(re: String): String = "(?:$re)*"

internal fun optional(re: String): String = "(?:$re)?"

internal fun concat(vararg args: String): String = args.joinToString(separator = "")

/**
 * Any of the passed expressions may match, mirroring `either()` upstream.
 *
 * [capture] wraps the alternation in a capturing group instead of a non capturing one.
 */
internal fun either(vararg args: String, capture: Boolean = false): String =
    buildString {
        append('(')
        if (!capture) append("?:")
        args.joinTo(this, separator = "|")
        append(')')
    }

internal fun either(args: List<String>, capture: Boolean = false): String =
    either(args = args.toTypedArray(), capture = capture)

/**
 * Matches an open parenthesis, a backreference, a character class or any other escape sequence.
 *
 * Character classes and escapes are matched so that their content is never mistaken for an
 * interesting element. Identical to `BACKREF_RE` upstream.
 */
private val BACKREF_RE = Regex("""\[(?:[^\\\]]|\\.)*]|\(\??|\\([1-9][0-9]*)|\\.""")

/**
 * Logically computes `regexps.join(joinWith)` while fixing up backreferences so they keep
 * matching, and places every individual expression into its own capture group.
 */
internal fun rewriteBackreferences(regexes: List<String>, joinWith: String): String {
    var numCaptures = 0
    return regexes.joinToString(separator = joinWith) { regex ->
        numCaptures += 1
        val offset = numCaptures
        var rest = regex
        val out = StringBuilder()

        while (rest.isNotEmpty()) {
            val match = BACKREF_RE.find(rest)
            if (match == null) {
                out.append(rest)
                break
            }
            out.append(rest, 0, match.range.first)
            rest = rest.substring(match.range.last + 1)

            val text = match.value
            val backreference = match.groupValues[1]
            if (text[0] == '\\' && backreference.isNotEmpty()) {
                out.append('\\').append(backreference.toInt() + offset)
            } else {
                out.append(text)
                if (text == "(") numCaptures++
            }
        }
        "($out)"
    }
}

/** Number of capturing groups declared by [re]. Mirrors `countMatchGroups()` upstream. */
internal fun countMatchGroups(re: String): Int =
    runCatching { Pattern.compile(translateJsRegex(re)).matcher("").groupCount() }
        .getOrElse { countMatchGroupsByScan(re) }

private fun countMatchGroupsByScan(re: String): Int {
    var count = 0
    var index = 0
    var inCharacterClass = false
    while (index < re.length) {
        when (re[index]) {
            '\\' -> index++
            '[' -> inCharacterClass = true
            ']' -> inCharacterClass = false
            '(' -> if (!inCharacterClass && isCapturingGroupStart(re, index)) count++
        }
        index++
    }
    return count
}

private fun isCapturingGroupStart(re: String, parenIndex: Int): Boolean {
    if (re.getOrNull(parenIndex + 1) != '?') return true
    // `(?<name>` captures, `(?<=` and `(?<!` do not.
    if (re.getOrNull(parenIndex + 2) != '<') return false
    return re.getOrNull(parenIndex + 3) !in setOf('=', '!')
}

/** Does [lexeme] start with a match of [pattern]? Mirrors `startsWith()` upstream. */
internal fun startsWith(pattern: Pattern?, lexeme: String): Boolean =
    pattern != null && pattern.matcher(lexeme).lookingAt()

/**
 * Compiles a JavaScript flavoured regular expression source into a [Pattern].
 *
 * `highlight.js` always compiles with the `m` flag, optionally adding `i` and `u`; the `g` flag has
 * no equivalent in Java and is instead expressed by the caller passing an explicit start index.
 */
internal fun compilePattern(
    source: String,
    caseInsensitive: Boolean = false,
    unicode: Boolean = false,
): Pattern {
    var flags = Pattern.MULTILINE
    if (caseInsensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    if (unicode) flags = flags or Pattern.UNICODE_CHARACTER_CLASS
    return Pattern.compile(translateJsRegex(source), flags)
}

/**
 * Rewrites the handful of constructs where JavaScript and `java.util.regex` disagree.
 *
 * - `[^]` matches any character in JavaScript but is a syntax error in Java.
 * - `[]` never matches in JavaScript but is a syntax error in Java.
 * - `[` and `&&` inside a character class are literal in JavaScript, but mean nested class union
 *   and class intersection in Java.
 * - `{` that does not open a valid quantifier is literal in JavaScript, but an error in Java.
 */
internal fun translateJsRegex(source: String): String {
    val out = StringBuilder(source.length + 8)
    var index = 0
    var inCharacterClass = false

    while (index < source.length) {
        val char = source[index]
        when {
            char == '\\' && index + 1 < source.length -> {
                out.append(char).append(source[index + 1])
                index += 2
            }

            !inCharacterClass && char == '[' -> {
                when {
                    source.startsWith("[^]", index) -> {
                        out.append("[\\s\\S]")
                        index += 3
                    }

                    source.startsWith("[]", index) -> {
                        out.append("(?!)")
                        index += 2
                    }

                    else -> {
                        inCharacterClass = true
                        out.append('[')
                        index++
                        if (source.getOrNull(index) == '^') {
                            out.append('^')
                            index++
                        }
                    }
                }
            }

            inCharacterClass && char == ']' -> {
                inCharacterClass = false
                out.append(']')
                index++
            }

            inCharacterClass && (char == '[' || char == '&') -> {
                out.append('\\').append(char)
                index++
            }

            !inCharacterClass && char == '{' && !opensQuantifier(source, index) -> {
                out.append("\\{")
                index++
            }

            else -> {
                out.append(char)
                index++
            }
        }
    }
    return out.toString()
}

/** Is the `{` at [braceIndex] the start of a `{n}`, `{n,}` or `{n,m}` quantifier? */
private fun opensQuantifier(source: String, braceIndex: Int): Boolean {
    var index = braceIndex + 1
    val digitsStart = index
    while (index < source.length && source[index].isDigit()) index++
    if (index == digitsStart) return false
    if (source.getOrNull(index) == ',') {
        index++
        while (index < source.length && source[index].isDigit()) index++
    }
    return source.getOrNull(index) == '}'
}
