package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the CSS parsing helpers extracted out of MarkdownNew.kt into
 * MarkdownCssParsing.kt. These lock the exact parse behaviour so the mechanical extraction
 * (issue #106) is proven behaviour-preserving.
 *
 * Note: the `#hex` branch of [parseColor] routes through `String.toColorInt()` which calls
 * android.graphics.Color (not available on the plain JVM test runtime, no Robolectric here),
 * so only the rgb()/rgba()/named/null branches are exercised.
 */
class MarkdownCssParsingTest {
    private val density = Density(density = 1f, fontScale = 1f)

    // ---- parseCssDeclarations ----

    @Test
    fun `parseCssDeclarations splits, lowercases keys, trims values`() {
        val result = MarkdownCss.parseCssDeclarations("Color: Red; Font-Weight : bold ")
        assertEquals(mapOf("color" to "Red", "font-weight" to "bold"), result)
    }

    @Test
    fun `parseCssDeclarations ignores segments without a colon`() {
        val result = MarkdownCss.parseCssDeclarations("color:red; junk; font-style:italic")
        assertEquals(mapOf("color" to "red", "font-style" to "italic"), result)
    }

    @Test
    fun `parseCssDeclarations keeps colons inside value via limit 2`() {
        val result = MarkdownCss.parseCssDeclarations("background:url(http://x)")
        assertEquals(mapOf("background" to "url(http://x)"), result)
    }

    // ---- parseColor ----

    @Test
    fun `parseColor named colors`() {
        assertEquals(Color.Red, MarkdownCss.parseColor("red"))
        assertEquals(Color.Red, MarkdownCss.parseColor("RED"))
        assertEquals(Color.Green, MarkdownCss.parseColor("green"))
        assertEquals(Color.Gray, MarkdownCss.parseColor("grey"))
    }

    @Test
    fun `parseColor rgb in range`() {
        assertEquals(Color(10, 20, 30), MarkdownCss.parseColor("rgb(10, 20, 30)"))
    }

    @Test
    fun `parseColor rgb out of range returns null`() {
        assertNull(MarkdownCss.parseColor("rgb(300, 0, 0)"))
    }

    @Test
    fun `parseColor rgba in range`() {
        assertEquals(Color(10, 20, 30, (0.5f * 255).toInt()), MarkdownCss.parseColor("rgba(10, 20, 30, 0.5)"))
    }

    @Test
    fun `parseColor rgba malformed returns null`() {
        assertNull(MarkdownCss.parseColor("rgba(10, 20, 30)"))
    }

    @Test
    fun `parseColor unknown returns null`() {
        assertNull(MarkdownCss.parseColor("notacolor"))
    }

    // ---- parseFontWeight ----

    @Test
    fun `parseFontWeight maps keywords and numerics`() {
        assertEquals(FontWeight.SemiBold, MarkdownCss.parseFontWeight("bold"))
        assertEquals(FontWeight.Normal, MarkdownCss.parseFontWeight("normal"))
        assertEquals(FontWeight.W700, MarkdownCss.parseFontWeight("700"))
        assertNull(MarkdownCss.parseFontWeight("junk"))
    }

    // ---- parseFontStyle ----

    @Test
    fun `parseFontStyle maps keywords`() {
        assertEquals(FontStyle.Italic, MarkdownCss.parseFontStyle("italic"))
        assertEquals(FontStyle.Italic, MarkdownCss.parseFontStyle("oblique"))
        assertEquals(FontStyle.Normal, MarkdownCss.parseFontStyle("normal"))
        assertNull(MarkdownCss.parseFontStyle("junk"))
    }

    // ---- parseTextDecoration ----

    @Test
    fun `parseTextDecoration single and combined`() {
        assertEquals(TextDecoration.Underline, MarkdownCss.parseTextDecoration("underline"))
        assertEquals(TextDecoration.LineThrough, MarkdownCss.parseTextDecoration("line-through"))
        assertEquals(
            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)),
            MarkdownCss.parseTextDecoration("underline line-through"),
        )
        assertNull(MarkdownCss.parseTextDecoration("none"))
    }

    // ---- parseTextAlign ----

    @Test
    fun `parseTextAlign maps keywords`() {
        assertEquals(TextAlign.Start, MarkdownCss.parseTextAlign("left"))
        assertEquals(TextAlign.Start, MarkdownCss.parseTextAlign("start"))
        assertEquals(TextAlign.Center, MarkdownCss.parseTextAlign("center"))
        assertEquals(TextAlign.End, MarkdownCss.parseTextAlign("right"))
        assertEquals(TextAlign.Justify, MarkdownCss.parseTextAlign("justify"))
        assertNull(MarkdownCss.parseTextAlign("junk"))
    }

    // ---- parseFontFamily ----

    @Test
    fun `parseFontFamily maps families`() {
        assertEquals(FontFamily.Monospace, MarkdownCss.parseFontFamily("Courier New, monospace"))
        assertEquals(FontFamily.Serif, MarkdownCss.parseFontFamily("Times New Roman"))
        assertEquals(FontFamily.SansSerif, MarkdownCss.parseFontFamily("Arial"))
        assertEquals(FontFamily.Cursive, MarkdownCss.parseFontFamily("cursive"))
        assertNull(MarkdownCss.parseFontFamily("Unknown"))
    }

    // ---- parseFontSize ----

    @Test
    fun `parseFontSize sp value`() {
        val result = MarkdownCss.parseFontSize("16sp", density, baseFontSize = 14.sp)
        assertEquals(TextUnitType.Sp, result!!.type)
        assertEquals(16f, result.value, 0.001f)
    }

    @Test
    fun `parseFontSize em value`() {
        val result = MarkdownCss.parseFontSize("1.5em", density, baseFontSize = 14.sp)
        assertEquals(TextUnitType.Em, result!!.type)
        assertEquals(1.5f, result.value, 0.001f)
    }

    @Test
    fun `parseFontSize percent scales base`() {
        val result = MarkdownCss.parseFontSize("120%", density, baseFontSize = 16.sp)
        assertEquals(TextUnitType.Sp, result!!.type)
        assertEquals(19.2f, result.value, 0.001f)
    }

    @Test
    fun `parseFontSize keyword scales base`() {
        val result = MarkdownCss.parseFontSize("xx-small", density, baseFontSize = 10.sp)
        assertEquals(TextUnitType.Sp, result!!.type)
        assertEquals(6f, result.value, 0.001f)
    }

    @Test
    fun `parseFontSize empty returns null`() {
        assertNull(MarkdownCss.parseFontSize("", density, baseFontSize = 14.sp))
    }

    // ---- parseSpacing ----

    @Test
    fun `parseSpacing px converts via density`() {
        val result = MarkdownCss.parseSpacing("2px", density, baseFontSize = 16.sp)
        // density 1f -> 2px == 2sp
        assertEquals(TextUnitType.Sp, result!!.type)
        assertEquals(2f, result.value, 0.001f)
    }

    @Test
    fun `parseSpacing percent scales base`() {
        val result = MarkdownCss.parseSpacing("50%", density, baseFontSize = 16.sp)
        assertEquals(TextUnitType.Sp, result!!.type)
        assertEquals(8f, result.value, 0.001f)
    }

    // ---- parseLineHeight ----

    @Test
    fun `parseLineHeight unitless multiplies base`() {
        val result = MarkdownCss.parseLineHeight("1.5", density, baseFontSize = 16.sp)
        assertEquals(TextUnitType.Sp, result!!.type)
        assertEquals(24f, result.value, 0.001f)
    }

    // ---- parseLegacyFontSize ----

    @Test
    fun `parseLegacyFontSize absolute level`() {
        // level 7 -> 3f * base
        val result = MarkdownCss.parseLegacyFontSize("7", density, baseFontSize = 16.sp)
        assertEquals(TextUnitType.Sp, result!!.type)
        assertEquals(48f, result.value, 0.001f)
    }

    @Test
    fun `parseLegacyFontSize relative delta`() {
        // base level 3 + 2 = level 5 -> 1.5f * base
        val result = MarkdownCss.parseLegacyFontSize("+2", density, baseFontSize = 16.sp)
        assertEquals(TextUnitType.Sp, result!!.type)
        assertEquals(24f, result.value, 0.001f)
    }

    @Test
    fun `parseLegacyFontSize relative delta clamps`() {
        // base level 3 - 5 = level -2, clamped to 1 -> 0.625f * base
        val result = MarkdownCss.parseLegacyFontSize("-5", density, baseFontSize = 16.sp)
        assertEquals(TextUnitType.Sp, result!!.type)
        assertEquals(10f, result.value, 0.001f)
        assertTrue(result.value > 0f)
    }
}
