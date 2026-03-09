package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockRenderResolverTest {
    @Test
    fun resolve_html_language() {
        val target = CodeBlockRenderResolver.resolve(
            language = "HTML",
            code = "<div>Hello</div>"
        )
        assertNotNull(target)
        assertEquals("html", target?.normalizedLanguage)
        assertEquals(CodeBlockRenderType.HTML, target?.renderType)
    }

    @Test
    fun resolve_svg_language() {
        val target = CodeBlockRenderResolver.resolve(
            language = "svg",
            code = "<svg></svg>"
        )
        assertNotNull(target)
        assertEquals("svg", target?.normalizedLanguage)
        assertEquals(CodeBlockRenderType.SVG, target?.renderType)
    }

    @Test
    fun resolve_xml_with_svg_content() {
        val target = CodeBlockRenderResolver.resolve(
            language = "xml",
            code = """
                <?xml version="1.0" encoding="UTF-8"?>
                <svg width="100" height="100"></svg>
            """.trimIndent()
        )
        assertNotNull(target)
        assertEquals("xml", target?.normalizedLanguage)
        assertEquals(CodeBlockRenderType.SVG, target?.renderType)
    }

    @Test
    fun resolve_xml_without_svg_content() {
        val target = CodeBlockRenderResolver.resolve(
            language = "xml",
            code = "<note><to>user</to></note>"
        )
        assertNull(target)
    }

    @Test
    fun resolve_other_language_returns_null() {
        val target = CodeBlockRenderResolver.resolve(
            language = "javascript",
            code = "console.log('hello');"
        )
        assertNull(target)
    }

    @Test
    fun resolve_common_mime_aliases() {
        val htmlTarget = CodeBlockRenderResolver.resolve(
            language = "text/html",
            code = "<div>Hello</div>"
        )
        val svgTarget = CodeBlockRenderResolver.resolve(
            language = "image/svg+xml",
            code = "<svg></svg>"
        )
        val xmlTarget = CodeBlockRenderResolver.resolve(
            language = "application/xml",
            code = "<svg></svg>"
        )

        assertEquals("html", htmlTarget?.normalizedLanguage)
        assertEquals(CodeBlockRenderType.HTML, htmlTarget?.renderType)
        assertEquals("svg", svgTarget?.normalizedLanguage)
        assertEquals(CodeBlockRenderType.SVG, svgTarget?.renderType)
        assertEquals("xml", xmlTarget?.normalizedLanguage)
        assertEquals(CodeBlockRenderType.SVG, xmlTarget?.renderType)
    }

    @Test
    fun resolve_xml_requires_svg_root_tag() {
        val target = CodeBlockRenderResolver.resolve(
            language = "xml",
            code = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- Example text mentioning <svg> should not trigger rich render -->
                <note><![CDATA[<svg width="16" height="16"></svg>]]></note>
            """.trimIndent()
        )

        assertNull(target)
    }

    @Test
    fun build_html_for_html_code_is_wrapped_and_keeps_payload() {
        val target = CodeBlockRenderTarget(
            normalizedLanguage = "html",
            renderType = CodeBlockRenderType.HTML
        )
        val code = "<span>hello</span>"
        val html = CodeBlockRenderResolver.buildHtmlForWebView(target, code)
        assertTrue(html.contains("<!DOCTYPE html>", ignoreCase = true))
        assertTrue(html.contains("<meta name=\"viewport\""))
        assertTrue(html.contains(CODE_BLOCK_HEIGHT_BRIDGE_NAME))
        assertTrue(html.contains("--TH-viewport-height"))
        assertTrue(html.contains(code))
    }

    @Test
    fun build_html_for_svg_code_is_wrapped_and_keeps_payload() {
        val target = CodeBlockRenderTarget(
            normalizedLanguage = "svg",
            renderType = CodeBlockRenderType.SVG
        )
        val code = """
            <?xml version="1.0" encoding="UTF-8"?>
            <svg width="80" height="80"></svg>
        """.trimIndent()
        val html = CodeBlockRenderResolver.buildHtmlForWebView(target, code)

        assertTrue(html.contains("<!DOCTYPE html>", ignoreCase = true))
        assertTrue(html.contains("<body>"))
        assertTrue(html.contains(code))
    }

    @Test
    fun build_html_converts_css_min_height_vh_to_viewport_variable() {
        val target = CodeBlockRenderTarget(
            normalizedLanguage = "html",
            renderType = CodeBlockRenderType.HTML
        )
        val code = """
            <style>
              .box { min-height: 100vh; }
              .half { min-height: 50vh; }
            </style>
            <div class="box half"></div>
        """.trimIndent()

        val html = CodeBlockRenderResolver.buildHtmlForWebView(target, code)

        assertTrue(html.contains("min-height: var(--TH-viewport-height)"))
        assertTrue(html.contains("min-height: calc(var(--TH-viewport-height) * 0.5)"))
    }

    @Test
    fun build_html_converts_inline_style_min_height_vh() {
        val target = CodeBlockRenderTarget(
            normalizedLanguage = "html",
            renderType = CodeBlockRenderType.HTML
        )
        val code = "<div style=\"padding: 4px; min-height: 75vh;\">inline</div>"

        val html = CodeBlockRenderResolver.buildHtmlForWebView(target, code)

        assertTrue(html.contains("min-height: calc(var(--TH-viewport-height) * 0.75)"))
    }

    @Test
    fun build_html_converts_javascript_min_height_vh() {
        val target = CodeBlockRenderTarget(
            normalizedLanguage = "html",
            renderType = CodeBlockRenderType.HTML
        )
        val code = """
            <script>
              el.style.minHeight = "80vh";
              el.style.setProperty('min-height', '25vh');
            </script>
        """.trimIndent()

        val html = CodeBlockRenderResolver.buildHtmlForWebView(target, code)

        assertTrue(html.contains("""el.style.minHeight = "calc(var(--TH-viewport-height) * 0.8)""""))
        assertTrue(html.contains("""setProperty('min-height', 'calc(var(--TH-viewport-height) * 0.25)')"""))
    }

    @Test
    fun build_html_does_not_convert_non_min_height_vh() {
        val target = CodeBlockRenderTarget(
            normalizedLanguage = "html",
            renderType = CodeBlockRenderType.HTML
        )
        val code = """
            <style>.box{height:100vh;}</style>
            <div class="box"></div>
        """.trimIndent()

        val html = CodeBlockRenderResolver.buildHtmlForWebView(target, code)

        assertTrue(html.contains("height:100vh"))
        assertFalse(html.contains("height:var(--TH-viewport-height)"))
    }

    @Test
    fun build_html_for_complete_document_does_not_wrap_again() {
        val target = CodeBlockRenderTarget(
            normalizedLanguage = "html",
            renderType = CodeBlockRenderType.HTML
        )
        val code = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <base href="https://example.com/assets/">
            </head>
            <body>
              <main>hello</main>
            </body>
            </html>
        """.trimIndent()

        val html = CodeBlockRenderResolver.buildHtmlForWebView(target, code)

        assertEquals(1, Regex("""<!DOCTYPE html>""", RegexOption.IGNORE_CASE).findAll(html).count())
        assertEquals(1, Regex("""<html\b""", RegexOption.IGNORE_CASE).findAll(html).count())
        assertTrue(html.contains("""<base href="https://example.com/assets/">"""))
        assertTrue(html.contains(CODE_BLOCK_HEIGHT_BRIDGE_NAME))
    }

    @Test
    fun build_html_scrollable_mode_enables_vertical_scroll() {
        val target = CodeBlockRenderTarget(
            normalizedLanguage = "html",
            renderType = CodeBlockRenderType.HTML
        )

        val html = CodeBlockRenderResolver.buildHtmlForWebView(
            target = target,
            code = "<div>hello</div>",
            scrollMode = CodeBlockRenderScrollMode.SCROLLABLE,
        )

        assertTrue(html.contains("overflow-y:auto!important"))
    }
}
