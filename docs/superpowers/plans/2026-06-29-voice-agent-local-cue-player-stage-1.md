# Voice Agent Local Cue Player Stage 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split Hermes local cue playback out of assistant `VoicePlaybackWriter` while preserving the current waiting-tone behavior.

**Architecture:** Stage 1 introduces `VoiceLocalCuePlayer` as the owner of local cue playback generation, invalidation, cue sink lifecycle, and cue diagnostics. `VoicePlaybackWriter` becomes assistant-only again. `AndroidVoiceAudioEngine` keeps the public `VoiceAudioEngine` API but delegates assistant playback to `VoicePlaybackWriter` and local cue playback to `VoiceLocalCuePlayer`.

**Tech Stack:** Kotlin, Android `AudioTrack`, Kotlin coroutines, JUnit 4, Gradle `:app:testDebugUnitTest`.

---

## Scope Check

This plan implements only Stage 1 from `docs/superpowers/specs/2026-06-29-voice-agent-waiting-tone-cleanup-design.md`.

Covered in this plan:

- Add `VoiceLocalCuePlayer`.
- Move cue generation, invalidation, cue sink lifecycle, and cue diagnostics out of `VoicePlaybackWriter`.
- Return `VoicePlaybackWriter` to assistant-only playback.
- Keep caller-facing `VoiceAudioEngine` behavior unchanged.

Not covered in this plan:

- Replacing coordinator waiting-tone flags with `HermesWaitingToneEligibility`.
- Removing `VoiceAudioEngine.setLocalCueErrorHandler`.
- Extracting Android playback track ownership out of `AndroidVoiceAudioEngine`.
- Further decomposition of `VoiceAgentCoordinator` or `HermesJobManager`.

Those are separate staged PRs.

## File Structure

- Create `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`
  - Owns local cue queueing, generation/token invalidation, sink lifecycle, release, and cue diagnostics.

- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`
  - Remove local cue responsibilities.
  - Remove `VoicePlaybackSource`.
  - Change `VoicePcm16Sink.start(source)` to `start()`.
  - Change `VoicePcm16Sink.writeFully(pcm16, source)` to `writeFully(pcm16)`.
  - Keep assistant playback diagnostics only.

- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
  - Add a `VoiceLocalCuePlayer`.
  - Route `playLocalCuePcm16` and `invalidateLocalCuePlayback` to `VoiceLocalCuePlayer`.
  - Keep `setLocalCueErrorHandler` as the Stage 1 compatibility path.
  - Keep platform track branching in this file for now; Stage 3 extracts it.

- Modify `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`
  - Remove local-cue tests from this file.
  - Update fake sink signatures to assistant-only sink API.

- Create `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt`
  - Move and reshape local-cue behavior tests here.

- Existing integration tests remain:
  - `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneControllerTest.kt`
  - `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinatorWaitingToneTest.kt`
  - `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`
  - `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSessionTest.kt`
  - `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesJobManagerTest.kt`

## Task 1: Add Local Cue Player Tests

**Files:**
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt`
- Read: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`

- [ ] **Step 1: Create failing local cue player tests**

Create `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt` with this content:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoiceLocalCuePlayerTest {
    @Test
    fun `local cue queued and written diagnostics are cue specific`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(sink.awaitWrites(1))
        assertTrue(waitForDiagnostic(diagnostics) { it is VoiceLocalCueDiagnostic.ChunkWritten })

        val queued = diagnostics.filterIsInstance<VoiceLocalCueDiagnostic.ChunkQueued>().single()
        val written = diagnostics.filterIsInstance<VoiceLocalCueDiagnostic.ChunkWritten>().single()
        assertEquals(3, queued.bytes)
        assertEquals(3, written.bytes)
        assertEquals(1, sink.startCalls)
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), sink.writes)

        player.release()
        scope.cancel()
    }

    @Test
    fun `sink start failure emits local cue diagnostic and worker accepts later cue`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val startFailedLatch = CountDownLatch(1)
        val firstSink = FakeVoicePcm16Sink(startException = IllegalStateException("start exploded"))
        val secondSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) firstSink else secondSink
            },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.SinkStartFailed) {
                    startFailedLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(startFailedLatch.await(2, TimeUnit.SECONDS))

        assertEquals(1, firstSink.stopAndReleaseCalls)
        assertTrue(player.playBase64(base64Pcm16 = "BAUG", token = null))
        assertTrue(secondSink.awaitWrites(1))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), secondSink.writes)
        assertTrue(diagnostics.any { it is VoiceLocalCueDiagnostic.SinkStartFailed })

        player.release()
        scope.cancel()
    }

    @Test
    fun `sink write failure emits local cue diagnostic`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val writeFailedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            writeResult = VoicePcm16Sink.WriteResult.Failed("write failed"),
        )
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.SinkWriteFailed) {
                    writeFailedLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(writeFailedLatch.await(2, TimeUnit.SECONDS))

        val diagnostic = diagnostics.filterIsInstance<VoiceLocalCueDiagnostic.SinkWriteFailed>().single()
        assertEquals("write failed", diagnostic.message)

        player.release()
        scope.cancel()
    }

    @Test
    fun `invalidation skips queued cue`() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        executor.execute {
            workerBlocked.countDown()
            releaseWorker.await(2, TimeUnit.SECONDS)
        }
        assertTrue(workerBlocked.await(2, TimeUnit.SECONDS))

        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val staleRejectedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(expectedWrites = 0)
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.StaleCueRejected) {
                    staleRejectedLatch.countDown()
                }
            },
        )

        try {
            assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
            player.invalidate()
            releaseWorker.countDown()

            assertTrue(staleRejectedLatch.await(2, TimeUnit.SECONDS))
            assertEquals(emptyList<List<Byte>>(), sink.writes)
        } finally {
            player.release()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `invalidation rejects later enqueue with invalidated token`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        var sinkCreations = 0
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = {
                sinkCreations += 1
                sink
            },
            onDiagnostic = diagnostics::add,
        )

        player.invalidate(token = 10L)

        assertFalse(player.playBase64(base64Pcm16 = "AQID", token = 10L))
        assertEquals(0, sinkCreations)
        assertTrue(diagnostics.any { it is VoiceLocalCueDiagnostic.StaleCueRejected })

        assertTrue(player.playBase64(base64Pcm16 = "BAUG", token = 11L))
        assertTrue(sink.awaitWrites(1))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), sink.writes)

        player.release()
        scope.cancel()
    }

    @Test
    fun `invalidation interrupts active cue`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val staleCueLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            blockFirstWrite = true,
            interruptBlockedWriteOnPause = true,
        )
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.StaleCueRejected) {
                    staleCueLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(sink.awaitWriteStarted())

        player.invalidate()
        sink.releaseBlockedWrite()

        assertTrue(staleCueLatch.await(2, TimeUnit.SECONDS))
        assertTrue(sink.awaitWrites(1))
        assertEquals(emptyList<List<Byte>>(), sink.writes)
        assertEquals(1, sink.pauseAndFlushCalls)
        assertEquals(1, sink.stopAndReleaseCalls)

        player.release()
        scope.cancel()
    }

    @Test
    fun `release stops invalidated sink before returning`() {
        val scope = testScope()
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(sink.awaitWrites(1))

        player.invalidate()
        player.release()

        assertEquals(1, sink.pauseAndFlushCalls)
        assertEquals(1, sink.stopAndReleaseCalls)
        scope.cancel()
    }

    @Test
    fun `malformed base64 is rejected without creating sink`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        var sinkCreations = 0
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = {
                sinkCreations += 1
                FakeVoicePcm16Sink()
            },
            onDiagnostic = diagnostics::add,
        )

        assertFalse(player.playBase64(base64Pcm16 = "not-base64%", token = null))

        assertEquals(0, sinkCreations)
        val diagnostic = diagnostics.filterIsInstance<VoiceLocalCueDiagnostic.MalformedCue>().single()
        assertTrue(diagnostic.message.isNotBlank())

        player.release()
        scope.cancel()
    }

    @Test
    fun `null sink factory reports start failure and worker accepts later cue`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val startFailedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) null else sink
            },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.SinkStartFailed) {
                    startFailedLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(startFailedLatch.await(2, TimeUnit.SECONDS))

        assertTrue(player.playBase64(base64Pcm16 = "BAUG", token = null))
        assertTrue(sink.awaitWrites(1))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), sink.writes)
        assertTrue(diagnostics.any { it is VoiceLocalCueDiagnostic.SinkStartFailed })

        player.release()
        scope.cancel()
    }

    @Test
    fun `interrupted write is treated as stale cue not sink failure`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val staleRejectedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            writeResult = VoicePcm16Sink.WriteResult.Interrupted,
        )
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.StaleCueRejected) {
                    staleRejectedLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(sink.awaitWrites(1))

        assertTrue(staleRejectedLatch.await(2, TimeUnit.SECONDS))
        assertFalse(diagnostics.any { it is VoiceLocalCueDiagnostic.SinkWriteFailed })
        assertEquals(1, sink.stopAndReleaseCalls)

        player.release()
        scope.cancel()
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun waitForDiagnostic(
        diagnostics: List<VoiceLocalCueDiagnostic>,
        predicate: (VoiceLocalCueDiagnostic) -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
        while (System.nanoTime() < deadline) {
            if (diagnostics.any(predicate)) {
                return true
            }
            Thread.sleep(1)
        }
        return diagnostics.any(predicate)
    }

    private class FakeVoicePcm16Sink(
        expectedWrites: Int = 0,
        private val blockFirstWrite: Boolean = false,
        private val startException: RuntimeException? = null,
        private val writeResult: VoicePcm16Sink.WriteResult? = null,
        private val interruptBlockedWriteOnPause: Boolean = false,
    ) : VoicePcm16Sink {
        private val writesLatch = CountDownLatch(expectedWrites)
        private val writeStartedLatch = CountDownLatch(1)
        private val releaseBlockedWriteLatch = CountDownLatch(1)
        private val startCallCount = AtomicInteger()
        private val pauseAndFlushCallCount = AtomicInteger()
        private val stopAndReleaseCallCount = AtomicInteger()
        val writes = mutableListOf<List<Byte>>()

        val startCalls: Int
            get() = startCallCount.get()

        val pauseAndFlushCalls: Int
            get() = pauseAndFlushCallCount.get()

        val stopAndReleaseCalls: Int
            get() = stopAndReleaseCallCount.get()

        override fun start(): VoicePcm16Sink.StartResult {
            startCallCount.incrementAndGet()
            startException?.let { throw it }
            return VoicePcm16Sink.StartResult.Started
        }

        override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
            writeStartedLatch.countDown()
            if (blockFirstWrite && writes.isEmpty()) {
                assertTrue(releaseBlockedWriteLatch.await(2, TimeUnit.SECONDS))
            }
            if (interruptBlockedWriteOnPause && pauseAndFlushCallCount.get() > 0) {
                writesLatch.countDown()
                return VoicePcm16Sink.WriteResult.Interrupted
            }
            val result = writeResult ?: VoicePcm16Sink.WriteResult.Written(pcm16.size)
            if (result is VoicePcm16Sink.WriteResult.Written) {
                writes += pcm16.toList()
            }
            writesLatch.countDown()
            return result
        }

        override fun pauseAndFlush() {
            pauseAndFlushCallCount.incrementAndGet()
        }

        override fun stopAndRelease() {
            stopAndReleaseCallCount.incrementAndGet()
        }

        fun awaitWrites(seconds: Long): Boolean = writesLatch.await(seconds, TimeUnit.SECONDS)

        fun awaitWriteStarted(): Boolean = writeStartedLatch.await(2, TimeUnit.SECONDS)

        fun releaseBlockedWrite() {
            releaseBlockedWriteLatch.countDown()
        }
    }
}
```

