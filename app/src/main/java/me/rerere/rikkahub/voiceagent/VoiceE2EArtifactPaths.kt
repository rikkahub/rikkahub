package me.rerere.rikkahub.voiceagent

import java.io.File

internal object VoiceE2EArtifactPaths {
    const val ROOT_DIRECTORY_NAME = "voice-e2e"
    const val LATEST_TRACE_ID_FILE_NAME = "latest-trace-id.txt"
    const val SENTRY_CONFIG_DIAGNOSTICS_FILE_NAME = "android-sentry-config.json"

    fun rootDirectory(rootDirectory: File): File =
        File(rootDirectory, ROOT_DIRECTORY_NAME)

    fun latestTraceIdFile(rootDirectory: File): File =
        File(rootDirectory(rootDirectory), LATEST_TRACE_ID_FILE_NAME)

    fun sentryConfigDiagnosticsFile(rootDirectory: File): File =
        File(rootDirectory(rootDirectory), SENTRY_CONFIG_DIAGNOSTICS_FILE_NAME)
}
