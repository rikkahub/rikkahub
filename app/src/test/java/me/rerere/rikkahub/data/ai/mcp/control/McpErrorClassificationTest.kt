package me.rerere.rikkahub.data.ai.mcp.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the McpStatus.Error → (error_kind, hint) mapping. The classifier sits in front
 * of the LLM-facing rollback envelope, so a regression here would silently start telling
 * users (and the model) the wrong root cause for failed `mcp_add` calls.
 *
 * The strings under test are real exception messages observed in the wild on this fork
 * (e.g. the Python-MCP-server-with-missing-name-field repro on 2026-05-09).
 */
class McpErrorClassificationTest {

    @Test
    fun `kotlinx-serialization missing field maps to remote_invalid_tool_def`() {
        val (kind, hint) = classifyMcpError(
            "Field 'name' is required for type with serial name 'io.modelcontextprotocol.kotlin.sdk.types.Tool', " +
                "but it was missing"
        )
        assertEquals("remote_invalid_tool_def", kind)
        assertTrue("hint mentions name field", hint.contains("name", ignoreCase = true))
    }

    @Test
    fun `MissingFieldException class name maps to remote_invalid_response`() {
        val (kind, _) = classifyMcpError(
            "kotlinx.serialization.MissingFieldException: Fields [version] are required"
        )
        assertEquals("remote_invalid_response", kind)
    }

    @Test
    fun `connect refused maps to connect_failed with bind hint`() {
        val (kind, hint) = classifyMcpError(
            "Error connecting to transport: Failed to connect to /192.168.100.6:5000"
        )
        assertEquals("connect_failed", kind)
        assertTrue("hint suggests 0.0.0.0 bind", hint.contains("0.0.0.0"))
    }

    @Test
    fun `ConnectException class name also maps to connect_failed`() {
        val (kind, _) = classifyMcpError("java.net.ConnectException: Connection refused")
        assertEquals("connect_failed", kind)
    }

    @Test
    fun `read timeout maps to request_timeout`() {
        val (kind, _) = classifyMcpError(
            "java.net.SocketTimeoutException: Read timed out"
        )
        assertEquals("request_timeout", kind)
    }

    @Test
    fun `connect timeout also maps to request_timeout`() {
        val (kind, _) = classifyMcpError(
            "java.net.SocketTimeoutException: connect timed out"
        )
        assertEquals("request_timeout", kind)
    }

    @Test
    fun `unknown host maps to host_not_found`() {
        val (kind, _) = classifyMcpError(
            "java.net.UnknownHostException: nope.example.com"
        )
        assertEquals("host_not_found", kind)
    }

    @Test
    fun `401 auth maps to auth_required`() {
        val (kind, _) = classifyMcpError("HTTP 401 Unauthorized")
        assertEquals("auth_required", kind)
    }

    @Test
    fun `403 forbidden maps to auth_forbidden`() {
        val (kind, _) = classifyMcpError("HTTP 403 Forbidden")
        assertEquals("auth_forbidden", kind)
    }

    @Test
    fun `404 maps to endpoint_not_found`() {
        val (kind, hint) = classifyMcpError("HTTP 404 Not Found")
        assertEquals("endpoint_not_found", kind)
        assertTrue("hint mentions /mcp or /sse path guess", hint.contains("/mcp"))
    }

    @Test
    fun `unknown shape falls back to connect_failed`() {
        val (kind, _) = classifyMcpError("some bizarre opaque error")
        assertEquals("connect_failed", kind)
    }

    @Test
    fun `classification is case-insensitive`() {
        val (kind, _) = classifyMcpError(
            "JAVA.NET.SOCKETTIMEOUTEXCEPTION: READ TIMED OUT"
        )
        assertEquals("request_timeout", kind)
    }
}