- [ ] **Step 2: Run the new tests and verify they fail**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest' \
  --rerun-tasks
```

Expected: compilation fails because `VoiceLocalCuePlayer`, `VoiceLocalCueDiagnostic`, and source-free `VoicePcm16Sink.start()` / `writeFully()` do not exist yet.

- [ ] **Step 3: Commit the failing tests**

```bash
git add app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt
git commit -m "test: cover local cue player lifecycle"
```

## Task 2: Implement VoiceLocalCuePlayer

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt`

- [ ] **Step 1: Remove source parameters from `VoicePcm16Sink`**

In `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`, change the interface at the top of the file to:

```kotlin
internal interface VoicePcm16Sink {
    fun start(): StartResult
    fun writeFully(pcm16: ByteArray): WriteResult
    fun pauseAndFlush()
    fun stopAndRelease()

    sealed interface StartResult {
        data object Started : StartResult
        data class Failed(val message: String) : StartResult
    }

    sealed interface WriteResult {
        data class Written(val bytes: Int) : WriteResult
        data class Failed(val message: String) : WriteResult
        data object Interrupted : WriteResult
    }
}
```

After this edit, `VoicePlaybackWriter.kt` will not compile until Task 3 removes the source-aware writer logic. Continue directly to Step 2 and Task 3 before expecting compilation success.

- [ ] **Step 2: Add `VoiceLocalCuePlayer`**

