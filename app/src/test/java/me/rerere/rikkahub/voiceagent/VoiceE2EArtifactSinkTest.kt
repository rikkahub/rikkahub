package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceE2EArtifactSinkTest {

    private val diagnostics = VoiceDiagnostics()
    private val artifacts = mutableListOf<Pair<VoiceE2EArtifact, String>>()

    private fun sink(
        writeArtifact: (VoiceE2EArtifact, String) -> Unit = { artifact, content -> artifacts += artifact to content },
    ) = VoiceE2EArtifactSink(
        diagnostics = diagnostics,
        writeVoiceE2EArtifact = writeArtifact,
    )

    @Test
    fun `writeArtifactSafely delegates to the artifact writer`() {
        sink().writeArtifactSafely(VoiceE2EArtifact.HermesAnswer, "content")
        assertEquals(listOf(VoiceE2EArtifact.HermesAnswer to "content"), artifacts)
        assertEquals(emptyList<String>(), diagnostics.events.value.map { it.name })
    }

    @Test
    fun `writeArtifactSafely records a diagnostic with callId on failure`() {
        sink(writeArtifact = { _, _ -> error("disk full") })
            .writeArtifactSafely(VoiceE2EArtifact.HermesAnswer, "content", callId = "c9")
        val event = diagnostics.events.value.single { it.name == "voice_e2e_artifact_write_failed" }
        assertTrue(event.detail.contains("callId=c9"))
    }
}
