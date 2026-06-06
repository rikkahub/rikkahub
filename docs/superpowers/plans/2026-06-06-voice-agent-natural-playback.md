# Voice Agent Natural Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix Voice Agent speech output so Gemini audio plays at natural speed without intentionally dropping partial PCM chunks.

**Architecture:** Split playback playout from `AndroidVoiceAudioEngine` into a small `VoicePlaybackWriter` that queues decoded PCM chunks and writes them fully, in order, through a sink abstraction. Android-specific `AudioTrack` code remains in `AndroidVoiceAudioEngine`, while queueing, stale-session rejection, and interruption semantics become JVM-testable.

**Tech Stack:** Kotlin, Android `AudioTrack`, Kotlin coroutines `Channel`, JUnit 4, wireless/USB ADB, Gemini Live PCM output at 24 kHz.

---

## Requirements

- Preserve current capture behavior: microphone input remains 16 kHz PCM and still streams to Gemini as `audio/pcm;rate=16000`.
- Preserve current playback sample rate: model output plays through `AudioTrack` at 24 kHz PCM.
- Stop using partial non-blocking playback writes that drop unwritten bytes.
- `playPcm16(...)` must return quickly after enqueueing decoded audio.
- Playback must write queued chunks in order and write each accepted chunk completely unless the session becomes stale, the user interrupts, the session ends, or the engine is released.
- `suppressPlayback()`, `invalidatePlaybackSession()`, `activatePlaybackSession(...)`, and `release()` must invalidate stale queued audio and flush/stop the active `AudioTrack`.
- Diagnostics must make playback behavior visible: enqueue, full write, suppressed/cleared stale playback, malformed base64, write failure, and sink creation failure.
- Real-device verification must prove no `AudioTrack non-blocking write incomplete` logs during a debug-injected spoken response.

## File Structure

- Create `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`
  - Pure Kotlin playback queue and sink contract.
  - Owns a single coroutine worker and a command channel.
  - Knows session/generation state, but does not import Android media classes.
- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
  - Replace direct `playPcm16(...)` `AudioTrack.write(..., WRITE_NON_BLOCKING)` path with `VoicePlaybackWriter`.
  - Keep Android `AudioTrack` creation, focus, release, and error reporting in this file.
  - Add an `AndroidAudioTrackSink` adapter that implements the pure sink contract with blocking/full writes.
- Create `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`
  - JVM tests for ordering, natural full writes, stale-session rejection, suppression, release, and malformed base64.
- Modify no Gemini codec behavior in this plan. The codec already parses output audio and the Android engine already uses 24 kHz playback.

## Design Notes

Android `AudioTrack.WRITE_NON_BLOCKING` can accept only part of a buffer. The current code logs `AudioTrack non-blocking write incomplete` and drops the remaining bytes. That shortens the PCM stream, which makes speech sound fast or clipped. The fix is not to change sample rate; device logs confirmed `FormatInfo{sampleRate=24000}`. The fix is to stop dropping unwritten playback bytes.

`VoicePlaybackWriter` is intentionally small and focused. It accepts base64 PCM strings, decodes them, tags them with the current generation, and sends them to one worker. The worker discards stale generation commands and writes current commands fully through `VoicePcm16Sink`. Interrupt/end/reconnect increment generation and flush the current sink, so old queued commands are skipped without byte-level PCM loss.

---

