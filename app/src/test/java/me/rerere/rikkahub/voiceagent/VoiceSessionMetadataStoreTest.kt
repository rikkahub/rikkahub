package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceSessionMetadataStoreTest {
    @Test
    fun `latestForConversation returns newest session metadata for conversation`() {
        val root = Files.createTempDirectory("voice-session-metadata").toFile()
        writeSession(
            root = root,
            traceId = "VA-old",
            conversationId = "conversation-1",
            startedAtEpochMs = 100,
        )
        writeSession(
            root = root,
            traceId = "VA-new",
            conversationId = "conversation-1",
            startedAtEpochMs = 200,
        )
        writeSession(
            root = root,
            traceId = "VA-other",
            conversationId = "conversation-2",
            startedAtEpochMs = 300,
        )

        val session = VoiceSessionMetadataStore(root).latestForConversation("conversation-1")

        assertEquals("VA-new", session?.voiceTraceId)
        assertEquals("VA-new-session", session?.voiceSessionId)
        assertEquals("conversation-1", session?.conversationId)
    }

    @Test
    fun `latestForConversation ignores malformed session metadata`() {
        val root = Files.createTempDirectory("voice-session-metadata-malformed").toFile()
        val traceDirectory = File(VoiceE2EArtifactPaths.rootDirectory(root), "VA-bad")
        traceDirectory.mkdirs()
        File(traceDirectory, "session.json").writeText("{not json")
        writeSession(
            root = root,
            traceId = "VA-good",
            conversationId = "conversation-1",
            startedAtEpochMs = 100,
        )

        val session = VoiceSessionMetadataStore(root).latestForConversation("conversation-1")

        assertEquals("VA-good", session?.voiceTraceId)
    }

    @Test
    fun `debug display hides session id when it duplicates trace id`() {
        val display = VoiceSessionMetadata(
            voiceTraceId = "VA-same",
            voiceSessionId = "VA-same",
            conversationId = "conversation-1",
            status = "ended",
            startedAtEpochMs = 100,
        ).toDebugDisplay()

        assertEquals("VA-same", display.traceId)
        assertNull(display.sessionId)
    }

    @Test
    fun `debug display lines keep trace id as full standalone value`() {
        val lines = VoiceSessionDebugDisplay(
            traceId = "VA936914-3fb9c650b2570a7e",
            sessionId = null,
        ).debugLines()

        assertEquals(listOf(VoiceSessionDebugLine("Trace ID", "VA936914-3fb9c650b2570a7e")), lines)
    }

    @Test
    fun `debug display keeps distinct session id`() {
        val display = VoiceSessionMetadata(
            voiceTraceId = "VA-trace",
            voiceSessionId = "VS-session",
            conversationId = "conversation-1",
            status = "ended",
            startedAtEpochMs = 100,
        ).toDebugDisplay()

        assertEquals("VA-trace", display.traceId)
        assertEquals("VS-session", display.sessionId)
    }

    private fun writeSession(
        root: File,
        traceId: String,
        conversationId: String,
        startedAtEpochMs: Long,
    ) {
        val traceDirectory = File(VoiceE2EArtifactPaths.rootDirectory(root), traceId)
        traceDirectory.mkdirs()
        File(traceDirectory, "session.json").writeText(
            """
            {
              "voiceTraceId": "$traceId",
              "voiceSessionId": "$traceId-session",
              "conversationId": "$conversationId",
              "status": "ended",
              "startedAtEpochMs": $startedAtEpochMs
            }
            """.trimIndent()
        )
    }
}
