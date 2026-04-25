package me.rerere.rikkahub.ui.components.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

// ESC [ ... m  (SGR sequences only)
private val SGR_REGEX = Regex("\u001B\\[([0-9;]*)m")

// Also strip other escape sequences we don't handle (cursor movement, etc.)
private val OTHER_ESC_REGEX = Regex("\u001B\\[[0-9;]*[A-HJKSTfihlmnsu]")

/**
 * Parse ANSI SGR escape sequences in [text] and return a styled [AnnotatedString].
 * Unrecognized escape sequences are stripped from the output.
 */
fun parseAnsi(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var style = AnsiStyle()
    var spanOpen = false
    var lastEnd = 0

    fun closeSpan() {
        if (spanOpen) {
            builder.pop()
            spanOpen = false
        }
    }

    fun pushSpan() {
        closeSpan()
        builder.pushStyle(style.toSpanStyle())
        spanOpen = true
    }

    SGR_REGEX.findAll(text).forEach { match ->
        // Append plain text before this escape, stripping other escape sequences
        val plain = text.substring(lastEnd, match.range.first)
            .replace(OTHER_ESC_REGEX, "")
        if (plain.isNotEmpty()) {
            builder.append(plain)
        }

        // Parse SGR codes and update style
        val raw = match.groupValues[1]
        val codes = if (raw.isEmpty()) listOf(0) else raw.split(";").mapNotNull { it.toIntOrNull() }
        style = style.applySGR(codes)
        pushSpan()

        lastEnd = match.range.last + 1
    }

    // Remaining text after last escape
    val tail = text.substring(lastEnd).replace(OTHER_ESC_REGEX, "")
    if (tail.isNotEmpty()) {
        builder.append(tail)
    }
    closeSpan()

    return builder.toAnnotatedString()
}

// ---------------------------------------------------------------------------
// Style state
// ---------------------------------------------------------------------------

private data class AnsiStyle(
    val fg: Color? = null,
    val bg: Color? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val reverse: Boolean = false,
) {
    fun toSpanStyle(): SpanStyle {
        val foreground = when {
            reverse -> bg ?: TerminalColors.Background
            else -> fg ?: TerminalColors.Text
        }
        val background = when {
            reverse -> fg ?: TerminalColors.Text
            else -> bg ?: Color.Transparent
        }
        val resolvedFg = if (dim) foreground.copy(alpha = foreground.alpha * 0.6f) else foreground

        return SpanStyle(
            color = resolvedFg,
            background = background,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = when {
                underline && strikethrough -> TextDecoration.combine(
                    listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                )
                underline -> TextDecoration.Underline
                strikethrough -> TextDecoration.LineThrough
                else -> TextDecoration.None
            },
        )
    }

    fun applySGR(codes: List<Int>): AnsiStyle {
        var s = this
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> s = AnsiStyle()
                1 -> s = s.copy(bold = true, dim = false)
                2 -> s = s.copy(dim = true, bold = false)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(reverse = true)
                9 -> s = s.copy(strikethrough = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(reverse = false)
                29 -> s = s.copy(strikethrough = false)
                39 -> s = s.copy(fg = null)
                49 -> s = s.copy(bg = null)
                in 30..37 -> s = s.copy(fg = ANSI_COLORS[code - 30])
                in 40..47 -> s = s.copy(bg = ANSI_COLORS[code - 40])
                in 90..97 -> s = s.copy(fg = ANSI_BRIGHT_COLORS[code - 90])
                in 100..107 -> s = s.copy(bg = ANSI_BRIGHT_COLORS[code - 100])
                38 -> {
                    val color = parseExtendedColor(codes, i + 1)
                    if (color != null) {
                        s = s.copy(fg = color.first)
                        i += color.second
                    }
                }
                48 -> {
                    val color = parseExtendedColor(codes, i + 1)
                    if (color != null) {
                        s = s.copy(bg = color.first)
                        i += color.second
                    }
                }
            }
            i++
        }
        return s
    }
}

/** Returns (Color, number of extra codes consumed) or null if malformed. */
private fun parseExtendedColor(codes: List<Int>, start: Int): Pair<Color, Int>? {
    return when (codes.getOrNull(start)) {
        5 -> {
            // 256-color palette
            val index = codes.getOrNull(start + 1) ?: return null
            Pair(ansi256ToColor(index), 2)
        }
        2 -> {
            // 24-bit RGB
            val r = codes.getOrNull(start + 1) ?: return null
            val g = codes.getOrNull(start + 2) ?: return null
            val b = codes.getOrNull(start + 3) ?: return null
            Pair(Color(r, g, b), 4)
        }
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Color tables
// ---------------------------------------------------------------------------

/** Standard 8 ANSI colors (codes 30-37 / 40-47) */
private val ANSI_COLORS = arrayOf(
    Color(0xFF000000), // Black
    Color(0xFFCD3131), // Red
    Color(0xFF0DBC79), // Green
    Color(0xFFE5E510), // Yellow
    Color(0xFF2472C8), // Blue
    Color(0xFFBC3FBC), // Magenta
    Color(0xFF11A8CD), // Cyan
    Color(0xFFE5E5E5), // White
)

/** Bright variants (codes 90-97 / 100-107) */
private val ANSI_BRIGHT_COLORS = arrayOf(
    Color(0xFF666666), // Bright Black (dark gray)
    Color(0xFFF14C4C), // Bright Red
    Color(0xFF23D18B), // Bright Green
    Color(0xFFF5F543), // Bright Yellow
    Color(0xFF3B8EEA), // Bright Blue
    Color(0xFFD670D6), // Bright Magenta
    Color(0xFF29B8DB), // Bright Cyan
    Color(0xFFFFFFFF), // Bright White
)

/**
 * Convert a 256-color palette index to a [Color].
 *
 * 0-7   → standard colors
 * 8-15  → bright colors
 * 16-231 → 6×6×6 color cube
 * 232-255 → grayscale ramp
 */
private fun ansi256ToColor(index: Int): Color {
    return when {
        index < 8 -> ANSI_COLORS[index]
        index < 16 -> ANSI_BRIGHT_COLORS[index - 8]
        index < 232 -> {
            val i = index - 16
            val b = i % 6
            val g = (i / 6) % 6
            val r = i / 36
            Color(
                red = if (r == 0) 0 else 55 + r * 40,
                green = if (g == 0) 0 else 55 + g * 40,
                blue = if (b == 0) 0 else 55 + b * 40,
            )
        }
        else -> {
            val v = 8 + (index - 232) * 10
            Color(v, v, v)
        }
    }
}
