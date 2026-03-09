package me.rerere.rikkahub.data.ai.tools.termux

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

internal const val TERMUX_PTY_SERVER_PORT = 9071
internal const val TERMUX_PTY_DEFAULT_YIELD_TIME_MS = 250L
internal const val TERMUX_PTY_DEFAULT_MAX_OUTPUT_CHARS = 12_000
internal const val TERMUX_PTY_DEFAULT_COLUMNS = 120
internal const val TERMUX_PTY_DEFAULT_ROWS = 40

@Serializable
internal data class TermuxPtyStartRequest(
    val command: String,
    val workdir: String,
    @SerialName("yield_time_ms")
    val yieldTimeMs: Long = TERMUX_PTY_DEFAULT_YIELD_TIME_MS,
    @SerialName("max_output_chars")
    val maxOutputChars: Int = TERMUX_PTY_DEFAULT_MAX_OUTPUT_CHARS,
    val cols: Int = TERMUX_PTY_DEFAULT_COLUMNS,
    val rows: Int = TERMUX_PTY_DEFAULT_ROWS,
)

@Serializable
internal data class TermuxPtyWriteRequest(
    val chars: String = "",
    @SerialName("yield_time_ms")
    val yieldTimeMs: Long = TERMUX_PTY_DEFAULT_YIELD_TIME_MS,
    @SerialName("max_output_chars")
    val maxOutputChars: Int = TERMUX_PTY_DEFAULT_MAX_OUTPUT_CHARS,
)

@Serializable
data class TermuxPtyToolResponse(
    val output: String = "",
    @SerialName("session_id")
    val sessionId: String? = null,
    val running: Boolean = false,
    @SerialName("exit_code")
    val exitCode: Int? = null,
    val error: String? = null,
    val truncated: Boolean = false,
)

@Serializable
data class TermuxPtyServerResponse(
    val output: String = "",
    @SerialName("session_id")
    val sessionId: String? = null,
    val running: Boolean = false,
    @SerialName("exit_code")
    val exitCode: Int? = null,
    val error: String? = null,
    val truncated: Boolean = false,
)

internal fun TermuxPtyServerResponse.toToolResponse(): TermuxPtyToolResponse {
    return TermuxPtyToolResponse(
        output = output,
        sessionId = sessionId,
        running = running,
        exitCode = exitCode,
        error = error,
        truncated = truncated,
    )
}

fun TermuxPtyToolResponse.encode(json: Json): String {
    return json.encodeToString(this)
}
