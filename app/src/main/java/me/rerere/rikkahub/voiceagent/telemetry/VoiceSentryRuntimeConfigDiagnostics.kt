package me.rerere.rikkahub.voiceagent.telemetry

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class VoiceSentryRuntimeConfigDiagnostics(
    val dsnConfigured: Boolean,
    val environmentConfigured: Boolean,
    val tracesSampleRate: Double,
    val observability: String,
) {
    val tracingEnabled: Boolean
        get() = tracesSampleRate > 0.0

    fun toJson(): String =
        Json.encodeToString(
            buildJsonObject {
                put("dsnConfigured", dsnConfigured)
                put("environmentConfigured", environmentConfigured)
                put("tracesSampleRate", tracesSampleRate)
                put("tracingEnabled", tracingEnabled)
                put("observability", observability)
            }
        )

    companion object {
        fun fromConfig(
            dsn: String,
            environment: String,
            tracesSampleRate: Double,
            observability: String,
        ): VoiceSentryRuntimeConfigDiagnostics =
            VoiceSentryRuntimeConfigDiagnostics(
                dsnConfigured = dsn.isNotBlank(),
                environmentConfigured = environment.isNotBlank(),
                tracesSampleRate = tracesSampleRate
                    .takeIf(Double::isFinite)
                    ?.coerceIn(0.0, 1.0)
                    ?: 0.0,
                observability = observability,
            )
    }
}

fun writeVoiceSentryRuntimeConfigDiagnostics(
    rootDirectory: File,
    diagnostics: VoiceSentryRuntimeConfigDiagnostics,
) {
    runCatching {
        val file = File(rootDirectory, "voice-e2e/android-sentry-config.json")
        file.parentFile?.mkdirs()
        file.writeText(diagnostics.toJson())
    }
}
