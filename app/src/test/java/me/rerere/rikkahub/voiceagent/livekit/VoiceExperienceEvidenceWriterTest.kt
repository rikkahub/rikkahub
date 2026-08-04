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
    fun `session binding writes one private and sanitized hash-only row`() = runBlocking {
        val root = Files.createTempDirectory("voice-experience-binding").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val artifactWriter = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                scope = scope,
            )
            val event = LiveKitVoiceExperienceEvent.SessionBinding(
                version = 1,
                voiceSessionId = "lvs_1",
                eventId = "binding_6dde1c43f223440f4bfba0ed",
                kind = "session_binding",
                observedAt = "2026-07-30T12:00:00Z",
                ownerHash = hash('1'),
                conversationHash = hash('2'),
                voiceSessionHash = "sha256:6dde1c43f223440f4bfba0ed05aa33cb837253ac01e0cadc1d223eff98914e06",
                roomHash = hash('3'),
                traceHash = hash('4'),
            )

            VoiceExperienceEvidenceWriter(artifactWriter).append(event)
            artifactWriter.drain()

            val privateLines = File(root, "voice-e2e/voice-experience-private.ndjson").readLines()
            val sanitizedLines = File(root, "voice-e2e/voice-experience-events.ndjson").readLines()
            assertEquals(1, privateLines.size)
            assertEquals(1, sanitizedLines.size)
            val sanitized = Json.parseToJsonElement(sanitizedLines.single()).jsonObject
            assertEquals(
                setOf(
                    "version",
                    "voiceSessionHash",
                    "eventId",
                    "kind",
                    "observedAt",
                    "eventHash",
                    "ownerHash",
                    "conversationHash",
                    "roomHash",
                    "traceHash",
                ),
                sanitized.keys,
            )
            assertEquals("session_binding", sanitized.getValue("kind").jsonPrimitive.content)
            assertEquals(hash('1'), sanitized.getValue("ownerHash").jsonPrimitive.content)
            assertEquals(hash('2'), sanitized.getValue("conversationHash").jsonPrimitive.content)
            assertEquals(hash('3'), sanitized.getValue("roomHash").jsonPrimitive.content)
            assertEquals(hash('4'), sanitized.getValue("traceHash").jsonPrimitive.content)
            assertFalse(sanitizedLines.single().contains("lvs_1"))
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `raw session and prompt appear only in private while sanitized keeps hashes and counts`() = runBlocking {
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
                    "voiceSessionHash",
                    "eventId",
                    "kind",
                    "observedAt",
                    "eventHash",
                    "userTurnId",
                    "requestHash",
                    "toolCallId",
                    "argumentHash",
                    "jobId",
                    "ownerHash",
                    "conversationHash",
                    "roomHash",
                    "traceHash",
                    "promptCharacterCount",
                ),
                sanitized.keys,
            )
            assertEquals("job_accepted", sanitized.getValue("kind").jsonPrimitive.content)
            assertEquals(14, sanitized.getValue("promptCharacterCount").jsonPrimitive.content.toInt())
            assertEquals(voiceSha256(raw), sanitized.getValue("eventHash").jsonPrimitive.content)
            val sanitizedText = sanitizedFile.readText()
            assertTrue(sanitizedText.contains("\"ownerHash\":\"${hash('1')}\""))
            assertTrue(sanitizedText.contains("\"conversationHash\":\"${hash('2')}\""))
            assertTrue(sanitizedText.contains("\"voiceSessionHash\":\"${voiceSha256("lvs_1")}\""))
            assertTrue(sanitizedText.contains("\"roomHash\":\"${hash('3')}\""))
            assertTrue(sanitizedText.contains("\"traceHash\":\"${hash('4')}\""))
            assertTrue(privateFile.readText().contains("\"voiceSessionId\":\"lvs_1\""))
            assertFalse(sanitizedText.contains("voiceSessionId"))
            assertFalse(sanitizedText.contains("lvs_1"))
            assertFalse(sanitizedText.contains("PRIVATE-PROMPT"))
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
                transcriptEventJson("PRIVATE-TRANSCRIPT"),
                succeededEventJson("PRIVATE-ANSWER"),
                failedEventJson("PRIVATE-FAILURE"),
            ).forEach { raw ->
                writer.append(requireNotNull(parseLiveKitVoiceExperienceEvent(raw)))
            }
            artifactWriter.drain()

            val sanitizedFile = File(root, "voice-e2e/voice-experience-events.ndjson")
            val lines = sanitizedFile.readLines()
            assertEquals(3, lines.size)
            val sanitizedText = sanitizedFile.readText()
            assertFalse(sanitizedText.contains("PRIVATE-TRANSCRIPT"))
            assertFalse(sanitizedText.contains("PRIVATE-ANSWER"))
            assertFalse(sanitizedText.contains("PRIVATE-FAILURE"))
            assertFalse(sanitizedText.contains("voiceSessionId"))
            assertFalse(sanitizedText.contains("lvs_1"))
            val events = lines.map { Json.parseToJsonElement(it).jsonObject }
            assertEquals("assistant", events[0].getValue("role").jsonPrimitive.content)
            assertEquals(18, events[0].getValue("textCharacterCount").jsonPrimitive.content.toInt())
            assertEquals(14, events[1].getValue("answerCharacterCount").jsonPrimitive.content.toInt())
            assertEquals(15, events[2].getValue("failureReasonCharacterCount").jsonPrimitive.content.toInt())
            listOf(events[1], events[2]).forEach { event ->
                assertEquals(hash('1'), event.getValue("ownerHash").jsonPrimitive.content)
                assertEquals(hash('2'), event.getValue("conversationHash").jsonPrimitive.content)
                assertEquals(voiceSha256("lvs_1"), event.getValue("voiceSessionHash").jsonPrimitive.content)
                assertEquals(hash('3'), event.getValue("roomHash").jsonPrimitive.content)
                assertEquals(hash('4'), event.getValue("traceHash").jsonPrimitive.content)
            }

            val privateText = File(root, "voice-e2e/voice-experience-private.ndjson").readText()
            assertTrue(privateText.contains("PRIVATE-TRANSCRIPT"))
            assertTrue(privateText.contains("PRIVATE-ANSWER"))
            assertTrue(privateText.contains("PRIVATE-FAILURE"))
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `follow up evidence contains identifiers and result hash without transcript answer or marker text`() = runBlocking {
        val root = Files.createTempDirectory("voice-experience-follow-up").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val artifactWriter = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                scope = scope,
            )
            val raw = followUpCorrelationEventJson()

            VoiceExperienceEvidenceWriter(artifactWriter).append(
                requireNotNull(parseLiveKitVoiceExperienceEvent(raw))
            )
            artifactWriter.drain()

            val sanitizedFile = File(root, "voice-e2e/voice-experience-events.ndjson")
            val sanitized = Json.parseToJsonElement(sanitizedFile.readLines().single()).jsonObject
            assertEquals(
                setOf(
                    "version",
                    "voiceSessionHash",
                    "eventId",
                    "kind",
                    "observedAt",
                    "eventHash",
                    "followUpTurnId",
                    "assistantTurnId",
                    "resultHash",
                ),
                sanitized.keys,
            )
            assertEquals("turn_2", sanitized.getValue("followUpTurnId").jsonPrimitive.content)
            assertEquals("assistant_2", sanitized.getValue("assistantTurnId").jsonPrimitive.content)
            val artifactText = sanitizedFile.readText()
            assertFalse(artifactText.contains("private surrounding transcript"))
            assertFalse(artifactText.contains("private Hermes answer"))
            assertFalse(artifactText.contains("VOICE-E2E-MARKER-42"))
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
    canonicalJson("""{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_accepted","kind":"job_accepted","observedAt":"2026-07-30T12:00:00Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1"${correlationJson()},"prompt":"PRIVATE-PROMPT"}""")

