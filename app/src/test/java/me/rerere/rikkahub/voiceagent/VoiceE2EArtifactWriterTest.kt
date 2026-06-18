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
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
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
    fun `enabled writer persists artifacts under the voice trace id when provided`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-trace-keyed").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "trace-123",
                scope = scope,
            )

            writer(VoiceE2EArtifact.InputTranscript, "user transcript")
            writer(VoiceE2EArtifact.OutputTranscript, "assistant transcript")
            writer.drain()

            assertEquals("user transcript", File(root, "voice-e2e/trace-123/input-transcript.txt").readText())
            assertEquals("assistant transcript", File(root, "voice-e2e/trace-123/output-transcript.txt").readText())
            assertEquals("trace-123", File(root, "voice-e2e/latest-trace-id.txt").readText())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `enabled writer prunes older trace artifact directories`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-trace-retention").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            createTraceArtifactDirectory(root, "trace-oldest", lastModified = 1_000)
            createTraceArtifactDirectory(root, "trace-middle", lastModified = 2_000)
            createTraceArtifactDirectory(root, "trace-newest", lastModified = 3_000)

            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "trace-active",
                scope = scope,
            )

            writer(VoiceE2EArtifact.InputTranscript, "active transcript")
            writer.drain()

            assertEquals(
                "active transcript",
                File(root, "voice-e2e/trace-active/input-transcript.txt").readText(),
            )
            assertTrue(File(root, "voice-e2e/trace-newest").isDirectory)
            assertTrue(File(root, "voice-e2e/trace-middle").isDirectory)
            assertFalse(File(root, "voice-e2e/trace-oldest").exists())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `trace retention ignores unsafe child directories and marker files`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-trace-retention-safe").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val baseDirectory = File(root, "voice-e2e")
            createTraceArtifactDirectory(root, "trace-oldest", lastModified = 1_000)
            createTraceArtifactDirectory(root, "trace-middle", lastModified = 2_000)
            createTraceArtifactDirectory(root, "trace-newest", lastModified = 3_000)
            File(baseDirectory, "unsafe trace").apply {
                mkdirs()
                File(this, "input-transcript.txt").writeText("unsafe child")
                setLastModified(500)
            }
            File(baseDirectory, "latest-trace-id.txt").writeText("trace-oldest")

            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "trace-active",
                scope = scope,
            )

            writer(VoiceE2EArtifact.OutputTranscript, "active output")
            writer.drain()

            assertTrue(File(baseDirectory, "unsafe trace").isDirectory)
            assertEquals("trace-active", File(baseDirectory, "latest-trace-id.txt").readText())
            assertFalse(File(baseDirectory, "trace-oldest").exists())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `enabled writer falls back to base directory for unsafe voice trace ids`() = runBlocking {
        listOf(".", "..", "a/b", "latest-trace-id.txt").forEachIndexed { index, traceId ->
            val root = Files.createTempDirectory("voice-e2e-unsafe-trace-$index").toFile()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val writer = VoiceE2EArtifactWriter.create(
                    enabled = true,
                    rootDirectory = root,
                    traceId = traceId,
                    scope = scope,
                )

                writer(VoiceE2EArtifact.InputTranscript, "safe fallback $index")
                writer.drain()

                assertEquals(
                    "safe fallback $index",
                    File(root, "voice-e2e/input-transcript.txt").readText(),
                )
                assertFalse(
                    "unsafe trace id must not write latest marker for $traceId",
                    File(root, "voice-e2e/latest-trace-id.txt").exists(),
                )
                assertFalse(
                    "unsafe trace id must not escape voice-e2e for $traceId",
                    File(root, "input-transcript.txt").exists(),
                )
                assertFalse(
                    "unsafe trace id must not create nested trace artifacts for $traceId",
                    File(root, "voice-e2e/a/b/input-transcript.txt").exists(),
                )
                assertFalse(
                    "unsafe trace id must not collide with latest marker path for $traceId",
                    File(root, "voice-e2e/latest-trace-id.txt/input-transcript.txt").exists(),
                )
            } finally {
                scope.cancel()
                root.deleteRecursively()
            }
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
    fun `enabled writer clears stale append-only hermes events under the voice trace id`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-hermes-events-trace-stale").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val eventsFile = File(root, "voice-e2e/trace-123/hermes-events.ndjson")
            requireNotNull(eventsFile.parentFile).mkdirs()
            eventsFile.writeText("""{"event":"old-private-row"}""" + "\n")

            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "trace-123",
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesEvents, """{"event":"new-row"}""")
            writer.drain()

            assertEquals(
                listOf("""{"event":"new-row"}"""),
                eventsFile.readLines(),
            )
            assertFalse(File(root, "voice-e2e/hermes-events.ndjson").exists())
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
                traceContext = VoiceTraceContext(traceId = "trace-test", voiceSessionId = "session-test"),
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesAnswer, "private answer")
            delay(100)

            assertFalse(File(root, "voice-e2e/trace-test/hermes-answer.txt").exists())
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
                traceContext = VoiceTraceContext(traceId = "trace-test", voiceSessionId = "session-test"),
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesAnswer, "private answer")
            writer.drain()

            val answerFile = File(root, "voice-e2e/trace-test/hermes-answer.txt")
            assertEquals("private answer", answerFile.readText())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `default factory writer boundary keys artifacts by active trace context`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-factory-trace-keyed").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = createDefaultVoiceE2EArtifactWriter(
                config = launchConfig(enableVoiceE2EArtifacts = true),
                noBackupFilesDir = root,
                traceContext = VoiceTraceContext(traceId = "trace-456", voiceSessionId = "session-456"),
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesCall, "prompt")
            writer.drain()

            assertEquals("prompt", File(root, "voice-e2e/trace-456/hermes-call.txt").readText())
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

    private fun createTraceArtifactDirectory(
        root: File,
        traceId: String,
        lastModified: Long,
    ) {
        val directory = File(root, "voice-e2e/$traceId")
        directory.mkdirs()
        File(directory, "input-transcript.txt").writeText("private artifact for $traceId")
        assertTrue("failed to set modified time for $traceId", directory.setLastModified(lastModified))
    }
}
