package me.rerere.rikkahub.data.ai.tools.termux

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TermuxCommandToolResponse(
    val output: String = "",
    @SerialName("exit_code")
    val exitCode: Int? = null,
    @SerialName("err_code")
    val errCode: Int? = null,
    @SerialName("timed_out")
    val timedOut: Boolean = false,
    val error: String? = null,
    val success: Boolean = true,
)

internal fun TermuxResult.toToolResponse(): TermuxCommandToolResponse {
    val error = TermuxOutputFormatter.statusSummary(this).takeIf { it.isNotBlank() }
    return TermuxCommandToolResponse(
        output = TermuxOutputFormatter.merge(stdout = stdout, stderr = stderr),
        exitCode = exitCode,
        errCode = errCode,
        timedOut = timedOut,
        error = error,
        success = isSuccessful(),
    )
}

internal fun Throwable.toCommandErrorToolResponse(
    setupHint: String? = null,
): TermuxCommandToolResponse {
    val message = buildString {
        append(this@toCommandErrorToolResponse.message ?: this@toCommandErrorToolResponse.javaClass.name)
        setupHint?.trim()?.takeIf { it.isNotBlank() }?.let {
            append('\n')
            append(it)
        }
    }
    return TermuxCommandToolResponse(
        success = false,
        error = message,
    )
}

fun TermuxCommandToolResponse.encode(json: Json): String {
    return json.encodeToString(this)
}
