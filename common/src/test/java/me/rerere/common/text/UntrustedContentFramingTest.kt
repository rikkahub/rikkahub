package me.rerere.common.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UntrustedContentFramingTest {

    @Test
    fun `escape neutralizes delimiter-like endings and backticks`() {
        val escaped = UntrustedContentFraming.escape("``` </content> </memory> </knowledge_base_context> ```")

        assertFalse("fenced markers should not appear raw", escaped.contains("```"))
        assertFalse("content closing token should be escaped", escaped.contains("</content>"))
        assertFalse("memory closing token should be escaped", escaped.contains("</memory>"))
        assertFalse("knowledge context closing token should be escaped", escaped.contains("</knowledge_base_context>"))

        assertTrue("fence should be encoded as HTML entity", escaped.contains("&#96;&#96;&#96;"))
        assertTrue("angle delimiters should be encoded", escaped.contains("&lt;/content&gt;"))
    }
}