Create `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Base64

internal sealed interface VoiceLocalCueDiagnostic {
    data class ChunkQueued(val bytes: Int, val generation: Long) : VoiceLocalCueDiagnostic
    data class ChunkWritten(val bytes: Int, val generation: Long) : VoiceLocalCueDiagnostic
    data class StaleCueRejected(
        val generation: Long,
        val activeGeneration: Long,
        val rejectedToken: Long? = null,
    ) : VoiceLocalCueDiagnostic
    data class MalformedCue(val message: String) : VoiceLocalCueDiagnostic
    data class SinkStartFailed(val message: String) : VoiceLocalCueDiagnostic
    data class SinkWriteFailed(val message: String) : VoiceLocalCueDiagnostic
    data object Released : VoiceLocalCueDiagnostic
}

internal class VoiceLocalCuePlayer(
    scope: CoroutineScope,
    private val createSink: () -> VoicePcm16Sink?,
    private val onDiagnostic: (VoiceLocalCueDiagnostic) -> Unit = {},
) {
    private val lock = Any()
    private val commands = Channel<PlaybackCommand>(Channel.UNLIMITED)
    private val worker = scope.launch {
        for (command in commands) {
            when (command) {
                is PlaybackCommand.Play -> playCommand(command)
            }
        }
    }

    private var generation = 0L
    private var highestInvalidatedToken: Long? = null
    private var activeSink: VoicePcm16Sink? = null
    private var retiredSink: VoicePcm16Sink? = null
    private var released = false

    fun playBase64(base64Pcm16: String, token: Long?): Boolean {
        val pcm16 = try {
            Base64.getDecoder().decode(base64Pcm16)
        } catch (e: IllegalArgumentException) {
            onDiagnostic(VoiceLocalCueDiagnostic.MalformedCue(e.message ?: "Malformed local cue chunk"))
            return false
        }
        if (pcm16.isEmpty()) {
            onDiagnostic(VoiceLocalCueDiagnostic.MalformedCue("Empty local cue chunk"))
            return false
        }

        var rejectedGeneration: Long? = null
        val command = synchronized(lock) {
            if (released) {
                null
            } else if (isTokenInvalidatedLocked(token)) {
                rejectedGeneration = generation
                null
            } else {
                PlaybackCommand.Play(
                    pcm16 = pcm16,
                    generation = generation,
                    token = token,
                )
            }
        }

        if (command == null) {
            rejectedGeneration?.let { activeGeneration ->
                onDiagnostic(
                    VoiceLocalCueDiagnostic.StaleCueRejected(
                        generation = activeGeneration,
                        activeGeneration = activeGeneration,
                        rejectedToken = token,
                    ),
                )
            }
            return false
        }

        if (!commands.trySend(command).isSuccess) {
            return false
        }

        onDiagnostic(
            VoiceLocalCueDiagnostic.ChunkQueued(
                bytes = pcm16.size,
                generation = command.generation,
            ),
        )
        return true
    }

    fun invalidate(token: Long? = null) {
        val result = synchronized(lock) {
            if (released) {
                InvalidateResult()
            } else {
                generation += 1
                token?.let { invalidatedToken ->
                    highestInvalidatedToken = maxOf(
                        highestInvalidatedToken ?: invalidatedToken,
                        invalidatedToken,
                    )
                }
                val sinkToFlush = activeSink
                val sinkToRelease = if (sinkToFlush != null && retiredSink !== sinkToFlush) {
                    retiredSink
                } else {
                    null
                }
                if (sinkToFlush != null) {
                    activeSink = null
                    retiredSink = sinkToFlush
                }
                InvalidateResult(sinkToFlush = sinkToFlush, sinkToRelease = sinkToRelease)
            }
        }
        result.sinkToRelease?.stopAndRelease()
        result.sinkToFlush?.pauseAndFlush()
    }

    fun release() {
        val retired = synchronized(lock) {
            if (released) {
                return
            }
            released = true
            generation += 1
            RetiredSinks(active = activeSink, retired = retiredSink).also {
                activeSink = null
                retiredSink = null
            }
        }
        retired.stopAndRelease()
        commands.close()
        worker.cancel()
        onDiagnostic(VoiceLocalCueDiagnostic.Released)
    }

    private fun playCommand(command: PlaybackCommand.Play) {
        if (!isCurrent(command)) {
            emitStale(command.generation)
            return
        }

        val sink = getOrCreateSink(command) ?: return
        if (!beginWrite(command, sink)) {
            emitStale(command.generation)
            return
        }

        when (val result = sink.writeFully(command.pcm16)) {
            is VoicePcm16Sink.WriteResult.Written -> {
                if (isCurrentSink(command, sink)) {
                    onDiagnostic(
                        VoiceLocalCueDiagnostic.ChunkWritten(
                            bytes = result.bytes,
                            generation = command.generation,
                        ),
                    )
                } else {
                    emitStale(command.generation)
                }
            }
            is VoicePcm16Sink.WriteResult.Failed -> {
                if (clearSink(sink)) {
                    sink.stopAndRelease()
                }
                onDiagnostic(VoiceLocalCueDiagnostic.SinkWriteFailed(result.message))
            }
            VoicePcm16Sink.WriteResult.Interrupted -> {
                if (clearSink(sink)) {
                    sink.stopAndRelease()
                }
                emitStale(command.generation)
            }
        }
    }

    private fun getOrCreateSink(command: PlaybackCommand.Play): VoicePcm16Sink? {
        var staleGeneration: Long? = null
        val currentSink = synchronized(lock) {
            if (!isCurrentLocked(command)) {
                staleGeneration = generation
                null
            } else {
                activeSink
            }
        }

        if (staleGeneration != null) {
            onDiagnostic(
                VoiceLocalCueDiagnostic.StaleCueRejected(
                    generation = command.generation,
                    activeGeneration = staleGeneration,
                    rejectedToken = command.token,
                ),
            )
            return null
        }
        if (currentSink != null) {
            return currentSink
        }

        val newSink = try {
            createSink()
        } catch (e: Exception) {
            onDiagnostic(VoiceLocalCueDiagnostic.SinkStartFailed(e.message ?: e.javaClass.simpleName))
            return null
        } ?: run {
            onDiagnostic(VoiceLocalCueDiagnostic.SinkStartFailed("Local cue sink creation failed"))
            return null
        }

        val startResult = try {
            newSink.start()
        } catch (e: Exception) {
            newSink.stopAndRelease()
            onDiagnostic(VoiceLocalCueDiagnostic.SinkStartFailed(e.message ?: e.javaClass.simpleName))
            return null
        }

        when (startResult) {
            VoicePcm16Sink.StartResult.Started -> Unit
            is VoicePcm16Sink.StartResult.Failed -> {
                newSink.stopAndRelease()
                onDiagnostic(VoiceLocalCueDiagnostic.SinkStartFailed(startResult.message))
                return null
            }
        }

        var selectedStaleGeneration: Long? = null
        val selectedSink = synchronized(lock) {
            if (!isCurrentLocked(command)) {
                selectedStaleGeneration = generation
                null
            } else {
                activeSink ?: newSink.also { activeSink = it }
            }
        }

        if (selectedSink == null) {
            newSink.stopAndRelease()
            onDiagnostic(
                VoiceLocalCueDiagnostic.StaleCueRejected(
                    generation = command.generation,
                    activeGeneration = selectedStaleGeneration ?: currentGeneration(),
                    rejectedToken = command.token,
                ),
            )
            return null
        }

        if (selectedSink !== newSink) {
            newSink.stopAndRelease()
        }
        return selectedSink
    }

    private fun isCurrent(command: PlaybackCommand.Play): Boolean = synchronized(lock) {
        isCurrentLocked(command)
    }

    private fun isCurrentLocked(command: PlaybackCommand.Play): Boolean {
        return !released &&
            generation == command.generation &&
            !isTokenInvalidatedLocked(command.token)
    }

    private fun isTokenInvalidatedLocked(token: Long?): Boolean =
        token != null && highestInvalidatedToken?.let { token <= it } == true

    private fun isCurrentSink(command: PlaybackCommand.Play, sink: VoicePcm16Sink): Boolean = synchronized(lock) {
        isCurrentLocked(command) && activeSink === sink
    }

    private fun beginWrite(command: PlaybackCommand.Play, sink: VoicePcm16Sink): Boolean = synchronized(lock) {
        isCurrentLocked(command) && activeSink === sink
    }

    private fun clearSink(sink: VoicePcm16Sink): Boolean = synchronized(lock) {
        var cleared = false
        if (activeSink === sink) {
            activeSink = null
            cleared = true
        }
        if (retiredSink === sink) {
            retiredSink = null
            cleared = true
        }
        return cleared
    }

    private fun emitStale(commandGeneration: Long) {
        onDiagnostic(
            VoiceLocalCueDiagnostic.StaleCueRejected(
                generation = commandGeneration,
                activeGeneration = currentGeneration(),
            ),
        )
    }

    private fun currentGeneration(): Long = synchronized(lock) {
        generation
    }

    private sealed interface PlaybackCommand {
        data class Play(
            val pcm16: ByteArray,
            val generation: Long,
            val token: Long?,
        ) : PlaybackCommand
    }

    private data class InvalidateResult(
        val sinkToFlush: VoicePcm16Sink? = null,
        val sinkToRelease: VoicePcm16Sink? = null,
    )

    private data class RetiredSinks(
        val active: VoicePcm16Sink?,
        val retired: VoicePcm16Sink?,
    ) {
        fun stopAndRelease() {
            active?.stopAndRelease()
            if (retired !== active) {
                retired?.stopAndRelease()
            }
        }
    }
}
```