private fun transcriptEventJson(text: String): String =
    canonicalJson("""{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_transcript","kind":"transcript","observedAt":"2026-07-30T12:00:01Z","turnId":"turn_2","role":"assistant","text":"$text","interrupted":false}""")

private fun succeededEventJson(answer: String): String =
    canonicalJson("""{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_succeeded","kind":"job_succeeded","observedAt":"2026-07-30T12:00:02Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1"${correlationJson()},"resultHash":"${voiceSha256(answer)}","answer":"$answer"}""")

private fun failedEventJson(reason: String): String =
    canonicalJson("""{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_failed","kind":"job_failed","observedAt":"2026-07-30T12:00:03Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1"${correlationJson()},"failureReason":"$reason"}""")

private fun followUpCorrelationEventJson(): String =
    canonicalJson("""{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_follow_up","kind":"follow_up_correlation","observedAt":"2026-07-30T12:00:04Z","followUpTurnId":"turn_2","assistantTurnId":"assistant_2","resultHash":"sha256:${"3".repeat(64)}"}""")

private fun canonicalJson(payload: String): String =
    CanonicalVoiceExperienceJson.encodeObject(Json.parseToJsonElement(payload).jsonObject)

private fun correlationJson(): String =
    ""","ownerHash":"${hash('1')}","conversationHash":"${hash('2')}","voiceSessionHash":"${voiceSha256("lvs_1")}","roomHash":"${hash('3')}","traceHash":"${hash('4')}""""

private fun hash(character: Char): String = "sha256:" + character.toString().repeat(64)
