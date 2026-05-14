package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.style.TextDirection

/**
 * Resolves the paragraph direction of a piece of text from its first strong
 * directional character, following the Unicode Bidi Algorithm rule P2/P3:
 *
 * - First strong RTL character (Arabic, Hebrew, etc.) -> [TextDirection.Rtl]
 * - First strong LTR character (Latin, CJK, etc.)      -> [TextDirection.Ltr]
 * - No strong directional character (digits, symbols,
 *   whitespace, emoji only)                            -> [TextDirection.Content]
 *
 * Pure and side-effect free so it can be unit tested directly.
 */
fun resolveTextDirection(text: String): TextDirection {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        when (strongDirectionOf(cp)) {
            StrongDirection.RTL -> return TextDirection.Rtl
            StrongDirection.LTR -> return TextDirection.Ltr
            StrongDirection.NONE -> { /* keep scanning */ }
        }
        i += Character.charCount(cp)
    }
    return TextDirection.Content
}

private enum class StrongDirection { LTR, RTL, NONE }

/**
 * Classifies a single Unicode code point as a strong LTR, strong RTL, or
 * neutral character. RTL ranges cover Arabic, Hebrew, Syriac, Thaana, and the
 * Arabic supplement/extended/presentation blocks (which also cover Persian and
 * Urdu, as they use the Arabic script).
 */
private fun strongDirectionOf(cp: Int): StrongDirection {
    return when (cp) {
        // Hebrew
        in 0x0590..0x05FF -> StrongDirection.RTL
        // Arabic
        in 0x0600..0x06FF -> StrongDirection.RTL
        // Syriac
        in 0x0700..0x074F -> StrongDirection.RTL
        // Arabic Supplement
        in 0x0750..0x077F -> StrongDirection.RTL
        // Thaana
        in 0x0780..0x07BF -> StrongDirection.RTL
        // Arabic Extended-A
        in 0x08A0..0x08FF -> StrongDirection.RTL
        // Arabic Presentation Forms-A
        in 0xFB50..0xFDFF -> StrongDirection.RTL
        // Arabic Presentation Forms-B
        in 0xFE70..0xFEFF -> StrongDirection.RTL
        else -> when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> StrongDirection.LTR
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> StrongDirection.RTL
            else -> StrongDirection.NONE
        }
    }
}
