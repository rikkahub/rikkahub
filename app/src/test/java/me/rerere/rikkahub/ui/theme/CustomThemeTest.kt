package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class CustomThemeTest {
    @Test
    fun parse_theme_token_source_accepts_css_like_declarations() {
        val result = parseThemeTokenSource(
            """
            :root {
              --primary: #11223344;
              surface-container-high: #55667788;
              on_primary_container: 0xFF010203;
              unsupported-token: #FFFFFFFF;
              background: invalid;
            }
            """.trimIndent()
        )

        assertEquals(3, result.validCount)
        assertEquals(Color(17, 34, 51, 68), result.overrides["primary"])
        assertEquals(Color(85, 102, 119, 136), result.overrides["surfaceContainerHigh"])
        assertEquals(Color(1, 2, 3, 255), result.overrides["onPrimaryContainer"])
        assertTrue("unsupported-token" in result.unsupportedKeys)
        assertEquals(1, result.invalidEntries.size)
    }

    @Test
    fun apply_theme_token_overrides_updates_matching_fields_only() {
        val base = lightColorScheme(
            primary = Color(1, 1, 1),
            onPrimary = Color(2, 2, 2),
            background = Color(3, 3, 3),
            surfaceContainerHigh = Color(4, 4, 4),
        )

        val updated = base.applyThemeTokenOverrides(
            """
            primary: #AABBCCDD;
            surfaceContainerHigh: #01020304;
            """.trimIndent()
        )

        assertEquals(Color(170, 187, 204, 221), updated.primary)
        assertEquals(Color(1, 2, 3, 4), updated.surfaceContainerHigh)
        assertEquals(base.onPrimary, updated.onPrimary)
        assertEquals(base.background, updated.background)
    }

    @Test
    fun parse_theme_color_string_accepts_uppercase_android_hex_prefix() {
        assertEquals(Color(170, 187, 204, 255), parseThemeColorString("0XFFAABBCC"))
    }

    @Test
    fun parse_theme_token_source_accepts_shape_and_scale_declarations() {
        val result = parseThemeTokenSource(
            """
            :root {
              --shape-large: 28dp;
              radius_small: 10;
              font-scale: 1.08;
              title_scale: 112%;
              body-scale: nope;
            }
            """.trimIndent()
        )

        assertEquals(4, result.validCount)
        assertEquals(28.dp, result.shapeOverrides["shapeLarge"])
        assertEquals(10.dp, result.shapeOverrides["shapeSmall"])
        assertEquals(1.08f, result.scaleOverrides["fontScale"])
        assertEquals(1.12f, result.scaleOverrides["titleScale"])
        assertEquals(1, result.invalidEntries.size)
    }

    @Test
    fun build_theme_token_template_exports_common_keys() {
        val template = buildThemeTokenTemplate(
            lightColorScheme(
                primary = Color(16, 32, 48),
                primaryContainer = Color(17, 33, 49),
                background = Color(18, 34, 50),
                surface = Color(19, 35, 51),
                surfaceContainer = Color(20, 36, 52),
                surfaceContainerHigh = Color(21, 37, 53),
                surfaceVariant = Color(22, 38, 54),
                outline = Color(23, 39, 55),
            )
        )

        assertTrue(template.contains("primary: #102030FF;"))
        assertTrue(template.contains("surfaceContainerHigh: #152535FF;"))
        assertTrue(template.contains("outline: #172737FF;"))
        assertTrue(template.contains("// shapeLarge: 24dp;"))
    }

    @Test
    fun upsert_theme_token_source_replaces_existing_css_style_variable() {
        val source = """
            :root {
              --primary: #01020304;
              outline: #05060708;
            }
        """.trimIndent()

        val updated = upsertThemeTokenSource(
            source = source,
            key = "primary",
            color = Color(170, 187, 204, 221),
        )

        assertTrue(updated.contains("primary: #AABBCCDD;"))
        assertTrue(!updated.contains("--primary: #01020304;"))
        assertTrue(updated.contains("outline: #05060708;"))
    }

    @Test
    fun apply_theme_shape_token_overrides_updates_matching_shape_slots() {
        val base = Shapes()

        val updated = base.applyThemeTokenOverrides(
            """
            shapeMedium: 18dp;
            shapeLarge: 28dp;
            """.trimIndent()
        )

        assertEquals(
            base.copy(
                medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(28.dp),
            ),
            updated,
        )
    }

    @Test
    fun apply_theme_typography_token_overrides_scales_global_and_group_sizes() {
        val base = Typography()

        val updated = base.applyThemeTokenOverrides(
            """
            fontScale: 1.10;
            bodyScale: 0.90;
            titleScale: 120%;
            """.trimIndent()
        )

        assertEquals(base.headlineSmall.fontSize * 1.10f, updated.headlineSmall.fontSize)
        assertEquals(base.bodyMedium.fontSize * 0.99f, updated.bodyMedium.fontSize)
        assertEquals(base.titleMedium.fontSize * 1.32f, updated.titleMedium.fontSize)
    }

    @Test
    fun themed_rounded_shape_uses_override_when_available() {
        val result = parseThemeTokenSource("shapeLarge: 28dp;")

        assertEquals(
            RoundedCornerShape(28.dp),
            result.themedRoundedShape(tokenKey = "shapeLarge", fallback = 24.dp),
        )
        assertEquals(
            RoundedCornerShape(12.dp),
            result.themedRoundedShape(tokenKey = "shapeSmall", fallback = 12.dp),
        )
    }

    @Test
    fun apply_theme_token_text_scale_scales_custom_text_styles() {
        val result = parseThemeTokenSource(
            """
            fontScale: 1.10;
            bodyScale: 90%;
            titleScale: 120%;
            """.trimIndent()
        )
        val bodyStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
        val titleStyle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp)

        assertEquals(
            bodyStyle.fontSize * 0.99f,
            result.applyThemeTokenTextScale(bodyStyle, ThemeTokenTextScaleGroup.BODY).fontSize,
        )
        assertEquals(
            titleStyle.lineHeight * 1.32f,
            result.applyThemeTokenTextScale(titleStyle, ThemeTokenTextScaleGroup.TITLE).lineHeight,
        )
    }
}