### Task 1: Add Testable Playback Writer

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`

- [ ] **Step 1: Write failing tests for ordered full playback and session filtering**

Create `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt` with:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackWriterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `play enqueues decoded chunks and writes them fully in order`() {
        val sink = FakeVoicePcm16Sink()
        val diagnostics = mutableListOf<VoicePlaybackDiagnostic>()
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(10L)
        writer.playBase64("AQID", sessionId = 10L)
        writer.playBase64("BAUG", sessionId = 10L)

        assertTrue(sink.awaitWrites(2))
        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5, 6)), sink.writes)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.ChunkWritten && it.bytes == 3 })

        writer.release()
    }

    @Test
    fun `play rejects stale session before enqueue`() {
        val sink = FakeVoicePcm16Sink()
        val diagnostics = mutableListOf<VoicePlaybackDiagnostic>()
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(11L)
        val accepted = writer.playBase64("AQID", sessionId = 12L)

        assertFalse(accepted)
        assertFalse(sink.awaitWrites(1, timeoutMs = 100L))
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.StaleChunkRejected })

        writer.release()
    }

    @Test
    fun `suppress increments generation flushes active sink and skips queued stale chunks`() {
        val sink = FakeVoicePcm16Sink(blockFirstWrite = true)
        val writer = VoicePlaybackWriter(scope = scope, createSink = { sink })

        writer.activateSession(20L)
        assertTrue(writer.playBase64("AQID", sessionId = 20L))
        assertTrue(sink.awaitWriteStarted())
        assertTrue(writer.playBase64("BAUG", sessionId = 20L))

        writer.suppress()
        sink.unblockWrites()

        assertTrue(sink.awaitWrites(1))
        Thread.sleep(150L)
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), sink.writes)
        assertEquals(1, sink.flushCalls)

        writer.release()
    }

    @Test
    fun `release stops sink and rejects future playback`() {
        val sink = FakeVoicePcm16Sink()
        val writer = VoicePlaybackWriter(scope = scope, createSink = { sink })

        writer.activateSession(30L)
        assertTrue(writer.playBase64("AQID", sessionId = 30L))
        assertTrue(sink.awaitWrites(1))
        writer.release()
        val accepted = writer.playBase64("AQID", sessionId = 30L)

        assertFalse(accepted)
        assertEquals(1, sink.stopAndReleaseCalls)
    }

    @Test
    fun `malformed base64 is rejected without creating sink`() {
        var sinkCreateCount = 0
        val diagnostics = mutableListOf<VoicePlaybackDiagnostic>()
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = {
                sinkCreateCount += 1
                FakeVoicePcm16Sink()
            },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(40L)
        val accepted = writer.playBase64("not valid base64", sessionId = 40L)

        assertFalse(accepted)
        assertEquals(0, sinkCreateCount)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.MalformedChunk })

        writer.release()
    }

    private class FakeVoicePcm16Sink(
        private val blockFirstWrite: Boolean = false,
    ) : VoicePcm16Sink {
        private val writeStarted = CountDownLatch(1)
        private val writeLatch = CountDownLatch(2)
        private val unblockLatch = CountDownLatch(1)
        val writes = mutableListOf<List<Byte>>()
        var flushCalls = 0
        var stopAndReleaseCalls = 0
        private var writeCount = 0

        override fun start(): VoicePcm16Sink.StartResult = VoicePcm16Sink.StartResult.Started

        override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
            writeCount += 1
            writeStarted.countDown()
            if (blockFirstWrite && writeCount == 1) {
                unblockLatch.await(500, TimeUnit.MILLISECONDS)
            }
            writes += pcm16.toList()
            writeLatch.countDown()
            return VoicePcm16Sink.WriteResult.Written(bytes = pcm16.size)
        }

        override fun pauseAndFlush() {
            flushCalls += 1
        }

        override fun stopAndRelease() {
            stopAndReleaseCalls += 1
        }

        fun awaitWriteStarted(): Boolean = writeStarted.await(500, TimeUnit.MILLISECONDS)

        fun awaitWrites(count: Int, timeoutMs: Long = 500L): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                if (writes.size >= count) return true
                Thread.sleep(10L)
            }
            return writes.size >= count
        }

        fun unblockWrites() {
            unblockLatch.countDown()
        }
    }

}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
cd /home/muly/src/rikkahub
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest'
```

Expected: compile failure because `VoicePlaybackWriter`, `VoicePcm16Sink`, and `VoicePlaybackDiagnostic` do not exist.

- [ ] **Step 3: Implement `VoicePlaybackWriter`**

