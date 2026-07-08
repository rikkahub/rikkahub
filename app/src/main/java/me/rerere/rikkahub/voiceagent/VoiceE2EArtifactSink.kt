package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics

/**
 * Safe E2E artifact write shared by all voice telemetry producers: never throws,
 * recording a `voice_e2e_artifact_write_failed` diagnostic on failure instead.
 */
class VoiceE2EArtifactSink(
    private val diagnostics: VoiceDiagnostics,
    private val writeVoiceE2EArtifact: (VoiceE2EArtifact, String) -> Unit,
) {
    fun writeArtifactSafely(artifact: VoiceE2EArtifact, content: String, callId: String? = null) {
        runCatching {
            writeVoiceE2EArtifact(artifact, content)
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            val callDetail = callId?.let { ", callId=$it" } ?: ""
            diagnostics.record(
                "voice_e2e_artifact_write_failed",
                "name=${artifact.fileName}$callDetail, message=$message",
            )
        }
    }
}
