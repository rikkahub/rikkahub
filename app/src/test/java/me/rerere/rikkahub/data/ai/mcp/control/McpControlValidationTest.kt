package me.rerere.rikkahub.data.ai.mcp.control

import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class McpControlValidationTest {

    private fun mkServer(name: String, id: Uuid = Uuid.random()): McpServerConfig =
        McpServerConfig.SseTransportServer(
            id = id,
            commonOptions = McpCommonOptions(name = name),
            url = "https://example.com/sse",
        )

    @Test fun `blank name rejected`() {
        val r = McpControlValidation.validateName("", emptyList())
        assertEquals("invalid_name", (r as McpControlValidation.Result.Reject).error)
    }

    @Test fun `whitespace-only name rejected`() {
        val r = McpControlValidation.validateName("   ", emptyList())
        assertEquals("invalid_name", (r as McpControlValidation.Result.Reject).error)
    }

    @Test fun `name over max length rejected`() {
        val long = "x".repeat(McpControlValidation.MAX_NAME_LENGTH + 1)
        val r = McpControlValidation.validateName(long, emptyList())
        assertEquals("invalid_name", (r as McpControlValidation.Result.Reject).error)
    }

    @Test fun `name at max length accepted`() {
        val ok = "x".repeat(McpControlValidation.MAX_NAME_LENGTH)
        assertTrue(McpControlValidation.validateName(ok, emptyList()) is McpControlValidation.Result.Ok)
    }

    @Test fun `case-insensitive duplicate rejected`() {
        val existing = listOf(mkServer("Fetch"))
        val r = McpControlValidation.validateName("fetch", existing)
        assertEquals("name_already_in_use", (r as McpControlValidation.Result.Reject).error)
    }

    @Test fun `duplicate name accepted when excluding the same id`() {
        val id = Uuid.random()
        val existing = listOf(mkServer("Fetch", id))
        val r = McpControlValidation.validateName("Fetch", existing, excludingId = id.toString())
        assertTrue(r is McpControlValidation.Result.Ok)
    }

    @Test fun `name trimmed before persistence`() {
        val r = McpControlValidation.validateName("  hello  ", emptyList())
        assertEquals("hello", (r as McpControlValidation.Result.Ok).value)
    }

    @Test fun `header name with CR rejected as injection`() {
        val r = McpControlValidation.validateHeaders(listOf("Foo\rBar" to "v"))
        assertEquals("invalid_header_name", (r as McpControlValidation.Result.Reject).error)
    }

    @Test fun `header name with LF rejected as injection`() {
        val r = McpControlValidation.validateHeaders(listOf("Foo\nBar" to "v"))
        assertEquals("invalid_header_name", (r as McpControlValidation.Result.Reject).error)
    }

    @Test fun `header value with CR or LF rejected`() {
        val r1 = McpControlValidation.validateHeaders(listOf("X-Trace" to "abc\rdef"))
        assertEquals("invalid_header_value", (r1 as McpControlValidation.Result.Reject).error)
        val r2 = McpControlValidation.validateHeaders(listOf("X-Trace" to "abc\ndef"))
        assertEquals("invalid_header_value", (r2 as McpControlValidation.Result.Reject).error)
    }

    @Test fun `blank header name rejected`() {
        val r = McpControlValidation.validateHeaders(listOf("" to "v"))
        assertEquals("invalid_header_name", (r as McpControlValidation.Result.Reject).error)
    }

    @Test fun `header with space in name rejected as non-token`() {
        val r = McpControlValidation.validateHeaders(listOf("Bad Header" to "v"))
        assertEquals("invalid_header_name", (r as McpControlValidation.Result.Reject).error)
    }

    @Test fun `over-cap header list rejected`() {
        val many = (0..McpControlValidation.MAX_HEADERS).map { "X-Header-$it" to "v" }
        val r = McpControlValidation.validateHeaders(many)
        assertEquals("too_many_headers", (r as McpControlValidation.Result.Reject).error)
    }

    @Test fun `valid headers accepted including duplicate names`() {
        val r = McpControlValidation.validateHeaders(
            listOf(
                "Authorization" to "Bearer abc",
                "X-Api-Key" to "k1",
                "X-Api-Key" to "k2",
                "Content-Type" to "application/json",
            )
        )
        assertTrue(r is McpControlValidation.Result.Ok)
    }
}
