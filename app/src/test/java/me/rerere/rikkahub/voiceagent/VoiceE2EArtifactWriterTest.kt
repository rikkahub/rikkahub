package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext

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
    fun `trace keyed writer creates active trace directory and latest marker on drain`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-trace-drain").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "VA000123",
                scope = scope,
            )

            writer.drain()

            assertEquals("VA000123", VoiceE2EArtifactPaths.latestTraceIdFile(root).readText())
            assertTrue(File(VoiceE2EArtifactPaths.rootDirectory(root), "VA000123").isDirectory)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `enabled writer persists session json under the voice trace id when provided`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-session-json").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "VA000321",
                scope = scope,
            )
            val content = """{"voiceTraceId":"VA000321","status":"started"}"""

            writer(VoiceE2EArtifact.SessionJson, content)
            writer.drain()

            assertEquals(
                content,
                File(VoiceE2EArtifactPaths.rootDirectory(root), "VA000321/session.json").readText(),
            )
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `non append writer replaces session json without leaving temp files`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-session-json-atomic").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "VA000322",
                scope = scope,
            )
            val first = """{"voiceTraceId":"VA000322","status":"started"}"""
            val second = """{"voiceTraceId":"VA000322","status":"ended"}"""

            writer.write(VoiceE2EArtifact.SessionJson, first)
            writer.drain()
            writer.write(VoiceE2EArtifact.SessionJson, second)
            writer.drain()

            val traceDirectory = File(VoiceE2EArtifactPaths.rootDirectory(root), "VA000322")
            assertEquals(second, File(traceDirectory, "session.json").readText())
            assertEquals(
                emptyList<String>(),
                traceDirectory.listFiles()
                    .orEmpty()
                    .map { it.name }
                    .filter { it.startsWith("session.json.") && it.endsWith(".tmp") },
            )
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `terminal session json write falls back when atomic move is unsupported`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-session-json-atomic-fallback").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val originalMove = VoiceE2EAtomicMoveOperation.move
        var atomicAttempted = false
        var fallbackAttempted = false
        try {
            VoiceE2EAtomicMoveOperation.move = { source, target, atomic ->
                if (atomic) {
                    atomicAttempted = true
                    throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "test fallback")
                }
                fallbackAttempted = true
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "VA000323",
                scope = scope,
            )
            val content = """{"voiceTraceId":"VA000323","status":"ended"}"""

            withTimeout(1000) {
                writer.writeTerminalSessionJson(content).await()
            }

            val traceDirectory = File(VoiceE2EArtifactPaths.rootDirectory(root), "VA000323")
            assertTrue(atomicAttempted)
            assertTrue(fallbackAttempted)
            assertEquals(content, File(traceDirectory, "session.json").readText())
            assertEquals(
                emptyList<String>(),
                traceDirectory.listFiles()
                    .orEmpty()
                    .map { it.name }
                    .filter { it.startsWith("session.json.") && it.endsWith(".tmp") },
            )
        } finally {
            VoiceE2EAtomicMoveOperation.move = originalMove
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `canceling returned terminal write does not corrupt terminal write ordering`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-session-json-cancel-returned").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val originalMove = VoiceE2EAtomicMoveOperation.move
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        try {
            VoiceE2EAtomicMoveOperation.move = { source, target, atomic ->
                if (
                    target.fileName.toString() == "session.json" &&
                    source.toFile().readText().contains("\"status\":\"started\"")
                ) {
                    firstWriteStarted.countDown()
                    releaseFirstWrite.await(1, TimeUnit.SECONDS)
                }
                originalMove(source, target, atomic)
            }
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "VA000325",
                scope = scope,
            )
            val first = """{"voiceTraceId":"VA000325","status":"started"}"""
            val second = """{"voiceTraceId":"VA000325","status":"ended"}"""

            val firstWrite = writer.writeTerminalSessionJson(first)
            withTimeout(1000) {
                while (firstWriteStarted.count > 0) {
                    delay(10)
                }
            }

            firstWrite.cancel()
            val secondWrite = writer.writeTerminalSessionJson(second)
            delay(100)
            assertFalse(secondWrite.isCompleted)

            releaseFirstWrite.countDown()
            withTimeout(1000) {
                secondWrite.await()
            }

            val traceDirectory = File(VoiceE2EArtifactPaths.rootDirectory(root), "VA000325")
            assertEquals(second, File(traceDirectory, "session.json").readText())
        } finally {
            releaseFirstWrite.countDown()
            VoiceE2EAtomicMoveOperation.move = originalMove
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `draining terminal writes waits for queued terminal session json writes`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-session-json-drain-terminal").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val originalMove = VoiceE2EAtomicMoveOperation.move
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        try {
            VoiceE2EAtomicMoveOperation.move = { source, target, atomic ->
                if (
                    target.fileName.toString() == "session.json" &&
                    source.toFile().readText().contains("\"status\":\"started\"")
                ) {
                    firstWriteStarted.countDown()
                    releaseFirstWrite.await(1, TimeUnit.SECONDS)
                }
                originalMove(source, target, atomic)
            }
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "VA000326",
                scope = scope,
            )
            val first = """{"voiceTraceId":"VA000326","status":"started"}"""
            val second = """{"voiceTraceId":"VA000326","status":"failed"}"""

            writer.writeTerminalSessionJson(first)
            withTimeout(1000) {
                while (firstWriteStarted.count > 0) {
                    delay(10)
                }
            }
            writer.writeTerminalSessionJson(second)

            val drainJob = launch {
                writer.drainTerminalWrites()
            }
            delay(100)
            assertFalse(drainJob.isCompleted)

            releaseFirstWrite.countDown()
            withTimeout(1000) {
                drainJob.join()
            }

            val traceDirectory = File(VoiceE2EArtifactPaths.rootDirectory(root), "VA000326")
            assertEquals(second, File(traceDirectory, "session.json").readText())
        } finally {
            releaseFirstWrite.countDown()
            VoiceE2EAtomicMoveOperation.move = originalMove
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `terminal session json write survives caller cancellation without flushing unrelated pending artifacts`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-session-json-targeted").toFile()
        val job = SupervisorJob().also { it.cancel() }
        val scope = CoroutineScope(coroutineContext + job)
        try {
            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "VA000324",
                scope = scope,
            )
            val stale = """{"voiceTraceId":"VA000324","status":"started"}"""
            val content = """{"voiceTraceId":"VA000324","status":"ended"}"""

            writer.write(VoiceE2EArtifact.SessionJson, stale)
            writer.write(VoiceE2EArtifact.HermesEvents, """{"event":"pending"}""")
            withTimeout(1000) {
                writer.writeTerminalSessionJson(content).await()
            }

            val traceDirectory = File(VoiceE2EArtifactPaths.rootDirectory(root), "VA000324")
            assertEquals(content, File(traceDirectory, "session.json").readText())
            assertFalse(File(traceDirectory, "hermes-events.ndjson").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `enabled writer prunes older trace artifact directories`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-trace-retention").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val baseDirectory = VoiceE2EArtifactPaths.rootDirectory(root)
            createTraceArtifactDirectory(root, "trace-oldest", lastModified = 1_000)
            (1..10).forEach { index ->
                createTraceArtifactDirectory(
                    root = root,
                    traceId = "trace-kept-$index",
                    lastModified = 2_000L + index,
                )
            }

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
                File(baseDirectory, "trace-active/input-transcript.txt").readText(),
            )
            (2..10).forEach { index ->
                assertTrue(File(baseDirectory, "trace-kept-$index").isDirectory)
            }
            assertFalse(File(baseDirectory, "trace-kept-1").exists())
            assertFalse(File(baseDirectory, "trace-oldest").exists())
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
            val baseDirectory = VoiceE2EArtifactPaths.rootDirectory(root)
            createTraceArtifactDirectory(root, "trace-oldest", lastModified = 1_000)
            (1..10).forEach { index ->
                createTraceArtifactDirectory(
                    root = root,
                    traceId = "trace-safe-$index",
                    lastModified = 2_000L + index,
                )
            }
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
            assertTrue(File(baseDirectory, "trace-safe-10").isDirectory)
            assertFalse(File(baseDirectory, "trace-safe-1").exists())
            assertFalse(File(baseDirectory, "trace-oldest").exists())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `trace retention pruning does not follow nested symlinks outside voice e2e`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-trace-retention-symlink").toFile()
        val sentinelDirectory = Files.createTempDirectory("voice-e2e-retention-sentinel").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val baseDirectory = VoiceE2EArtifactPaths.rootDirectory(root)
            val sentinelFile = File(sentinelDirectory, "sentinel.txt")
            sentinelFile.writeText("outside data")
            val oldTraceDirectory = createTraceArtifactDirectory(root, "trace-oldest", lastModified = 1_000)
            val nestedDirectory = File(oldTraceDirectory, "nested").apply { mkdirs() }
            Files.createSymbolicLink(
                File(nestedDirectory, "outside-link").toPath(),
                sentinelDirectory.toPath(),
            )
            assertTrue("failed to restore modified time for trace-oldest", oldTraceDirectory.setLastModified(1_000))
            (1..10).forEach { index ->
                createTraceArtifactDirectory(
                    root = root,
                    traceId = "trace-kept-$index",
                    lastModified = 2_000L + index,
                )
            }

            val writer = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "trace-active",
                scope = scope,
            )

            writer.drain()

            assertFalse(File(baseDirectory, "trace-oldest").exists())
            assertTrue(sentinelFile.isFile)
            assertEquals("outside data", sentinelFile.readText())
        } finally {
            scope.cancel()
            root.deleteRecursively()
            sentinelDirectory.deleteRecursively()
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
    fun `default factory writer boundary keeps artifacts enabled even if launch config disables them`() = runBlocking {
        val root = Files.createTempDirectory("voice-e2e-factory-always-enabled").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writer = createDefaultVoiceE2EArtifactWriter(
                noBackupFilesDir = root,
                traceContext = VoiceTraceContext(traceId = "trace-test", voiceSessionId = "session-test"),
                scope = scope,
            )

            writer(VoiceE2EArtifact.HermesAnswer, "private answer")
            writer.drain()

            assertEquals("private answer", File(root, "voice-e2e/trace-test/hermes-answer.txt").readText())
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

    private fun createTraceArtifactDirectory(
        root: File,
        traceId: String,
        lastModified: Long,
    ): File {
        val directory = File(VoiceE2EArtifactPaths.rootDirectory(root), traceId)
        directory.mkdirs()
        File(directory, "input-transcript.txt").writeText("private artifact for $traceId")
        assertTrue("failed to set modified time for $traceId", directory.setLastModified(lastModified))
        return directory
    }
}
