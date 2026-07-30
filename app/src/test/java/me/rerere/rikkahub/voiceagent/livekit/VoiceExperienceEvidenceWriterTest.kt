package me.rerere.rikkahub.voiceagent.livekit

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.voiceagent.VoiceE2EArtifactWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceExperienceEvidenceWriterTest {
    @Test
    fun `raw event is private while sanitized event contains only correlations and character counts`() = runBlocking {
        val root = Files.createTempDirectory("voice-experience-evidence").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val artifactWriter = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "VA-evidence",
                scope = scope,
            )
            val raw = acceptedEventJson()
            val event = requireNotNull(parseLiveKitVoiceExperienceEvent(raw))

            VoiceExperienceEvidenceWriter(artifactWriter).append(event)
            artifactWriter.drain()

            val directory = File(root, "voice-e2e/VA-evidence")
            val privateFile = File(directory, "voice-experience-private.ndjson")
            val sanitizedFile = File(directory, "voice-experience-events.ndjson")
            assertEquals(listOf(raw), privateFile.readLines())
            val sanitized = Json.parseToJsonElement(sanitizedFile.readLines().single()).jsonObject
            assertEquals(
                setOf(
                    "version",
                    "voiceSessionId",
                    "eventId",
                    "kind",
                    "observedAt",
                    "eventHash",
                    "userTurnId",
                    "requestHash",
                    "toolCallId",
                    "argumentHash",
                    "jobId",
                    "promptCharacterCount",
                ),
                sanitized.keys,
            )
            assertEquals("job_accepted", sanitized.getValue("kind").jsonPrimitive.content)
            assertEquals(16, sanitized.getValue("promptCharacterCount").jsonPrimitive.content.toInt())
            assertEquals(voiceSha256(raw), sanitized.getValue("eventHash").jsonPrimitive.content)
            assertFalse(sanitizedFile.readText().contains("private question"))
            assertEquals(OWNER_READ_WRITE, Files.getPosixFilePermissions(privateFile.toPath()))
            assertEquals(OWNER_READ_WRITE, Files.getPosixFilePermissions(sanitizedFile.toPath()))
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `sanitized transcript and terminal evidence never copy transcript answer or failure content`() = runBlocking {
        val root = Files.createTempDirectory("voice-experience-evidence-sensitive").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val artifactWriter = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                scope = scope,
            )
            val writer = VoiceExperienceEvidenceWriter(artifactWriter)
            listOf(
                transcriptEventJson("private transcript"),
                succeededEventJson("private answer"),
                failedEventJson("private failure"),
            ).forEach { raw ->
                writer.append(requireNotNull(parseLiveKitVoiceExperienceEvent(raw)))
            }
            artifactWriter.drain()

            val sanitizedFile = File(root, "voice-e2e/voice-experience-events.ndjson")
            val lines = sanitizedFile.readLines()
            assertEquals(3, lines.size)
            val sanitizedText = sanitizedFile.readText()
            assertFalse(sanitizedText.contains("private transcript"))
            assertFalse(sanitizedText.contains("private answer"))
            assertFalse(sanitizedText.contains("private failure"))
            val events = lines.map { Json.parseToJsonElement(it).jsonObject }
            assertEquals("assistant", events[0].getValue("role").jsonPrimitive.content)
            assertEquals(18, events[0].getValue("textCharacterCount").jsonPrimitive.content.toInt())
            assertEquals(14, events[1].getValue("answerCharacterCount").jsonPrimitive.content.toInt())
            assertEquals(15, events[2].getValue("failureReasonCharacterCount").jsonPrimitive.content.toInt())

            val privateText = File(root, "voice-e2e/voice-experience-private.ndjson").readText()
            assertTrue(privateText.contains("private transcript"))
            assertTrue(privateText.contains("private answer"))
            assertTrue(privateText.contains("private failure"))
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}

private val OWNER_READ_WRITE = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)

private fun acceptedEventJson(): String =
    """{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_accepted","kind":"job_accepted","observedAt":"2026-07-30T12:00:00Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1","prompt":"private question"}"""

private fun transcriptEventJson(text: String): String =
    """{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_transcript","kind":"transcript","observedAt":"2026-07-30T12:00:01Z","turnId":"turn_2","role":"assistant","text":"$text","interrupted":false}"""

private fun succeededEventJson(answer: String): String =
    """{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_succeeded","kind":"job_succeeded","observedAt":"2026-07-30T12:00:02Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1","resultHash":"${voiceSha256(answer)}","answer":"$answer"}"""

private fun failedEventJson(reason: String): String =
    """{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_failed","kind":"job_failed","observedAt":"2026-07-30T12:00:03Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1","failureReason":"$reason"}"""