Create `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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
    }
}

internal sealed interface VoicePlaybackDiagnostic {
    data class ChunkQueued(val bytes: Int, val generation: Long) : VoicePlaybackDiagnostic
    data class ChunkWritten(val bytes: Int, val generation: Long) : VoicePlaybackDiagnostic
    data class StaleChunkRejected(val generation: Long, val activeGeneration: Long) : VoicePlaybackDiagnostic
    data class MalformedChunk(val message: String) : VoicePlaybackDiagnostic
    data class SinkStartFailed(val message: String) : VoicePlaybackDiagnostic
    data class SinkWriteFailed(val message: String) : VoicePlaybackDiagnostic
    data class PlaybackSuppressed(val generation: Long) : VoicePlaybackDiagnostic
    data object Released : VoicePlaybackDiagnostic
}

internal class VoicePlaybackWriter(
    scope: CoroutineScope,
    private val createSink: () -> VoicePcm16Sink?,
    private val onDiagnostic: (VoicePlaybackDiagnostic) -> Unit = {},
) {
    private val lock = Any()
    private val commands = Channel<PlaybackCommand>(Channel.UNLIMITED)
    private val worker: Job
    private var generation = 0L
    private var activeSessionId: Long? = null
    private var released = false
    private var activeSink: VoicePcm16Sink? = null

    init {
        worker = scope.launch {
            for (command in commands) {
                when (command) {
                    is PlaybackCommand.Play -> playCommand(command)
                    PlaybackCommand.Stop -> stopActiveSink()
                }
            }
        }
    }

    fun playBase64(base64Pcm16: String, sessionId: Long?): Boolean {
        val pcm16 = runCatching { Base64.getDecoder().decode(base64Pcm16) }
            .onFailure { onDiagnostic(VoicePlaybackDiagnostic.MalformedChunk(it.message ?: it.javaClass.simpleName)) }
            .getOrNull()
            ?: return false
        if (pcm16.isEmpty()) return false

        val command = synchronized(lock) {
            if (released) return false
            if (sessionId != null && activeSessionId != sessionId) {
                onDiagnostic(VoicePlaybackDiagnostic.StaleChunkRejected(generation = generation, activeGeneration = generation))
                return false
            }
            PlaybackCommand.Play(pcm16 = pcm16, generation = generation)
        }
        onDiagnostic(VoicePlaybackDiagnostic.ChunkQueued(bytes = pcm16.size, generation = command.generation))
        return commands.trySend(command).isSuccess
    }

    fun activateSession(sessionId: Long) {
        synchronized(lock) {
            if (released) return
            activeSessionId = sessionId
            generation += 1
            activeSink?.pauseAndFlush()
        }
    }

    fun invalidateSession() {
        synchronized(lock) {
            if (released) return
            activeSessionId = null
            generation += 1
            activeSink?.pauseAndFlush()
        }
    }

    fun suppress() {
        val newGeneration = synchronized(lock) {
            if (released) return
            generation += 1
            activeSink?.pauseAndFlush()
            generation
        }
        onDiagnostic(VoicePlaybackDiagnostic.PlaybackSuppressed(generation = newGeneration))
    }

    fun release() {
        val sink = synchronized(lock) {
            if (released) return
            released = true
            generation += 1
            activeSessionId = null
            activeSink.also { activeSink = null }
        }
        sink?.stopAndRelease()
        commands.trySend(PlaybackCommand.Stop)
        commands.close()
        worker.cancel()
        onDiagnostic(VoicePlaybackDiagnostic.Released)
    }

    private fun playCommand(command: PlaybackCommand.Play) {
        if (!isCurrent(command.generation)) {
            onDiagnostic(VoicePlaybackDiagnostic.StaleChunkRejected(command.generation, activeGeneration()))
            return
        }
        val sink = getOrCreateSink() ?: return
        when (val start = sink.start()) {
            VoicePcm16Sink.StartResult.Started -> Unit
            is VoicePcm16Sink.StartResult.Failed -> {
                onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed(start.message))
                clearSink(sink)
                sink.stopAndRelease()
                return
            }
        }
        if (!isCurrent(command.generation)) return
        when (val result = sink.writeFully(command.pcm16)) {
            is VoicePcm16Sink.WriteResult.Written -> {
                if (isCurrent(command.generation)) {
                    onDiagnostic(VoicePlaybackDiagnostic.ChunkWritten(result.bytes, command.generation))
                }
            }
            is VoicePcm16Sink.WriteResult.Failed -> {
                onDiagnostic(VoicePlaybackDiagnostic.SinkWriteFailed(result.message))
                clearSink(sink)
                sink.stopAndRelease()
            }
        }
    }

    private fun getOrCreateSink(): VoicePcm16Sink? {
        synchronized(lock) {
            if (released) return null
            activeSink?.let { return it }
        }
        val newSink = createSink() ?: return null
        synchronized(lock) {
            if (released) {
                newSink.stopAndRelease()
                return null
            }
            activeSink?.let {
                newSink.stopAndRelease()
                return it
            }
            activeSink = newSink
            return newSink
        }
    }

    private fun stopActiveSink() {
        val sink = synchronized(lock) {
            activeSink.also { activeSink = null }
        }
        sink?.stopAndRelease()
    }

    private fun clearSink(sink: VoicePcm16Sink) {
        synchronized(lock) {
            if (activeSink === sink) activeSink = null
        }
    }

    private fun isCurrent(commandGeneration: Long): Boolean = synchronized(lock) {
        !released && generation == commandGeneration
    }

    private fun activeGeneration(): Long = synchronized(lock) { generation }

    private sealed interface PlaybackCommand {
        data class Play(val pcm16: ByteArray, val generation: Long) : PlaybackCommand
        data object Stop : PlaybackCommand
    }
}
```

