package me.rerere.rikkahub.voiceagent.telemetry

import android.util.Log
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class VoiceSentryRuntimeConfigDiagnostics(
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
        internal fun fromConfig(
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

internal fun writeVoiceSentryRuntimeConfigDiagnostics(
    rootDirectory: File,
    diagnostics: VoiceSentryRuntimeConfigDiagnostics,
    warningLogger: (String) -> Unit = ::logVoiceSentryRuntimeConfigDiagnosticsWarning,
) {
    runCatching {
        val file = File(rootDirectory, "voice-e2e/android-sentry-config.json")
        val parentDirectory = file.parentFile
            ?: error("Diagnostics parent directory unavailable")
        if (!parentDirectory.exists() && !parentDirectory.mkdirs()) {
            error("Diagnostics parent directory unavailable")
        }
        if (!parentDirectory.isDirectory) {
            error("Diagnostics parent path is not a directory")
        }
        file.writeText(diagnostics.toJson())
    }.onFailure { error ->
        warningLogger(
            "Failed to write voice Sentry runtime config diagnostics (${error::class.java.simpleName})"
        )
    }
}

private fun logVoiceSentryRuntimeConfigDiagnosticsWarning(message: String) {
    Log.w(TAG, message)
}

private const val TAG = "VoiceSentryRuntimeConfigDiagnostics"
