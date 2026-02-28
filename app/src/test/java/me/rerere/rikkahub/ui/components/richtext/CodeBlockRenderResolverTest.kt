package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
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
    fun build_html_for_html_code_is_wrapped_and_keeps_payload() {
        val target = CodeBlockRenderTarget(
            normalizedLanguage = "html",
            renderType = CodeBlockRenderType.HTML
        )
        val code = "<span>hello</span>"
        val html = CodeBlockRenderResolver.buildHtmlForWebView(target, code)
        assertTrue(html.contains("<!DOCTYPE html>", ignoreCase = true))
        assertTrue(html.contains("<meta name=\"viewport\""))
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
}