- [ ] **Step 4: Run tests and fix only compiler-level issues**

Run:

```bash
cd /home/muly/src/rikkahub
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest'
```

Expected: `VoicePlaybackWriterTest` passes. If it fails because a queued stale command writes after `suppress()`, keep the generation checks in `playCommand(...)` and do not add byte-dropping behavior.

- [ ] **Step 5: Commit playback writer**

Run:

```bash
cd /home/muly/src/rikkahub
git add app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt
git commit -m "fix: add ordered voice playback writer"
```

Expected: commit succeeds with only the new playback writer and its tests staged.

---

### Task 2: Wire Android AudioTrack Through Full Blocking Writes

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`

- [ ] **Step 1: Add a failing test for sink write failures**

Append this test to `VoicePlaybackWriterTest`:

```kotlin
@Test
fun `write failure releases bad sink and reports diagnostic`() {
    val sink = object : VoicePcm16Sink {
        var stopAndReleaseCalls = 0
        override fun start(): VoicePcm16Sink.StartResult = VoicePcm16Sink.StartResult.Started
        override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult =
            VoicePcm16Sink.WriteResult.Failed("write failed")
        override fun pauseAndFlush() = Unit
        override fun stopAndRelease() {
            stopAndReleaseCalls += 1
        }
    }
    val diagnostics = mutableListOf<VoicePlaybackDiagnostic>()
    val writer = VoicePlaybackWriter(scope = scope, createSink = { sink }, onDiagnostic = diagnostics::add)

    writer.activateSession(50L)
    assertTrue(writer.playBase64("AQID", sessionId = 50L))

    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500L)
    while (System.nanoTime() < deadline && sink.stopAndReleaseCalls == 0) {
        Thread.sleep(10L)
    }

    assertEquals(1, sink.stopAndReleaseCalls)
    assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.SinkWriteFailed && it.message == "write failed" })
    writer.release()
}
```

- [ ] **Step 2: Run the test**

Run:

```bash
cd /home/muly/src/rikkahub
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest'
```

Expected: the new test passes if Task 1 implemented sink failure handling correctly. If it fails, fix `VoicePlaybackWriter.playCommand(...)` so `WriteResult.Failed` clears and releases the sink exactly once.

- [ ] **Step 3: Replace direct playback fields in `AndroidVoiceAudioEngine`**

In `AndroidVoiceAudioEngine.kt`, keep these fields:

```kotlin
private val playbackWriteLock = Any()
private var audioTrack: AudioTrack? = null
private var playbackWriter: VoicePlaybackWriter? = null
```

Remove these fields:

```kotlin
private var playbackGeneration = 0L
private var playbackSessionId: Long? = null
```

Initialize the writer lazily with this helper:

```kotlin
private fun playbackWriterOrNull(): VoicePlaybackWriter? = synchronized(lock) {
    if (released) return null
    playbackWriter ?: VoicePlaybackWriter(
        scope = scope,
        createSink = { createAudioTrackSinkOrNull() },
        onDiagnostic = ::logPlaybackDiagnostic,
    ).also { playbackWriter = it }
}
```

- [ ] **Step 4: Replace playback session methods**

Replace `playPcm16(...)`, `activatePlaybackSession(...)`, `invalidatePlaybackSession(...)`, and `suppressPlayback()` in `AndroidVoiceAudioEngine.kt` with:

```kotlin
override fun playPcm16(base64Pcm16: String) {
    playPcm16(base64Pcm16 = base64Pcm16, sessionId = null)
}

