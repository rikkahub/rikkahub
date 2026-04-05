package me.rerere.rikkahub.data.ai.tools.termux

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val TERMUX_MCP_STDIO_DEFAULT_SERVER_PORT = 9072
internal const val TERMUX_MCP_STDIO_DEFAULT_WAIT_TIME_MS = 250L
internal const val TERMUX_MCP_STDIO_DEFAULT_MAX_BYTES = 32 * 1024

/**
 * Bump this when the embedded stdio bridge Python server changes incompatibly.
 */
internal const val TERMUX_MCP_STDIO_SERVER_VERSION = 1

@Serializable
internal data class TermuxMcpStdioStartRequest(
    val command: String,
    val args: List<String> = emptyList(),
    val workdir: String,
    val env: Map<String, String> = emptyMap(),
)

@Serializable
internal data class TermuxMcpStdioReadRequest(
    val stream: String,
    @SerialName("wait_time_ms")
    val waitTimeMs: Long = TERMUX_MCP_STDIO_DEFAULT_WAIT_TIME_MS,
    @SerialName("max_bytes")
    val maxBytes: Int = TERMUX_MCP_STDIO_DEFAULT_MAX_BYTES,
)

@Serializable
internal data class TermuxMcpStdioWriteRequest(
    @SerialName("data_base64")
    val dataBase64: String = "",
)

@Serializable
data class TermuxMcpStdioStartResponse(
    @SerialName("session_id")
    val sessionId: String? = null,
    val running: Boolean = false,
    @SerialName("exit_code")
    val exitCode: Int? = null,
    val error: String? = null,
)

@Serializable
data class TermuxMcpStdioReadResponse(
    @SerialName("data_base64")
    val dataBase64: String = "",
    val eof: Boolean = false,
    val running: Boolean = false,
    @SerialName("exit_code")
    val exitCode: Int? = null,
    val error: String? = null,
)

@Serializable
data class TermuxMcpStdioActionResponse(
    val success: Boolean = true,
    val running: Boolean = false,
    @SerialName("exit_code")
    val exitCode: Int? = null,
    val error: String? = null,
)

@Serializable
internal data class TermuxMcpStdioHealthResponse(
    val ok: Boolean = false,
    val version: Int? = null,
)

internal enum class TermuxMcpStdioStream(val wireName: String) {
    Stdout("stdout"),
    Stderr("stderr"),
}