- [ ] **Step 3: Run only the new local cue tests**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest' \
  --rerun-tasks
```

Expected: compilation still fails because `VoicePlaybackWriter.kt`, `AndroidVoiceAudioEngine.kt`, and existing tests still call the old source-aware sink API.

- [ ] **Step 4: Defer the implementation commit**

Do not commit after Task 2. The repository will still have compile errors until `VoicePlaybackWriter.kt`, tests, and `AndroidVoiceAudioEngine.kt` are updated in Tasks 3 and 4. Commit the Stage 1 core split in Task 4 after focused audio tests pass.

## Task 3: Make VoicePlaybackWriter Assistant-Only

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`

- [ ] **Step 1: Replace `VoicePlaybackWriter` constructor and fields**

In `VoicePlaybackWriter.kt`, remove `VoicePlaybackSource` entirely. Change constructor and state to:

```kotlin
internal class VoicePlaybackWriter(
    scope: CoroutineScope,
    private val createSink: () -> VoicePcm16Sink?,
    private val onDiagnostic: (VoicePlaybackDiagnostic) -> Unit = {},
) {
    private val lock = Any()
    private val commands = Channel<PlaybackCommand>(Channel.UNLIMITED)
    private val worker = scope.launch {
        for (command in commands) {
            when (command) {
                is PlaybackCommand.Play -> playCommand(command)
            }
        }
    }

    private var activeSessionId: Long? = null
    private var generation = 0L
    private var activeSink: VoicePcm16Sink? = null
    private var released = false
```

- [ ] **Step 2: Replace `VoicePlaybackDiagnostic` with assistant-only diagnostics**

In `VoicePlaybackWriter.kt`, replace the diagnostic sealed interface with:

```kotlin
internal sealed interface VoicePlaybackDiagnostic {
    data class ChunkQueued(val bytes: Int, val generation: Long) : VoicePlaybackDiagnostic
    data class ChunkWritten(val bytes: Int, val generation: Long) : VoicePlaybackDiagnostic
    data class StaleChunkRejected(
        val generation: Long,
        val activeGeneration: Long,
        val rejectedSessionId: Long? = null,
        val activeSessionId: Long? = null,
    ) : VoicePlaybackDiagnostic
    data class MalformedChunk(val message: String) : VoicePlaybackDiagnostic
    data class SinkStartFailed(val message: String) : VoicePlaybackDiagnostic
    data class SinkWriteFailed(val message: String) : VoicePlaybackDiagnostic
    data class PlaybackSuppressed(val generation: Long) : VoicePlaybackDiagnostic
    data object Released : VoicePlaybackDiagnostic
}
```

- [ ] **Step 3: Replace playback enqueue methods**

In `VoicePlaybackWriter.kt`, keep only this public enqueue API:

```kotlin
fun playBase64(base64Pcm16: String, sessionId: Long?): Boolean {
    val pcm16 = try {
        Base64.getDecoder().decode(base64Pcm16)
    } catch (e: IllegalArgumentException) {
        onDiagnostic(VoicePlaybackDiagnostic.MalformedChunk(e.message ?: "Malformed playback chunk"))
        return false
    }
    if (pcm16.isEmpty()) {
        onDiagnostic(VoicePlaybackDiagnostic.MalformedChunk("Empty playback chunk"))
        return false
    }

    var staleActiveGeneration: Long? = null
    var staleActiveSessionId: Long? = null
    val command = synchronized(lock) {
        if (released) {
            null
        } else if (sessionId != null && activeSessionId != sessionId) {
            staleActiveGeneration = generation
            staleActiveSessionId = activeSessionId
            null
        } else {
            PlaybackCommand.Play(pcm16 = pcm16, generation = generation)
        }
    }
    if (command == null) {
        staleActiveGeneration?.let { activeGeneration ->
            onDiagnostic(
                VoicePlaybackDiagnostic.StaleChunkRejected(
                    generation = activeGeneration,
                    activeGeneration = activeGeneration,
                    rejectedSessionId = sessionId,
                    activeSessionId = staleActiveSessionId,
                ),
            )
        }
        return false
    }

    if (!commands.trySend(command).isSuccess) {
        return false
    }

    onDiagnostic(VoicePlaybackDiagnostic.ChunkQueued(bytes = pcm16.size, generation = command.generation))
    return true
}
```

- [ ] **Step 4: Replace session lifecycle methods**

In `VoicePlaybackWriter.kt`, ensure these methods retire only the assistant sink:

```kotlin
fun activateSession(sessionId: Long) {
    val sink = synchronized(lock) {
        if (released) {
            return
        }
        activeSessionId = sessionId
        generation += 1
        activeSink.also {
            activeSink = null
        }
    }
    sink?.stopAndRelease()
}

fun invalidateSession() {
    val sink = synchronized(lock) {
        if (released) {
            return
        }
        activeSessionId = null
        generation += 1
        activeSink.also {
            activeSink = null
        }
    }
    sink?.stopAndRelease()
}

fun suppress() {
    val result = synchronized(lock) {
        if (released) {
            return
        }
        generation += 1
        SuppressResult(sink = activeSink, generation = generation).also {
            activeSink = null
        }
    }
    result.sink?.stopAndRelease()
    onDiagnostic(VoicePlaybackDiagnostic.PlaybackSuppressed(result.generation))
}

fun release() {
    val sink = synchronized(lock) {
        if (released) {
            return
        }
        released = true
        generation += 1
        activeSessionId = null
        activeSink.also {
            activeSink = null
        }
    }
    sink?.stopAndRelease()
    commands.close()
    worker.cancel()
    onDiagnostic(VoicePlaybackDiagnostic.Released)
}
```

- [ ] **Step 5: Replace private playback helpers**

In `VoicePlaybackWriter.kt`, make private helpers assistant-only:

```kotlin
private fun playCommand(command: PlaybackCommand.Play) {
    if (!isCurrent(command.generation)) {
        emitStale(command.generation)
        return
    }

    val sink = getOrCreateSink(command.generation) ?: return
    if (!isCurrentSink(command.generation, sink)) {
        emitStale(command.generation)
        return
    }

    when (val result = sink.writeFully(command.pcm16)) {
        is VoicePcm16Sink.WriteResult.Written -> {
            if (isCurrentSink(command.generation, sink)) {
                onDiagnostic(
                    VoicePlaybackDiagnostic.ChunkWritten(
                        bytes = result.bytes,
                        generation = command.generation,
                    ),
                )
            } else {
                emitStale(command.generation)
            }
        }
        is VoicePcm16Sink.WriteResult.Failed -> {
            if (clearSink(sink)) {
                sink.stopAndRelease()
            }
            onDiagnostic(VoicePlaybackDiagnostic.SinkWriteFailed(result.message))
        }
        VoicePcm16Sink.WriteResult.Interrupted -> {
            if (clearSink(sink)) {
                sink.stopAndRelease()
            }
            emitStale(command.generation)
        }
    }
}

private fun getOrCreateSink(commandGeneration: Long): VoicePcm16Sink? {
    var staleActiveGeneration: Long? = null
    val currentSink = synchronized(lock) {
        if (released || generation != commandGeneration) {
            staleActiveGeneration = generation
            null
        } else {
            activeSink
        }
    }
    if (staleActiveGeneration != null) {
        onDiagnostic(
            VoicePlaybackDiagnostic.StaleChunkRejected(
                generation = commandGeneration,
                activeGeneration = staleActiveGeneration,
            ),
        )
        return null
    }
    if (currentSink != null) {
        return currentSink
    }

    val newSink = try {
        createSink()
    } catch (e: Exception) {
        onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed(e.message ?: e.javaClass.simpleName))
        return null
    } ?: run {
        onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed("Playback sink creation failed"))
        return null
    }

    val startResult = try {
        newSink.start()
    } catch (e: Exception) {
        newSink.stopAndRelease()
        onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed(e.message ?: e.javaClass.simpleName))
        return null
    }

    when (startResult) {
        VoicePcm16Sink.StartResult.Started -> Unit
        is VoicePcm16Sink.StartResult.Failed -> {
            newSink.stopAndRelease()
            onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed(startResult.message))
            return null
        }
    }

    var staleGeneration: Long? = null
    val selectedSink = synchronized(lock) {
        if (released || generation != commandGeneration) {
            staleGeneration = generation
            null
        } else {
            activeSink ?: newSink.also { activeSink = it }
        }
    }

    if (selectedSink == null) {
        newSink.stopAndRelease()
        onDiagnostic(
            VoicePlaybackDiagnostic.StaleChunkRejected(
                generation = commandGeneration,
                activeGeneration = staleGeneration ?: currentGeneration(),
            ),
        )
        return null
    }

    if (selectedSink !== newSink) {
        newSink.stopAndRelease()
    }
    return selectedSink
}

private fun isCurrent(commandGeneration: Long): Boolean = synchronized(lock) {
    !released && generation == commandGeneration
}

private fun isCurrentSink(commandGeneration: Long, sink: VoicePcm16Sink): Boolean = synchronized(lock) {
    !released && generation == commandGeneration && activeSink === sink
}

private fun clearSink(sink: VoicePcm16Sink): Boolean = synchronized(lock) {
    if (activeSink === sink) {
        activeSink = null
        true
    } else {
        false
    }
}

private fun emitStale(commandGeneration: Long) {
    onDiagnostic(
        VoicePlaybackDiagnostic.StaleChunkRejected(
            generation = commandGeneration,
            activeGeneration = currentGeneration(),
        ),
    )
}

private fun currentGeneration(): Long = synchronized(lock) {
    generation
}

private sealed interface PlaybackCommand {
    data class Play(
        val pcm16: ByteArray,
        val generation: Long,
    ) : PlaybackCommand
}

private data class SuppressResult(
    val sink: VoicePcm16Sink?,
    val generation: Long,
)
```

Delete `invalidateLocalCues`, `isLocalCueSessionInvalidatedLocked`, `sinkForLocked`, `setSinkForLocked`, `retireSinksLocked`, `InvalidateLocalCuesResult`, and `RetiredSinks`.

- [ ] **Step 6: Update `VoicePlaybackWriterTest` constructor calls**

In `VoicePlaybackWriterTest.kt`, change every writer creation from:

```kotlin
val writer = VoicePlaybackWriter(
    scope = scope,
    createSink = { _ -> sink },
    onDiagnostic = diagnostics::add,
)
```

to:

```kotlin
val writer = VoicePlaybackWriter(
    scope = scope,
    createSink = { sink },
    onDiagnostic = diagnostics::add,
)
```

For sink factories that increment or choose by index, keep the body but remove the unused source parameter:

```kotlin
createSink = {
    if (sinkIndex.getAndIncrement() == 0) null else sink
}
```

- [ ] **Step 7: Remove local-cue tests from `VoicePlaybackWriterTest`**

Delete these test methods from `VoicePlaybackWriterTest.kt` because their behavior now belongs to `VoiceLocalCuePlayerTest`:

```text
local cue sink start failure diagnostic keeps local cue source
local cue queued and written diagnostics keep local cue source
local cue sink write failure diagnostic keeps local cue source
local cue uses separate sink from assistant playback
local cue invalidation skips queued local cue without suppressing queued assistant playback
local cue invalidation rejects later enqueue with invalidated session token
local cue invalidation interrupts active local cue without suppressing assistant playback
release stops invalidated local cue sink before another local cue plays
local cue invalidation flushes buffered local cue without flushing assistant sink
local cue sink failures do not map to fatal audio errors
local cue sink failures map to local cue error messages
assistant sink failures still map to fatal audio errors
assistant sink failures do not map to local cue error messages
malformed local cue base64 is rejected without creating sink or fatal audio error
```

Keep assistant tests such as:

```text
play enqueues decoded chunks and writes them fully in order
play rejects stale session before enqueue
suppress increments generation retires active sink and skips queued stale chunks
invalidate session flushes sink and rejects previous session playback
release during blocked failed write stops sink only once
throwing sink start is released and worker accepts later playback
release stops sink and rejects future playback
malformed base64 is rejected without creating sink
null sink factory reports start failure and worker accepts later playback
interrupted write is treated as stale playback not sink failure
```

- [ ] **Step 8: Update `FakeVoicePcm16Sink` in `VoicePlaybackWriterTest`**

Replace the fake sink helper with the assistant-only version:

```kotlin
private class FakeVoicePcm16Sink(
    expectedWrites: Int = 0,
    private val blockFirstWrite: Boolean = false,
    private val startException: RuntimeException? = null,
    private val writeResult: VoicePcm16Sink.WriteResult? = null,
) : VoicePcm16Sink {
    private val writesLatch = CountDownLatch(expectedWrites)
    private val writeStartedLatch = CountDownLatch(1)
    private val releaseBlockedWriteLatch = CountDownLatch(1)
    private val startCallCount = AtomicInteger()
    private val pauseAndFlushCallCount = AtomicInteger()
    private val stopAndReleaseCallCount = AtomicInteger()
    val writes = mutableListOf<List<Byte>>()

    val startCalls: Int
        get() = startCallCount.get()

    val pauseAndFlushCalls: Int
        get() = pauseAndFlushCallCount.get()

    val stopAndReleaseCalls: Int
        get() = stopAndReleaseCallCount.get()

    override fun start(): VoicePcm16Sink.StartResult {
        startCallCount.incrementAndGet()
        startException?.let { throw it }
        return VoicePcm16Sink.StartResult.Started
    }

    override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
        writeStartedLatch.countDown()
        if (blockFirstWrite && writes.isEmpty()) {
            assertTrue(releaseBlockedWriteLatch.await(2, TimeUnit.SECONDS))
        }
        val result = writeResult ?: VoicePcm16Sink.WriteResult.Written(pcm16.size)
        if (result is VoicePcm16Sink.WriteResult.Written) {
            writes += pcm16.toList()
        }
        writesLatch.countDown()
        return result
    }

    override fun pauseAndFlush() {
        pauseAndFlushCallCount.incrementAndGet()
    }

    override fun stopAndRelease() {
        stopAndReleaseCallCount.incrementAndGet()
    }

    fun awaitWrites(seconds: Long): Boolean = writesLatch.await(seconds, TimeUnit.SECONDS)

    fun awaitWriteStarted(): Boolean = writeStartedLatch.await(2, TimeUnit.SECONDS)

    fun releaseBlockedWrite() {
        releaseBlockedWriteLatch.countDown()
    }
}
```

- [ ] **Step 9: Run writer and local cue tests**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest' \
  --rerun-tasks
```

Expected: compilation still fails in `AndroidVoiceAudioEngine.kt` because it still passes `VoicePlaybackSource` and calls the old sink API.

## Task 4: Wire AndroidVoiceAudioEngine to Separate Players

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt`

- [ ] **Step 1: Add private Android playback source enum**

In `AndroidVoiceAudioEngine.kt`, add this private enum near the top-level helper functions:

```kotlin
private enum class AndroidPlaybackTrackOwner {
    Assistant,
    LocalCue,
}
```

This enum is intentionally private to `AndroidVoiceAudioEngine`. It is a Stage 1 bridge until Stage 3 extracts Android track ownership.

- [ ] **Step 2: Update playback writer and add local cue player fields**

Replace the existing `playbackWriter` field with:

```kotlin
private val playbackWriter = VoicePlaybackWriter(
    scope = scope,
    createSink = ::createAssistantAudioTrackSinkOrNull,
    onDiagnostic = ::handlePlaybackDiagnostic,
)
private val localCuePlayer = VoiceLocalCuePlayer(
    scope = scope,
    createSink = ::createLocalCueAudioTrackSinkOrNull,
    onDiagnostic = ::handleLocalCueDiagnostic,
)
```

- [ ] **Step 3: Route local cue public methods to local cue player**

Replace local cue methods with:

```kotlin
override fun playLocalCuePcm16(base64Pcm16: String, sessionId: Long?): Boolean {
    return localCuePlayer.playBase64(base64Pcm16 = base64Pcm16, token = sessionId)
}

override fun invalidateLocalCuePlayback(sessionId: Long?) {
    localCuePlayer.invalidate(token = sessionId)
}
```

In `release()`, after `playbackWriter.release()`, also call:

```kotlin
localCuePlayer.release()
```

- [ ] **Step 4: Replace sink factory methods**

Replace `createAudioTrackSinkOrNull(source: VoicePlaybackSource)` with:

```kotlin
private fun createAssistantAudioTrackSinkOrNull(): VoicePcm16Sink? {
    val track = getOrCreatePlaybackTrack(AndroidPlaybackTrackOwner.Assistant) ?: return null
    return AndroidAudioTrackSink(
        track = track,
        owner = AndroidPlaybackTrackOwner.Assistant,
    )
}

private fun createLocalCueAudioTrackSinkOrNull(): VoicePcm16Sink? {
    val track = createLocalCuePlaybackTrack() ?: return null
    return AndroidAudioTrackSink(
        track = track,
        owner = AndroidPlaybackTrackOwner.LocalCue,
    )
}
```

- [ ] **Step 5: Replace track owner helpers**

Change helper signatures from `VoicePlaybackSource` to `AndroidPlaybackTrackOwner`:

```kotlin
private fun getOrCreatePlaybackTrack(owner: AndroidPlaybackTrackOwner): AudioTrack? {
    val existingTrack = synchronized(lock) { playbackTrackForLocked(owner) }
    if (existingTrack != null) {
        return currentPlaybackTrack(existingTrack, owner)
    }

    val newTrack = createAudioTrackOrNull(owner) ?: return null
    var selectedTrack: AudioTrack? = null
    var shouldReleaseNewTrack = false
    synchronized(lock) {
        val currentTrack = playbackTrackForLocked(owner)
        if (released) {
            shouldReleaseNewTrack = true
        } else if (currentTrack == null) {
            setPlaybackTrackForLocked(owner, newTrack)
            selectedTrack = newTrack
        } else {
            selectedTrack = currentTrack
            shouldReleaseNewTrack = true
        }
    }

    if (shouldReleaseNewTrack) {
        newTrack.releaseSafely()
    }
    return currentPlaybackTrack(selectedTrack ?: return null, owner)
}

private fun createLocalCuePlaybackTrack(): AudioTrack? {
    val newTrack = createAudioTrackOrNull(AndroidPlaybackTrackOwner.LocalCue) ?: return null
    var shouldReleaseNewTrack = false
    synchronized(lock) {
        if (released) {
            shouldReleaseNewTrack = true
        } else {
            localCueAudioTrack = newTrack
        }
    }
    if (shouldReleaseNewTrack) {
        newTrack.releaseSafely()
        return null
    }
    return currentPlaybackTrack(newTrack, AndroidPlaybackTrackOwner.LocalCue)
}

private fun currentPlaybackTrack(track: AudioTrack, owner: AndroidPlaybackTrackOwner): AudioTrack? = synchronized(lock) {
    if (!released && playbackTrackForLocked(owner) === track) {
        track
    } else {
        null
    }
}

private fun playbackTrackForLocked(owner: AndroidPlaybackTrackOwner): AudioTrack? = when (owner) {
    AndroidPlaybackTrackOwner.Assistant -> audioTrack
    AndroidPlaybackTrackOwner.LocalCue -> localCueAudioTrack
}

private fun setPlaybackTrackForLocked(owner: AndroidPlaybackTrackOwner, track: AudioTrack?) {
    when (owner) {
        AndroidPlaybackTrackOwner.Assistant -> audioTrack = track
        AndroidPlaybackTrackOwner.LocalCue -> localCueAudioTrack = track
    }
}
```

- [ ] **Step 6: Update `createAudioTrackOrNull` and `playSafely`**

Change `createAudioTrackOrNull(source: VoicePlaybackSource)` to:

```kotlin
private fun createAudioTrackOrNull(owner: AndroidPlaybackTrackOwner): AudioTrack? {
    val bufferSize = playbackBufferSizeOrNull() ?: return null
    val attributes = voiceAudioAttributes()
    val format = AudioFormat.Builder()
        .setSampleRate(PLAYBACK_SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()
    val track = runCatching {
        AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }.onFailure {
        Log.w(TAG, "AudioTrack creation failed", it)
        if (owner == AndroidPlaybackTrackOwner.LocalCue) {
            Log.w(TAG, "Local cue playback failed: AudioTrack creation failed")
        } else {
            notifyAudioError("AudioTrack creation failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }.getOrNull() ?: return null

    if (track.state != AudioTrack.STATE_INITIALIZED) {
        Log.w(TAG, "AudioTrack initialization failed: state=${track.state}")
        if (owner == AndroidPlaybackTrackOwner.LocalCue) {
            Log.w(TAG, "Local cue playback failed: AudioTrack initialization failed")
        } else {
            notifyAudioError("AudioTrack initialization failed: state=${track.state}")
        }
        track.releaseSafely()
        return null
    }

    return track
}
```