override fun playPcm16(base64Pcm16: String, sessionId: Long?) {
    playbackWriterOrNull()?.playBase64(base64Pcm16 = base64Pcm16, sessionId = sessionId)
}

override fun activatePlaybackSession(sessionId: Long) {
    playbackWriterOrNull()?.activateSession(sessionId)
}

override fun invalidatePlaybackSession() {
    playbackWriterOrNull()?.invalidateSession()
}

override fun suppressPlayback() {
    playbackWriterOrNull()?.suppress()
}
```

- [ ] **Step 5: Replace release playback cleanup**

In `release()`, replace direct track stop/release cleanup:

```kotlin
track = audioTrack
audioTrack = null
```

and:

```kotlin
synchronized(playbackWriteLock) {
    track?.stopSafely()
    track?.releaseSafely()
}
```

with writer cleanup:

```kotlin
val writer: VoicePlaybackWriter?
synchronized(lock) {
    writer = playbackWriter
    playbackWriter = null
    audioTrack = null
}
writer?.release()
```

Keep capture cleanup and audio focus cleanup unchanged.

- [ ] **Step 6: Add Android sink adapter**

Add this nested class near the bottom of `AndroidVoiceAudioEngine.kt`, before the companion object:

```kotlin
private inner class AndroidAudioTrackSink(
    private val track: AudioTrack,
) : VoicePcm16Sink {
    override fun start(): VoicePcm16Sink.StartResult {
        return if (track.playSafely()) {
            VoicePcm16Sink.StartResult.Started
        } else {
            VoicePcm16Sink.StartResult.Failed("AudioTrack play failed")
        }
    }

    override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
        var offset = 0
        while (offset < pcm16.size) {
            val remaining = pcm16.size - offset
            val writeResult = runCatching {
                track.write(pcm16, offset, remaining, AudioTrack.WRITE_BLOCKING)
            }.onFailure {
                Log.w(TAG, "AudioTrack blocking write failed", it)
                notifyAudioError("AudioTrack write failed: ${it.message ?: it.javaClass.simpleName}")
                removeTrack(track)
            }.getOrNull() ?: return VoicePcm16Sink.WriteResult.Failed("AudioTrack write threw")

            if (writeResult < 0) {
                Log.w(TAG, "AudioTrack blocking write error: $writeResult")
                notifyAudioError("AudioTrack write error: $writeResult")
                removeTrack(track)
                return VoicePcm16Sink.WriteResult.Failed("AudioTrack write error: $writeResult")
            }
            if (writeResult == 0) {
                Thread.yield()
            } else {
                offset += writeResult.coerceAtMost(remaining)
            }
        }
        return VoicePcm16Sink.WriteResult.Written(bytes = pcm16.size)
    }

    override fun pauseAndFlush() {
        synchronized(playbackWriteLock) {
            track.pauseSafely()
            track.flushSafely()
        }
    }

    override fun stopAndRelease() {
        synchronized(playbackWriteLock) {
            track.stopSafely()
            track.releaseSafely()
        }
        removeTrack(track)
    }
}
```

- [ ] **Step 7: Replace `createAudioTrackOrNull()` usage with sink creation**

Add this helper:

```kotlin
private fun createAudioTrackSinkOrNull(): VoicePcm16Sink? {
    val track = getOrCreatePlaybackTrack() ?: return null
    return AndroidAudioTrackSink(track)
}
```

Change `getOrCreatePlaybackTrack(...)` to no longer accept a generation:

```kotlin
private fun getOrCreatePlaybackTrack(): AudioTrack? {
    val existingTrack = synchronized(lock) { audioTrack }
    if (existingTrack != null) return existingTrack

    val newTrack = createAudioTrackOrNull() ?: return null
    var shouldReleaseNewTrack = false
    synchronized(lock) {
        val currentTrack = audioTrack
        if (released) {
            shouldReleaseNewTrack = true
        } else if (currentTrack == null) {
            audioTrack = newTrack
        } else {
            shouldReleaseNewTrack = true
        }
    }
    if (shouldReleaseNewTrack) {
        newTrack.releaseSafely()
    }
    return synchronized(lock) { audioTrack }
}
```

Remove these obsolete helpers:

```kotlin
private fun isCurrentPlayback(generation: Long, track: AudioTrack): Boolean
private fun currentPlaybackTrack(track: AudioTrack, generation: Long): AudioTrack?
private fun AudioTrack.writeNonBlocking(pcm16: ByteArray, generation: Long)
```

- [ ] **Step 8: Add playback diagnostics logging**

Add this helper in `AndroidVoiceAudioEngine.kt`:

```kotlin
private fun logPlaybackDiagnostic(event: VoicePlaybackDiagnostic) {
    when (event) {
        is VoicePlaybackDiagnostic.ChunkQueued -> Log.d(TAG, "playback queued bytes=${event.bytes} generation=${event.generation}")
        is VoicePlaybackDiagnostic.ChunkWritten -> Log.d(TAG, "playback wrote bytes=${event.bytes} generation=${event.generation}")
        is VoicePlaybackDiagnostic.StaleChunkRejected -> Log.d(
            TAG,
            "playback stale chunk rejected generation=${event.generation} active=${event.activeGeneration}",
        )
        is VoicePlaybackDiagnostic.MalformedChunk -> Log.w(TAG, "Dropping malformed playback chunk: ${event.message}")
        is VoicePlaybackDiagnostic.SinkStartFailed -> Log.w(TAG, "AudioTrack start failed: ${event.message}")
        is VoicePlaybackDiagnostic.SinkWriteFailed -> Log.w(TAG, "AudioTrack write failed: ${event.message}")
        is VoicePlaybackDiagnostic.PlaybackSuppressed -> Log.d(TAG, "playback suppressed generation=${event.generation}")
        VoicePlaybackDiagnostic.Released -> Log.d(TAG, "playback writer released")
    }
}
```

- [ ] **Step 9: Run focused unit tests**

Run:

```bash
cd /home/muly/src/rikkahub
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest' --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentViewModelTest'
```

Expected: both focused suites pass. If `VoiceAgentViewModelTest` fails because the fake audio engine expects synchronous playback, update only the fake test expectations; do not reintroduce byte-dropping in production playback.

- [ ] **Step 10: Commit Android wiring**

Run:

```bash
cd /home/muly/src/rikkahub
git add app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt
git commit -m "fix: play voice audio with full blocking writes"
```

Expected: commit contains the Android wiring and any necessary focused test adjustment.

---

### Task 3: Add Regression Guard Against Partial-Write Drops

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`

