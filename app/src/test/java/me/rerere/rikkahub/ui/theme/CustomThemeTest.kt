package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