Change `AudioTrack.playSafely(source: VoicePlaybackSource)` to:

```kotlin
private fun AudioTrack.playSafely(owner: AndroidPlaybackTrackOwner): Boolean {
    return runCatching {
        if (playState != AudioTrack.PLAYSTATE_PLAYING) {
            play()
        }
        true
    }.onFailure {
        Log.w(TAG, "AudioTrack play failed", it)
        if (owner == AndroidPlaybackTrackOwner.LocalCue) {
            Log.w(TAG, "Local cue playback failed: AudioTrack play failed")
        } else {
            notifyAudioError("AudioTrack play failed: ${it.message ?: it.javaClass.simpleName}")
        }
        removeTrack(this)
        releaseSafely()
    }.getOrDefault(false)
}
```

- [ ] **Step 7: Replace diagnostic mapping helpers**

Delete top-level `VoicePlaybackDiagnostic.audioErrorMessageOrNull()` and `VoicePlaybackDiagnostic.localCueErrorMessageOrNull()`.

Add:

```kotlin
private fun VoicePlaybackDiagnostic.audioErrorMessageOrNull(): String? = when (this) {
    is VoicePlaybackDiagnostic.MalformedChunk -> "Malformed playback chunk: $message"
    is VoicePlaybackDiagnostic.SinkStartFailed -> "AudioTrack start failed: $message"
    is VoicePlaybackDiagnostic.SinkWriteFailed -> "AudioTrack write failed: $message"
    is VoicePlaybackDiagnostic.ChunkQueued,
    is VoicePlaybackDiagnostic.ChunkWritten,
    is VoicePlaybackDiagnostic.StaleChunkRejected,
    is VoicePlaybackDiagnostic.PlaybackSuppressed,
    VoicePlaybackDiagnostic.Released,
    -> null
}

private fun VoiceLocalCueDiagnostic.localCueErrorMessageOrNull(): String? = when (this) {
    is VoiceLocalCueDiagnostic.SinkStartFailed -> "AudioTrack start failed: $message"
    is VoiceLocalCueDiagnostic.SinkWriteFailed -> "AudioTrack write failed: $message"
    is VoiceLocalCueDiagnostic.ChunkQueued,
    is VoiceLocalCueDiagnostic.ChunkWritten,
    is VoiceLocalCueDiagnostic.StaleCueRejected,
    is VoiceLocalCueDiagnostic.MalformedCue,
    VoiceLocalCueDiagnostic.Released,
    -> null
}
```

- [ ] **Step 8: Update diagnostic handlers**

Keep `handlePlaybackDiagnostic` assistant-only:

```kotlin
private fun handlePlaybackDiagnostic(diagnostic: VoicePlaybackDiagnostic) {
    when (diagnostic) {
        is VoicePlaybackDiagnostic.ChunkQueued -> {
            Log.d(TAG, "Voice playback queued: bytes=${diagnostic.bytes} generation=${diagnostic.generation}")
        }
        is VoicePlaybackDiagnostic.ChunkWritten -> {
            Log.d(TAG, "Voice playback wrote: bytes=${diagnostic.bytes} generation=${diagnostic.generation}")
        }
        is VoicePlaybackDiagnostic.StaleChunkRejected -> {
            Log.d(
                TAG,
                "Voice playback stale chunk rejected: generation=${diagnostic.generation} " +
                    "active=${diagnostic.activeGeneration} session=${diagnostic.rejectedSessionId} " +
                    "activeSession=${diagnostic.activeSessionId}",
            )
        }
        is VoicePlaybackDiagnostic.MalformedChunk -> {
            Log.w(TAG, "Dropping malformed playback chunk: ${diagnostic.message}")
            diagnostic.audioErrorMessageOrNull()?.let(::notifyAudioError)
        }
        is VoicePlaybackDiagnostic.SinkStartFailed -> {
            Log.w(TAG, "Voice playback start failed: ${diagnostic.message}")
            diagnostic.audioErrorMessageOrNull()?.let(::notifyAudioError)
        }
        is VoicePlaybackDiagnostic.SinkWriteFailed -> {
            Log.w(TAG, "Voice playback write failed: ${diagnostic.message}")
            diagnostic.audioErrorMessageOrNull()?.let(::notifyAudioError)
        }
        is VoicePlaybackDiagnostic.PlaybackSuppressed -> {
            Log.d(TAG, "Voice playback suppressed: generation=${diagnostic.generation}")
        }
        VoicePlaybackDiagnostic.Released -> {
            Log.d(TAG, "Voice playback released")
        }
    }
}
```

Add local cue handler:

```kotlin
private fun handleLocalCueDiagnostic(diagnostic: VoiceLocalCueDiagnostic) {
    when (diagnostic) {
        is VoiceLocalCueDiagnostic.ChunkQueued -> {
            Log.d(TAG, "Local cue playback queued: bytes=${diagnostic.bytes} generation=${diagnostic.generation}")
        }
        is VoiceLocalCueDiagnostic.ChunkWritten -> {
            Log.d(TAG, "Local cue playback wrote: bytes=${diagnostic.bytes} generation=${diagnostic.generation}")
        }
        is VoiceLocalCueDiagnostic.StaleCueRejected -> {
            Log.d(
                TAG,
                "Local cue stale chunk rejected: generation=${diagnostic.generation} " +
                    "active=${diagnostic.activeGeneration} token=${diagnostic.rejectedToken}",
            )
        }
        is VoiceLocalCueDiagnostic.MalformedCue -> {
            Log.w(TAG, "Local cue playback failed: ${diagnostic.message}")
        }
        is VoiceLocalCueDiagnostic.SinkStartFailed -> {
            Log.w(TAG, "Local cue playback failed: ${diagnostic.message}")
            diagnostic.localCueErrorMessageOrNull()?.let(::notifyLocalCueError)
        }
        is VoiceLocalCueDiagnostic.SinkWriteFailed -> {
            Log.w(TAG, "Local cue playback failed: ${diagnostic.message}")
            diagnostic.localCueErrorMessageOrNull()?.let(::notifyLocalCueError)
        }
        VoiceLocalCueDiagnostic.Released -> {
            Log.d(TAG, "Local cue playback released")
        }
    }
}
```

- [ ] **Step 9: Update `AndroidAudioTrackSink`**

Change constructor and methods to:

```kotlin
private inner class AndroidAudioTrackSink(
    private val track: AudioTrack,
    private val owner: AndroidPlaybackTrackOwner,
) : VoicePcm16Sink {
    private val interrupted = AtomicBoolean(false)

    override fun start(): VoicePcm16Sink.StartResult {
        interrupted.set(false)
        return if (track.playSafely(owner)) {
            VoicePcm16Sink.StartResult.Started
        } else {
            VoicePcm16Sink.StartResult.Failed("AudioTrack play failed")
        }
    }

    override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
        if (interrupted.get()) {
            return VoicePcm16Sink.WriteResult.Interrupted
        }
        if (!track.playSafely(owner)) {
            return VoicePcm16Sink.WriteResult.Failed("AudioTrack play failed")
        }

        var offset = 0
        var zeroWrites = 0
        while (offset < pcm16.size && !interrupted.get() && currentPlaybackTrack(track, owner) != null) {
            val remaining = pcm16.size - offset
            val writeResult = try {
                track.write(pcm16, offset, remaining, AudioTrack.WRITE_BLOCKING)
            } catch (e: RuntimeException) {
                if (interrupted.get()) {
                    return VoicePcm16Sink.WriteResult.Interrupted
                }
                Log.w(TAG, "AudioTrack write failed", e)
                removeTrack(track)
                track.releaseSafely()
                return VoicePcm16Sink.WriteResult.Failed(e.message ?: e.javaClass.simpleName)
            }

            when {
                interrupted.get() -> {
                    return VoicePcm16Sink.WriteResult.Interrupted
                }
                writeResult < 0 -> {
                    Log.w(TAG, "AudioTrack write error: $writeResult")
                    removeTrack(track)
                    track.releaseSafely()
                    return VoicePcm16Sink.WriteResult.Failed("AudioTrack write error: $writeResult")
                }
                writeResult == 0 -> {
                    zeroWrites += 1
                    if (zeroWrites >= MAX_BLOCKING_ZERO_WRITES) {
                        removeTrack(track)
                        track.releaseSafely()
                        return VoicePcm16Sink.WriteResult.Failed(
                            "AudioTrack write made no progress after $zeroWrites attempts",
                        )
                    }
                    Thread.yield()
                }
                else -> {
                    offset += writeResult.coerceAtMost(remaining)
                    zeroWrites = 0
                }
            }
        }

        return when {
            offset == pcm16.size -> VoicePcm16Sink.WriteResult.Written(offset)
            interrupted.get() -> VoicePcm16Sink.WriteResult.Interrupted
            else -> VoicePcm16Sink.WriteResult.Failed(
                "AudioTrack write interrupted after $offset of ${pcm16.size} bytes",
            )
        }
    }

    override fun pauseAndFlush() {
        interrupted.set(true)
        track.pauseSafely()
        track.flushSafely()
    }

    override fun stopAndRelease() {
        interrupted.set(true)
        track.stopSafely()
        track.releaseSafely()
        removeTrack(track)
    }
}
```

- [ ] **Step 10: Run focused audio tests**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest' \
  --rerun-tasks
```

Expected: both test classes pass.

- [ ] **Step 11: Commit Stage 1 core split**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt
git commit -m "refactor: split local cue playback from assistant writer"
```

## Task 5: Verify Waiting Tone Integration Still Holds

**Files:**
- Read: `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneController.kt`
- Read: `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneControllerTest.kt`
- Read: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinatorWaitingToneTest.kt`

- [ ] **Step 1: Run waiting-tone controller tests**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesWaitingToneControllerTest' \
  --rerun-tasks
```

Expected: all tests pass. These tests prove grace delay, repeat timing, stop, close, invalidation, and cue diagnostics still behave as before.

- [ ] **Step 2: Run coordinator waiting-tone tests**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCoordinatorWaitingToneTest' \
  --rerun-tasks
```

Expected: all tests pass. These tests prove queued/running Hermes states still start the tone, and completion/cancel/interruption/reconnect/session-end still stop it.

- [ ] **Step 3: Fix only Stage 1 regressions if tests fail**

If either command fails, inspect the failure and only adjust Stage 1 boundaries:

```text
Allowed fixes:
- local cue player token/generation behavior;
- AndroidVoiceAudioEngine routing between playbackWriter and localCuePlayer;
- local cue diagnostics mapping;
- test assertions that still reference VoicePlaybackSource after the split.

Disallowed fixes in this stage:
- changing HermesWaitingToneController timing semantics;
- changing VoiceAgentCoordinator waiting-tone flags;
- removing setLocalCueErrorHandler;
- extracting Android track factory classes.
```

- [ ] **Step 4: Commit integration fixes if any were needed**

If Step 3 changed files, commit them:

```bash
git add app/src/main/java app/src/test/java
git commit -m "fix: preserve waiting tone integration after cue split"
```

If Step 3 made no changes, do not create a commit.

## Task 6: Final Verification for Stage 1

**Files:**
- Read: `docs/superpowers/specs/2026-06-29-voice-agent-waiting-tone-cleanup-design.md`
- Read: changed source and test files from Tasks 1-5

- [ ] **Step 1: Run the Stage 1 focused verification command**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesWaitingToneControllerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCoordinatorWaitingToneTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallSessionTest' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesJobManagerTest' \
  --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Search for forbidden Stage 1 leftovers**

Run:

```bash
rg -n "VoicePlaybackSource|invalidateLocalCues|localCueGeneration|retiredLocalCueSink|source = VoicePlaybackSource|start\\(source|writeFully\\(pcm16, source" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio
```

Expected: no output.

- [ ] **Step 3: Confirm `VoicePlaybackWriter` no longer owns local cue concepts**

Run:

```bash
rg -n "LocalCue|localCue|cue|token|highestInvalidated" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt
```

Expected: no output.

- [ ] **Step 4: Check working tree state**

Run:

```bash
git status --short --branch
```

Expected: clean branch or only intentional committed-ahead state.

- [ ] **Step 5: Commit any final cleanup**

If Steps 2 or 3 required cleanup edits, commit them:

```bash
git add app/src/main/java app/src/test/java
git commit -m "chore: remove local cue leftovers from playback writer"
```

If Steps 2 and 3 were already clean, do not create a commit.

## Execution Notes

- Work inside the RikkaHub repository: `/home/muly/agora2/external/rikkahub`.
- Start implementation from branch `voice-agent-waiting-tone-cleanup-design` or a new worktree branch created from it.
- This plan intentionally leaves `VoiceAudioEngine.setLocalCueErrorHandler` in place. Removing that callback belongs to Stage 2 when `HermesWaitingToneController` is given a narrower cue dependency or scoped diagnostic path.
- This plan intentionally leaves Android track ownership inside `AndroidVoiceAudioEngine`. Extracting a track factory belongs to Stage 3.
- Do not change waiting-tone timing constants or Hermes job behavior in this stage.

## Self-Review Checklist

- Spec coverage:
  - Stage 1 local cue player: Tasks 1-4.
  - Assistant-only `VoicePlaybackWriter`: Task 3 and Task 6.
  - Caller-facing `VoiceAudioEngine` behavior unchanged: Task 4 and Task 5.
  - Focused verification: Task 6.
- Type consistency:
  - `VoicePcm16Sink.start()` and `VoicePcm16Sink.writeFully(ByteArray)` are source-free everywhere after Task 4.
  - `VoiceLocalCuePlayer.playBase64(base64Pcm16, token)` uses the same token currently passed through `VoiceAudioEngine.playLocalCuePcm16(base64Pcm16, sessionId)`.
  - `VoiceLocalCueDiagnostic` is separate from `VoicePlaybackDiagnostic`.
- Scope guard:
  - Coordinator flag cleanup is not part of this plan.
  - Android playback track extraction is not part of this plan.