- [ ] **Step 1: Add a regression test for partial sink writes**

Append this test to `VoicePlaybackWriterTest`:

```kotlin
@Test
fun `writer does not split or drop bytes when sink accepts full chunk`() {
    val sink = FakeVoicePcm16Sink()
    val writer = VoicePlaybackWriter(scope = scope, createSink = { sink })

    writer.activateSession(60L)
    assertTrue(writer.playBase64("AAECAwQFBgcICQ==", sessionId = 60L))

    assertTrue(sink.awaitWrites(1))
    assertEquals(listOf((0..9).map { it.toByte() }), sink.writes)

    writer.release()
}
```

- [ ] **Step 2: Run the regression test**

Run:

```bash
cd /home/muly/src/rikkahub
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest'
```

Expected: test passes. This proves the queue layer hands whole chunks to the sink; Android-specific full-write behavior is covered by code review and device logs because `AudioTrack` is not available in JVM unit tests.

- [ ] **Step 3: Remove obsolete warning string**

Search:

```bash
cd /home/muly/src/rikkahub
rg -n "AudioTrack non-blocking write incomplete|WRITE_NON_BLOCKING|MAX_NON_BLOCKING_WRITE_ATTEMPTS" app/src/main/java/me/rerere/rikkahub/voiceagent/audio
```

