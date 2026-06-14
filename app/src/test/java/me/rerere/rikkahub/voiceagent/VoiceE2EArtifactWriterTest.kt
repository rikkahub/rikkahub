package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabMobileCredentials

class VoiceE2EArtifactWriterTest {
    @Test
    fun `disabled writer does not persist private artifacts`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-disabled").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = false,
                rootDirectory = root,
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesAnswer, "private answer")
            delay(100)

            assertFalse(File(root, "voice-e2e/hermes-answer.txt").exists())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `enabled writer persists typed artifacts under the configured root`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-enabled").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesAnswer, "private answer")

            val answerFile = File(root, "voice-e2e/hermes-answer.txt")
            withTimeout(1000) {
                while (!answerFile.isFile) {
                    delay(10)
                }
            }
            assertEquals("private answer", answerFile.readText())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `enabled writer keeps later transcript snapshots when writes are queued quickly`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-ordered").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                scope = scope,
            )
            repeat(100) { index ->
                writer(VoiceE2EArtifact.InputTranscript, "snapshot-$index")
            }
            writer.drain()

            val transcriptFile = File(root, "voice-e2e/input-transcript.txt")
            assertEquals("snapshot-99", transcriptFile.readText())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `enabled writer coalesces noisy transcripts without losing final answer artifact`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-coalesced").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                scope = scope,
            )
            repeat(1_000) { index ->
                writer(VoiceE2EArtifact.InputTranscript, "snapshot-$index")
            }
            writer(VoiceE2EArtifact.HermesAnswer, "final answer")
            repeat(1_000) { index ->
                writer(VoiceE2EArtifact.OutputTranscript, "output-$index")
            }
            writer.drain()

            assertEquals("snapshot-999", File(root, "voice-e2e/input-transcript.txt").readText())
            assertEquals("final answer", File(root, "voice-e2e/hermes-answer.txt").readText())
            assertEquals("output-999", File(root, "voice-e2e/output-transcript.txt").readText())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `enabled writer appends hermes events without coalescing them`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-hermes-events-enabled").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesEvents, """{"event":"queued"}""")
            writer(VoiceE2EArtifact.HermesEvents, """{"event":"started"}""")
            writer(VoiceE2EArtifact.HermesEvents, """{"event":"completed"}""")
            writer.drain()

            val eventsFile = File(root, "voice-e2e/hermes-events.ndjson")
            assertEquals(
                listOf(
                    """{"event":"queued"}""",
                    """{"event":"started"}""",
                    """{"event":"completed"}""",
                ),
                eventsFile.readLines(),
            )
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `enabled writer clears stale append-only hermes events before writing`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-hermes-events-stale").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val eventsFile = File(root, "voice-e2e/hermes-events.ndjson")
            requireNotNull(eventsFile.parentFile).mkdirs()
            eventsFile.writeText("""{"event":"old-private-row"}""" + "\n")

            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesEvents, """{"event":"new-row"}""")
            writer.drain()

            assertEquals(
                listOf("""{"event":"new-row"}"""),
                eventsFile.readLines(),
            )
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `append-only writer rejects multiline hermes events`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-hermes-events-multiline").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesEvents, """{"event":"queued"}""")
            writer(VoiceE2EArtifact.HermesEvents, "{\"event\":\"bad\\nobject\"}\n")
            writer(VoiceE2EArtifact.HermesEvents, "{\"event\":\"bad\\robject\"}\r")
            writer(VoiceE2EArtifact.HermesEvents, """{"event":"completed"}""")
            writer(VoiceE2EArtifact.HermesAnswer, "answer\nwith newline")
            writer.drain()

            assertEquals(
                listOf(
                    """{"event":"queued"}""",
                    """{"event":"completed"}""",
                ),
                File(root, "voice-e2e/hermes-events.ndjson").readLines(),
            )
            assertEquals("answer\nwith newline", File(root, "voice-e2e/hermes-answer.txt").readText())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `disabled writer does not persist hermes events`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-hermes-events-disabled").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val eventsFile = File(root, "voice-e2e/hermes-events.ndjson")
            requireNotNull(eventsFile.parentFile).mkdirs()
            eventsFile.writeText("""{"event":"existing-private-row"}""" + "\n")

            val writer = VoiceE2EArtifactWriter.create(
                enabled = false,
                rootDirectory = root,
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesEvents, """{"event":"queued"}""")
            writer.drain()

            assertEquals(listOf("""{"event":"existing-private-row"}"""), eventsFile.readLines())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `default factory writer boundary keeps artifacts disabled unless launch config enables them`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-factory-disabled").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = createDefaultVoiceE2EArtifactWriter(
                config = launchConfig(enableVoiceE2EArtifacts = false),
                noBackupFilesDir = root,
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesAnswer, "private answer")
            delay(100)

            assertFalse(File(root, "voice-e2e/hermes-answer.txt").exists())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `default factory writer boundary uses no backup root when launch config enables artifacts`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-factory-enabled").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = createDefaultVoiceE2EArtifactWriter(
                config = launchConfig(enableVoiceE2EArtifacts = true),
                noBackupFilesDir = root,
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesAnswer, "private answer")
            writer.drain()

            val answerFile = File(root, "voice-e2e/hermes-answer.txt")
            assertEquals("private answer", answerFile.readText())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `service start config enables voice e2e artifacts from start extra`() {
        val config = voiceAgentServiceStartConfig(
            resolvedConfig = launchConfig(enableVoiceE2EArtifacts = false),
            readBooleanExtra = { name, default ->
                assertEquals(VoiceAgentCallContract.EXTRA_ENABLE_VOICE_E2E_ARTIFACTS, name)
                assertFalse(default)
                true
            },
        )

        assertTrue(config.enableVoiceE2EArtifacts)
    }

    @Test
    fun `service start config keeps voice e2e artifacts disabled by default`() {
        val config = voiceAgentServiceStartConfig(
            resolvedConfig = launchConfig(enableVoiceE2EArtifacts = true),
            readBooleanExtra = { name, default ->
                assertEquals(VoiceAgentCallContract.EXTRA_ENABLE_VOICE_E2E_ARTIFACTS, name)
                assertFalse(default)
                false
            },
        )

        assertFalse(config.enableVoiceE2EArtifacts)
    }

    private fun launchConfig(enableVoiceE2EArtifacts: Boolean) = VoiceAgentLaunchConfig(
        voiceLabBaseUrl = "https://voice.test",
        credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-key"),
        voiceModelId = "gemini-flash",
        assistantName = "Hermes",
        assistantPrompt = "system",
        enableVoiceE2EArtifacts = enableVoiceE2EArtifacts,
    )
}
