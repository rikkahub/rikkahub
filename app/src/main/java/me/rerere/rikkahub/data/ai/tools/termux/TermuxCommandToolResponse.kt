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
)

internal fun TermuxResult.toToolResponse(): TermuxCommandToolResponse {
    val status = TermuxOutputFormatter.statusSummary(this).takeIf { it.isNotBlank() }
    return TermuxCommandToolResponse(
        output = TermuxOutputFormatter.merge(stdout = stdout, stderr = stderr, errMsg = status),
        exitCode = exitCode,
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
        output = message,
    )
}

fun TermuxCommandToolResponse.encode(json: Json): String {
    return json.encodeToString(this)
}
