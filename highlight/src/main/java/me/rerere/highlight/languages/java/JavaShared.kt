package me.rerere.highlight.languages.java

import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.mode

/**
 * The numeric literal of the Java Language Specification, ported from
 * `lib/languages/lib/java.js` of `highlight.js` 11.11.1.
 *
 * Kotlin borrows it unchanged, which is why it lives outside the grammar itself. A fresh instance
 * is built per call because compilation mutates modes in place.
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jls/se15/html/jls-3.html#jls-3.10">JLS 3.10</a>
 */
internal fun javaNumeric(): Mode {
    val decimalDigits = """[0-9](_*[0-9])*"""
    val frac = """\.($decimalDigits)"""
    val hexDigits = """[0-9a-fA-F](_*[0-9a-fA-F])*"""

    return mode {
        scope = "number"
        relevance = 0.0
        variants = listOf(
            // DecimalFloatingPointLiteral, including ExponentPart.
            {
                begin = """(\b($decimalDigits)(($frac)|\.)?|($frac))""" +
                    """[eE][+-]?($decimalDigits)[fFdD]?\b"""
            },
            // DecimalFloatingPointLiteral, excluding ExponentPart.
            { begin = """\b($decimalDigits)(($frac)[fFdD]?\b|\.([fFdD]\b)?)""" },
            { begin = """($frac)[fFdD]?\b""" },
            { begin = """\b($decimalDigits)[fFdD]\b""" },

            // HexadecimalFloatingPointLiteral.
            {
                begin = """\b0[xX](($hexDigits)\.?|($hexDigits)?\.($hexDigits))""" +
                    """[pP][+-]?($decimalDigits)[fFdD]?\b"""
            },

            // DecimalIntegerLiteral.
            { begin = """\b(0|[1-9](_*[0-9])*)[lL]?\b""" },

            // HexIntegerLiteral.
            { begin = """\b0[xX]($hexDigits)[lL]?\b""" },

            // OctalIntegerLiteral.
            { begin = """\b0(_*[0-7])*[lL]?\b""" },

            // BinaryIntegerLiteral.
            { begin = """\b0[bB][01](_*[01])*[lL]?\b""" },
        )
    }
}