Expected before edit: old references may still appear if Task 2 missed cleanup.

Remove all production references to:

```kotlin
AudioTrack.WRITE_NON_BLOCKING
MAX_NON_BLOCKING_WRITE_ATTEMPTS
"AudioTrack non-blocking write incomplete"
```

Expected after edit:

```bash
rg -n "AudioTrack non-blocking write incomplete|WRITE_NON_BLOCKING|MAX_NON_BLOCKING_WRITE_ATTEMPTS" app/src/main/java/me/rerere/rikkahub/voiceagent/audio
```

prints no matches.

- [ ] **Step 4: Run focused tests and assemble**

Run:

```bash
cd /home/muly/src/rikkahub
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest'
./gradlew --no-daemon :app:assembleDebug
```

Expected: unit test passes and `:app:assembleDebug` ends with `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit regression guard**

Run:

```bash
cd /home/muly/src/rikkahub
git add app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt
git commit -m "test: guard voice playback against partial-write drops"
```

Expected: commit succeeds with the regression test and cleanup.

---

### Task 4: Device Verification

**Files:**
- Read: `app/build/outputs/apk/debug/app-universal-debug.apk`
- Read: `/tmp/voice_probe_env.sh`
- Device: `RZCX71NXRPB` through `ADB_SERVER_SOCKET=tcp:100.69.79.32:5037`

- [ ] **Step 1: Build APK with existing Voice Agent credentials**

Run without printing secrets:

```bash
cd /home/muly/src/rikkahub
source /tmp/voice_probe_env.sh
export CF_ACCESS_CLIENT_ID="$CF_ID"
export CF_ACCESS_CLIENT_SECRET="$CF_SECRET"
unset VOICE_AGENT_BASE_URL_OVERRIDE
./gradlew --no-daemon :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install on the connected device**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb devices -l
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB install -r /home/muly/src/rikkahub/app/build/outputs/apk/debug/app-universal-debug.apk
```

Expected:

```text
RZCX71NXRPB device ...
Success
```

- [ ] **Step 3: Launch app and keep screen awake during the test**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell svc power stayon true
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell monkey -p me.rerere.rikkahub.debug 1
```

Expected: RikkaHub debug app is foreground. If the device is locked, ask the user to unlock it before continuing.

