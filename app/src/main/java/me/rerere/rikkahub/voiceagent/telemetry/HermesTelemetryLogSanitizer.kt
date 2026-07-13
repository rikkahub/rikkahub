package me.rerere.rikkahub.voiceagent.telemetry

internal object HermesTelemetryLogSanitizer {
    fun failureMessage(message: String): String {
        Regex("Hermes Voice request failed \\d+").find(message)?.let { return it.value }
        return message.substringBefore(':').take(120).ifBlank { "Hermes tool failed" }
    }
}