- [ ] **Step 4: Start Voice Agent and clear logs**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB logcat -c
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell input tap 550 170
sleep 10
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell screencap -p /sdcard/voice_playback_connected.png
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB pull /sdcard/voice_playback_connected.png /tmp/voice_playback_connected.png
```

Expected screenshot: `Voice Agent`, `Session Connected`, controls visible.

- [ ] **Step 5: Inject deterministic audio prompt**

If `/tmp/voice-agent-test.pcm` exists, use it. If it is missing, create it:

```bash
curl -L --get \
  --data-urlencode "q=Ask Hermes say okay only" \
  --data-urlencode "tl=en" \
  --data-urlencode "client=tw-ob" \
  "https://translate.google.com/translate_tts" \
  -o /tmp/voice-agent-test.mp3
ffmpeg -y -i /tmp/voice-agent-test.mp3 -ac 1 -ar 16000 -f s16le -acodec pcm_s16le /tmp/voice-agent-test.pcm
```

Push and inject:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB push /tmp/voice-agent-test.pcm /data/local/tmp/voice-agent-test.pcm
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell run-as me.rerere.rikkahub.debug cp /data/local/tmp/voice-agent-test.pcm /data/user/0/me.rerere.rikkahub.debug/files/voice-agent-test.pcm
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell am broadcast \
  -a me.rerere.rikkahub.debug.voiceagent.INJECT_PCM \
  -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.debug.VoiceAudioDebugInjectionReceiver \
  --es path voice-agent-test.pcm \
  --ei chunk_bytes 3200 \
  --el chunk_delay_ms 100 \
  --el leading_silence_ms 300 \
  --el trailing_silence_ms 1800
sleep 35
```

Expected: broadcast result is `0`, Voice Agent screen eventually shows Hermes/MS-agent answered and History saved.

- [ ] **Step 6: Verify logs prove natural playback path**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB logcat -d -v time | rg -n "AndroidVoiceAudioEngine|VoicePlayback|AudioTrack non-blocking write incomplete|WRITE_NON_BLOCKING|toolCall|toolResponse|Hermes|OutputAudio|playback wrote|playback queued" | tail -n 240
```

Expected:

- Contains `playback queued ...` and `playback wrote ...` diagnostics.
- Contains `receive kind=toolCall`.
- Contains `send kind=toolResponse sent=true`.
- Does not contain `AudioTrack non-blocking write incomplete`.
- Does not contain Cloudflare HTML/auth failures.

- [ ] **Step 7: End session and restore device power behavior**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell input tap 795 2018
sleep 5
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell svc power stayon false
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell dumpsys power | rg -n "mStayOn=|Wake Locks:|PARTIAL_WAKE_LOCK"
```

Expected: `mStayOn=false`; no RikkaHub voice wake lock remains after the session ends.

- [ ] **Step 8: Commit final verification notes if the repository keeps execution logs**

If `execution-gaps.md` or an equivalent local verification log already exists in this branch, append a short entry:

```markdown
2026-06-06 voice playback fix verification:
- Device: RZCX71NXRPB
- Voice Agent connected.
- Debug injected prompt triggered Gemini tool call and Hermes response.
- Playback diagnostics showed queued/written chunks.
- No "AudioTrack non-blocking write incomplete" logs after the fix.
```

Then run:

```bash
cd /home/muly/src/rikkahub
git add execution-gaps.md
git commit -m "docs: record voice playback device verification"
```

Expected: commit succeeds only if a verification log file already exists and was updated. If no such file exists, skip this commit and record the same facts in the final implementation report.

---

## Self-Review

- Spec coverage: The plan covers natural speech priority, full ordered playback, interrupt/end/reconnect stale rejection, diagnostics, tests, assemble, and device verification.
- Scope check: This is one subsystem: Android Voice Agent output playback. Gemini tool calling, Hermes auth, chat persistence, and UI layout are intentionally out of scope.
- Placeholder scan: The plan contains concrete file paths, code snippets, commands, and expected results. It does not leave implementation decisions open.
- Type consistency: `VoicePlaybackWriter`, `VoicePcm16Sink`, and `VoicePlaybackDiagnostic` are introduced in Task 1 and reused with the same names in later tasks.
