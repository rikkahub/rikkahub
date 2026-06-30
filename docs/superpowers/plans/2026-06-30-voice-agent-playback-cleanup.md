# Voice Agent Playback Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean up voice-agent playback ownership by making local cue tokens explicit, extracting Android playback track management, and sharing only mechanical PCM sink lifecycle code.

**Architecture:** Assistant playback and local cue playback keep separate state machines and diagnostics. `VoiceLocalCueToken` makes cue invalidation distinct from voice session ids. `AndroidVoicePlaybackTracks` owns Android `AudioTrack` state, while `VoicePcm16SinkLifecycle` centralizes sink start/write/release mechanics without owning playback policy.

**Tech Stack:** Kotlin, Android `AudioTrack`/`AudioRecord`, coroutines, JUnit 4, Gradle Android unit tests.

---

## File Structure

- Create `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCueToken.kt`
  - Public value type for cue invalidation tokens used by `VoiceAudioEngine` and Hermes.
- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioEngine.kt`
  - Rename local cue parameters from `sessionId` to `cueToken`.
- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneController.kt`
  - Wrap the existing monotonic generation in `VoiceLocalCueToken`.
- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`
  - Store and compare `VoiceLocalCueToken` values.
- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
  - Delegate local cue token parameters to `VoiceLocalCuePlayer`.
  - Delegate playback track ownership to `AndroidVoicePlaybackTracks`.
- Modify test fakes in `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentFakes.kt` and `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneControllerTest.kt`
  - Use cue-token names and types.
- Create `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycle.kt`
  - Pure helper for sink start, write, pause/flush, and stop/release.
- Create `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycleTest.kt`
  - Focused unit tests for mechanical sink outcomes.
- Create `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoicePlaybackTracks.kt`
  - Android playback track owner and `AndroidAudioTrackSink` implementation.

## Task 1: Make Local Cue Tokens Explicit

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCueToken.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioEngine.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneController.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentFakes.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneControllerTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneControllerTest.kt`

- [ ] **Step 1: Write the failing token-focused local cue test**

In `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt`, replace the test named `invalidation rejects later enqueue with invalidated token` with this version:

```kotlin
@Test
fun `invalidation rejects later enqueue with invalidated cue token`() {
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

    val invalidatedToken = VoiceLocalCueToken(10L)
    player.invalidate(cueToken = invalidatedToken)

    assertFalse(player.playBase64(base64Pcm16 = "AQID", cueToken = invalidatedToken))
    assertEquals(0, sinkCreations)
    val stale = diagnostics.filterIsInstance<VoiceLocalCueDiagnostic.StaleCueRejected>().single()
    assertEquals(invalidatedToken, stale.rejectedCueToken)

    assertTrue(player.playBase64(base64Pcm16 = "BAUG", cueToken = VoiceLocalCueToken(11L)))
    assertTrue(sink.awaitWrites(1))
    assertEquals(listOf(listOf<Byte>(4, 5, 6)), sink.writes)

    player.release()
    scope.cancel()
}
```

- [ ] **Step 2: Run the focused tests to verify the token API fails**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest.invalidation rejects later enqueue with invalidated cue token' \
  --rerun-tasks
```

Expected: FAIL during Kotlin compilation because `VoiceLocalCueToken`, `cueToken`, and `rejectedCueToken` do not exist yet.

- [ ] **Step 3: Add the cue token value type**

Create `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCueToken.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

@JvmInline
value class VoiceLocalCueToken(val value: Long)
```

- [ ] **Step 4: Update the public audio engine interface**

In `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioEngine.kt`, replace the local cue methods with:

```kotlin
fun playLocalCuePcm16(base64Pcm16: String, cueToken: VoiceLocalCueToken? = null): Boolean
fun invalidateLocalCuePlayback(cueToken: VoiceLocalCueToken? = null) = Unit
```

Leave assistant playback methods using `sessionId: Long?`; those are real voice session ids.

- [ ] **Step 5: Update `VoiceLocalCuePlayer` signatures and diagnostics**

In `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`, change the diagnostic field:

```kotlin
data class StaleCueRejected(
    val generation: Long,
    val activeGeneration: Long,
    val rejectedCueToken: VoiceLocalCueToken? = null,
) : VoiceLocalCueDiagnostic
```

Change state and method signatures:

```kotlin
private var highestInvalidatedToken: VoiceLocalCueToken? = null

fun playBase64(base64Pcm16: String, cueToken: VoiceLocalCueToken?): Boolean

fun invalidate(cueToken: VoiceLocalCueToken? = null)
```

Inside `playBase64`, replace `token` with `cueToken`:

```kotlin
} else if (isCueTokenInvalidatedLocked(cueToken)) {
    rejectedGeneration = generation
    null
} else {
    PlaybackCommand.Play(
        pcm16 = pcm16,
        generation = generation,
        cueToken = cueToken,
    )
}
```

When emitting stale diagnostics from enqueue rejection, use:

```kotlin
VoiceLocalCueDiagnostic.StaleCueRejected(
    generation = activeGeneration,
    activeGeneration = activeGeneration,
    rejectedCueToken = cueToken,
)
```

Inside `invalidate`, replace token invalidation with:

```kotlin
cueToken?.let { invalidatedToken ->
    val highestValue = maxOf(
        highestInvalidatedToken?.value ?: invalidatedToken.value,
        invalidatedToken.value,
    )
    highestInvalidatedToken = VoiceLocalCueToken(highestValue)
}
```

Replace every `rejectedToken = command.token` with:

```kotlin
rejectedCueToken = command.cueToken
```

Replace `isTokenInvalidatedLocked` with:

```kotlin
private fun isCueTokenInvalidatedLocked(cueToken: VoiceLocalCueToken?): Boolean =
    cueToken != null && highestInvalidatedToken?.let { cueToken.value <= it.value } == true
```

Replace the playback command with:

```kotlin
private sealed interface PlaybackCommand {
    data class Play(
        val pcm16: ByteArray,
        val generation: Long,
        val cueToken: VoiceLocalCueToken?,
    ) : PlaybackCommand
}
```

- [ ] **Step 6: Update Android engine local cue overrides**

In `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`, replace the local cue overrides with:

```kotlin
override fun playLocalCuePcm16(base64Pcm16: String, cueToken: VoiceLocalCueToken?): Boolean {
    return localCuePlayer.playBase64(base64Pcm16 = base64Pcm16, cueToken = cueToken)
}

override fun invalidateLocalCuePlayback(cueToken: VoiceLocalCueToken?) {
    localCuePlayer.invalidate(cueToken = cueToken)
}
```

In `handleLocalCueDiagnostic`, replace the stale log token field with:

```kotlin
"active=${diagnostic.activeGeneration} cueToken=${diagnostic.rejectedCueToken}",
```

- [ ] **Step 7: Update Hermes waiting-tone calls**

In `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneController.kt`, add the import:

```kotlin
import me.rerere.rikkahub.voiceagent.audio.VoiceLocalCueToken
```

In `stop()`, replace the invalidation call with:

```kotlin
audio.invalidateLocalCuePlayback(cueToken = stoppedGeneration?.let(::VoiceLocalCueToken))
```

In `playCue`, replace the playback call with:

```kotlin
audio.playLocalCuePcm16(
    base64Pcm16 = toneBase64Pcm16,
    cueToken = VoiceLocalCueToken(loopGeneration),
)
```

- [ ] **Step 8: Update test fakes and Hermes test engines**

In `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentFakes.kt`, rename local cue fields and overrides:

```kotlin
val playedLocalCueTokens = CopyOnWriteArrayList<VoiceLocalCueToken?>()
val invalidatedLocalCueTokens = CopyOnWriteArrayList<VoiceLocalCueToken?>()
```

```kotlin
override fun playLocalCuePcm16(base64Pcm16: String, cueToken: VoiceLocalCueToken?): Boolean {
    localCuePlaybackAttemptCount.incrementAndGet()
    localCuePlaybackError?.let { throw it }
    if (failLocalCuePlayback) {
        return false
    }
    playedLocalCueTokens += cueToken
    playedLocalCuePcm16 += base64Pcm16
    return true
}

override fun invalidateLocalCuePlayback(cueToken: VoiceLocalCueToken?) {
    invalidateLocalCuePlaybackCalls += 1
    invalidatedLocalCueTokens += cueToken
    localCueInvalidationError?.let { throw it }
}
```

In `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneControllerTest.kt`, add this import:

```kotlin
import me.rerere.rikkahub.voiceagent.audio.VoiceLocalCueToken
```

Change every fake `VoiceAudioEngine` local cue override to:

```kotlin
override fun playLocalCuePcm16(base64Pcm16: String, cueToken: VoiceLocalCueToken?): Boolean
```

Change every fake invalidation override to:

```kotlin
override fun invalidateLocalCuePlayback(cueToken: VoiceLocalCueToken?)
```

If a test stores a generation value, store `cueToken?.value` or compare against `VoiceLocalCueToken(expectedValue)`.

- [ ] **Step 9: Rename local cue test call sites**

In `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt`, update all calls:

```kotlin
player.playBase64(base64Pcm16 = "AQID", cueToken = null)
player.invalidate(cueToken = null)
```

For non-null cue tokens, use:

```kotlin
VoiceLocalCueToken(10L)
```

Do not change assistant playback tests that use `sessionId`.

- [ ] **Step 10: Run token-focused tests**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesWaitingToneControllerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCoordinatorWaitingToneTest' \
  --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Check local cue naming cleanup**

Run:

```bash
rg -n "playLocalCuePcm16\\([^\\n]*sessionId|invalidateLocalCuePlayback\\([^\\n]*sessionId|rejectedToken|playedLocalCueSessionIds|invalidatedLocalCueSessionIds" \
  app/src/main/java/me/rerere/rikkahub/voiceagent \
  app/src/test/java/me/rerere/rikkahub/voiceagent
```

Expected: no matches.

- [ ] **Step 12: Commit**

Run:

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCueToken.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioEngine.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneController.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentFakes.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneControllerTest.kt
git commit -m "refactor: name local cue playback tokens"
```

## Task 2: Add PCM Sink Lifecycle Helper

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycle.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycleTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycleTest.kt`

- [ ] **Step 1: Write failing lifecycle helper tests**

Create `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycleTest.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePcm16SinkLifecycleTest {
    @Test
    fun `create started returns started sink`() {
        val sink = FakeVoicePcm16Sink()

        val outcome = VoicePcm16SinkLifecycle.createStarted(
            createSink = { sink },
            nullSinkMessage = "missing sink",
        )

        assertTrue(outcome is VoicePcm16SinkLifecycle.StartOutcome.Started)
        assertSame(sink, (outcome as VoicePcm16SinkLifecycle.StartOutcome.Started).sink)
        assertEquals(1, sink.startCalls)
    }

    @Test
    fun `create started maps null sink to failure`() {
        val outcome = VoicePcm16SinkLifecycle.createStarted(
            createSink = { null },
            nullSinkMessage = "missing sink",
        )

        assertEquals(
            VoicePcm16SinkLifecycle.StartOutcome.Failed("missing sink"),
            outcome,
        )
    }

    @Test
    fun `create started releases sink when start result fails`() {
        val sink = FakeVoicePcm16Sink(
            startResult = VoicePcm16Sink.StartResult.Failed("play failed"),
        )

        val outcome = VoicePcm16SinkLifecycle.createStarted(
            createSink = { sink },
            nullSinkMessage = "missing sink",
        )

        assertEquals(
            VoicePcm16SinkLifecycle.StartOutcome.Failed("play failed"),
            outcome,
        )
        assertEquals(1, sink.stopAndReleaseCalls)
    }

    @Test
    fun `create started releases sink when start throws`() {
        val sink = FakeVoicePcm16Sink(startException = IllegalStateException("start exploded"))

        val outcome = VoicePcm16SinkLifecycle.createStarted(
            createSink = { sink },
            nullSinkMessage = "missing sink",
        )

        assertEquals(
            VoicePcm16SinkLifecycle.StartOutcome.Failed("start exploded"),
            outcome,
        )
        assertEquals(1, sink.stopAndReleaseCalls)
    }

    @Test
    fun `write fully maps success failure interrupted and exception`() {
        val written = VoicePcm16SinkLifecycle.writeFully(
            sink = FakeVoicePcm16Sink(writeResult = VoicePcm16Sink.WriteResult.Written(3)),
            pcm16 = byteArrayOf(1, 2, 3),
        )
        val failed = VoicePcm16SinkLifecycle.writeFully(
            sink = FakeVoicePcm16Sink(writeResult = VoicePcm16Sink.WriteResult.Failed("bad write")),
            pcm16 = byteArrayOf(1),
        )
        val interrupted = VoicePcm16SinkLifecycle.writeFully(
            sink = FakeVoicePcm16Sink(writeResult = VoicePcm16Sink.WriteResult.Interrupted),
            pcm16 = byteArrayOf(1),
        )
        val exploded = VoicePcm16SinkLifecycle.writeFully(
            sink = FakeVoicePcm16Sink(writeException = IllegalStateException("write exploded")),
            pcm16 = byteArrayOf(1),
        )

        assertEquals(VoicePcm16SinkLifecycle.WriteOutcome.Written(3), written)
        assertEquals(VoicePcm16SinkLifecycle.WriteOutcome.Failed("bad write"), failed)
        assertEquals(VoicePcm16SinkLifecycle.WriteOutcome.Interrupted, interrupted)
        assertEquals(VoicePcm16SinkLifecycle.WriteOutcome.Failed("write exploded"), exploded)
    }

    @Test
    fun `release helpers ignore sink exceptions`() {
        val sink = FakeVoicePcm16Sink(
            pauseException = IllegalStateException("pause exploded"),
            stopException = IllegalStateException("stop exploded"),
        )

        VoicePcm16SinkLifecycle.pauseAndFlush(sink)
        VoicePcm16SinkLifecycle.stopAndRelease(sink)
        VoicePcm16SinkLifecycle.stopAndReleaseDistinct(first = sink, second = sink)

        assertEquals(1, sink.pauseAndFlushCalls)
        assertEquals(2, sink.stopAndReleaseCalls)
    }

    private class FakeVoicePcm16Sink(
        private val startResult: VoicePcm16Sink.StartResult = VoicePcm16Sink.StartResult.Started,
        private val writeResult: VoicePcm16Sink.WriteResult = VoicePcm16Sink.WriteResult.Written(0),
        private val startException: RuntimeException? = null,
        private val writeException: RuntimeException? = null,
        private val pauseException: RuntimeException? = null,
        private val stopException: RuntimeException? = null,
    ) : VoicePcm16Sink {
        var startCalls = 0
        var pauseAndFlushCalls = 0
        var stopAndReleaseCalls = 0

        override fun start(): VoicePcm16Sink.StartResult {
            startCalls += 1
            startException?.let { throw it }
            return startResult
        }

        override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
            writeException?.let { throw it }
            return writeResult
        }

        override fun pauseAndFlush() {
            pauseAndFlushCalls += 1
            pauseException?.let { throw it }
        }

        override fun stopAndRelease() {
            stopAndReleaseCalls += 1
            stopException?.let { throw it }
        }
    }
}
```

- [ ] **Step 2: Run helper tests to verify they fail**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePcm16SinkLifecycleTest' \
  --rerun-tasks
```

Expected: FAIL during Kotlin compilation because `VoicePcm16SinkLifecycle` does not exist.

- [ ] **Step 3: Implement the lifecycle helper**

Create `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycle.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

internal object VoicePcm16SinkLifecycle {
    sealed interface StartOutcome {
        data class Started(val sink: VoicePcm16Sink) : StartOutcome
        data class Failed(val message: String) : StartOutcome
    }

    sealed interface WriteOutcome {
        data class Written(val bytes: Int) : WriteOutcome
        data class Failed(val message: String) : WriteOutcome
        data object Interrupted : WriteOutcome
    }

    fun createStarted(
        createSink: () -> VoicePcm16Sink?,
        nullSinkMessage: String,
    ): StartOutcome {
        val sink = try {
            createSink()
        } catch (e: Exception) {
            return StartOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } ?: return StartOutcome.Failed(nullSinkMessage)

        val startResult = try {
            sink.start()
        } catch (e: Exception) {
            stopAndRelease(sink)
            return StartOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }

        return when (startResult) {
            VoicePcm16Sink.StartResult.Started -> StartOutcome.Started(sink)
            is VoicePcm16Sink.StartResult.Failed -> {
                stopAndRelease(sink)
                StartOutcome.Failed(startResult.message)
            }
        }
    }

    fun writeFully(
        sink: VoicePcm16Sink,
        pcm16: ByteArray,
    ): WriteOutcome {
        return try {
            when (val result = sink.writeFully(pcm16)) {
                is VoicePcm16Sink.WriteResult.Written -> WriteOutcome.Written(result.bytes)
                is VoicePcm16Sink.WriteResult.Failed -> WriteOutcome.Failed(result.message)
                VoicePcm16Sink.WriteResult.Interrupted -> WriteOutcome.Interrupted
            }
        } catch (e: Exception) {
            WriteOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun pauseAndFlush(sink: VoicePcm16Sink?) {
        runCatching {
            sink?.pauseAndFlush()
        }
    }

    fun stopAndRelease(sink: VoicePcm16Sink?) {
        runCatching {
            sink?.stopAndRelease()
        }
    }

    fun stopAndReleaseDistinct(first: VoicePcm16Sink?, second: VoicePcm16Sink?) {
        stopAndRelease(first)
        if (second !== first) {
            stopAndRelease(second)
        }
    }
}
```

- [ ] **Step 4: Run helper tests**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePcm16SinkLifecycleTest' \
  --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

Run:

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycle.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycleTest.kt
git commit -m "test: cover PCM sink lifecycle helper"
```

## Task 3: Use PCM Sink Lifecycle in Writer and Local Cue Player

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayerTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycleTest.kt`

- [ ] **Step 1: Replace writer sink release calls with the helper**

In `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`, replace direct release calls:

```kotlin
sink?.stopAndRelease()
```

with:

```kotlin
VoicePcm16SinkLifecycle.stopAndRelease(sink)
```

Replace direct non-null release calls:

```kotlin
sink.stopAndRelease()
```

with:

```kotlin
VoicePcm16SinkLifecycle.stopAndRelease(sink)
```

- [ ] **Step 2: Replace writer write handling**

In `VoicePlaybackWriter.playCommand`, replace the `try { sink.writeFully(...) } catch` block and result `when` with:

```kotlin
when (val result = VoicePcm16SinkLifecycle.writeFully(sink, command.pcm16)) {
    is VoicePcm16SinkLifecycle.WriteOutcome.Written -> {
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
    is VoicePcm16SinkLifecycle.WriteOutcome.Failed -> {
        if (clearSink(sink)) {
            VoicePcm16SinkLifecycle.stopAndRelease(sink)
        }
        onDiagnostic(VoicePlaybackDiagnostic.SinkWriteFailed(result.message))
    }
    VoicePcm16SinkLifecycle.WriteOutcome.Interrupted -> {
        if (clearSink(sink)) {
            VoicePcm16SinkLifecycle.stopAndRelease(sink)
        }
        emitStale(command.generation)
    }
}
```

- [ ] **Step 3: Replace writer sink creation handling**

In `VoicePlaybackWriter.getOrCreateSink`, replace the `createSink()` and `start()` block with:

```kotlin
val newSink = when (
    val startOutcome = VoicePcm16SinkLifecycle.createStarted(
        createSink = createSink,
        nullSinkMessage = "Playback sink creation failed",
    )
) {
    is VoicePcm16SinkLifecycle.StartOutcome.Started -> startOutcome.sink
    is VoicePcm16SinkLifecycle.StartOutcome.Failed -> {
        onDiagnostic(VoicePlaybackDiagnostic.SinkStartFailed(startOutcome.message))
        return null
    }
}
```

When the newly created sink loses a race or is not selected, release it with:

```kotlin
VoicePcm16SinkLifecycle.stopAndRelease(newSink)
```

- [ ] **Step 4: Replace local cue sink release calls with the helper**

In `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`, replace direct pause and release calls:

```kotlin
result.sinkToRelease?.stopAndRelease()
result.sinkToFlush?.pauseAndFlush()
```

with:

```kotlin
VoicePcm16SinkLifecycle.stopAndRelease(result.sinkToRelease)
VoicePcm16SinkLifecycle.pauseAndFlush(result.sinkToFlush)
```

Replace `retired.stopAndRelease()` with:

```kotlin
VoicePcm16SinkLifecycle.stopAndReleaseDistinct(
    first = retired.active,
    second = retired.retired,
)
```

Then remove the `RetiredSinks.stopAndRelease()` method body from the data class so the class is only:

```kotlin
private data class RetiredSinks(
    val active: VoicePcm16Sink?,
    val retired: VoicePcm16Sink?,
)
```

- [ ] **Step 5: Replace local cue write handling**

In `VoiceLocalCuePlayer.playCommand`, replace the `try { sink.writeFully(...) } catch` block and result `when` with:

```kotlin
when (val result = VoicePcm16SinkLifecycle.writeFully(sink, command.pcm16)) {
    is VoicePcm16SinkLifecycle.WriteOutcome.Written -> {
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
    is VoicePcm16SinkLifecycle.WriteOutcome.Failed -> {
        if (clearSink(sink)) {
            VoicePcm16SinkLifecycle.stopAndRelease(sink)
        }
        onDiagnostic(VoiceLocalCueDiagnostic.SinkWriteFailed(result.message))
    }
    VoicePcm16SinkLifecycle.WriteOutcome.Interrupted -> {
        if (clearSink(sink)) {
            VoicePcm16SinkLifecycle.stopAndRelease(sink)
        }
        emitStale(command.generation)
    }
}
```

- [ ] **Step 6: Replace local cue sink creation handling**

In `VoiceLocalCuePlayer.getOrCreateSink`, replace the `createSink()` and `start()` block with:

```kotlin
val newSink = when (
    val startOutcome = VoicePcm16SinkLifecycle.createStarted(
        createSink = createSink,
        nullSinkMessage = "Local cue sink creation failed",
    )
) {
    is VoicePcm16SinkLifecycle.StartOutcome.Started -> startOutcome.sink
    is VoicePcm16SinkLifecycle.StartOutcome.Failed -> {
        onDiagnostic(VoiceLocalCueDiagnostic.SinkStartFailed(startOutcome.message))
        return null
    }
}
```

When the newly created sink loses a race or is not selected, release it with:

```kotlin
VoicePcm16SinkLifecycle.stopAndRelease(newSink)
```

- [ ] **Step 7: Run playback tests**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePcm16SinkLifecycleTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest' \
  --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Check duplicated raw sink operations are gone from writer and player**

Run:

```bash
rg -n "try \\{|\\.start\\(\\)|\\.writeFully\\(|\\.pauseAndFlush\\(\\)|\\.stopAndRelease\\(\\)" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt
```

Expected:

- `VoicePlaybackWriter.kt` and `VoiceLocalCuePlayer.kt` may still contain `try` for base64 decoding.
- They should not call `VoicePcm16Sink.start()`, `VoicePcm16Sink.writeFully()`, `VoicePcm16Sink.pauseAndFlush()`, or `VoicePcm16Sink.stopAndRelease()` directly.
- They should call `VoicePcm16SinkLifecycle`.

- [ ] **Step 9: Commit**

Run:

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt
git commit -m "refactor: share PCM sink lifecycle mechanics"
```

## Task 4: Extract Android Playback Track Ownership

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoicePlaybackTracks.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
- Test: existing voice-agent audio and waiting-tone tests

- [ ] **Step 1: Create the Android playback track owner**

Create `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoicePlaybackTracks.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private enum class AndroidPlaybackTrackOwner {
    Assistant,
    LocalCue,
}

internal class AndroidVoicePlaybackTracks(
    private val audioAttributes: () -> AudioAttributes,
    private val onAssistantPlaybackError: (String) -> Unit,
) {
    private val lock = Any()
    private var assistantTrack: AudioTrack? = null
    private var localCueTrack: AudioTrack? = null
    private var released = false

    fun createAssistantSinkOrNull(): VoicePcm16Sink? {
        val track = getOrCreatePlaybackTrack(AndroidPlaybackTrackOwner.Assistant) ?: return null
        return AndroidAudioTrackSink(
            track = track,
            owner = AndroidPlaybackTrackOwner.Assistant,
        )
    }

    fun createLocalCueSinkOrNull(): VoicePcm16Sink? {
        val track = createLocalCuePlaybackTrack() ?: return null
        return AndroidAudioTrackSink(
            track = track,
            owner = AndroidPlaybackTrackOwner.LocalCue,
        )
    }

    fun releaseAssistant() {
        val track = synchronized(lock) {
            assistantTrack.also {
                assistantTrack = null
            }
        }
        track?.stopSafely()
        track?.releaseSafely()
    }

    fun releaseLocalCue() {
        val track = synchronized(lock) {
            localCueTrack.also {
                localCueTrack = null
            }
        }
        track?.stopSafely()
        track?.releaseSafely()
    }

    fun releaseAll() {
        val assistant: AudioTrack?
        val localCue: AudioTrack?
        synchronized(lock) {
            if (released) {
                return
            }
            released = true
            assistant = assistantTrack
            localCue = localCueTrack
            assistantTrack = null
            localCueTrack = null
        }
        assistant?.stopSafely()
        assistant?.releaseSafely()
        if (localCue !== assistant) {
            localCue?.stopSafely()
            localCue?.releaseSafely()
        }
    }

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
                localCueTrack = newTrack
            }
        }
        if (shouldReleaseNewTrack) {
            newTrack.releaseSafely()
            return null
        }
        return currentPlaybackTrack(newTrack, AndroidPlaybackTrackOwner.LocalCue)
    }

    private fun currentPlaybackTrack(track: AudioTrack, owner: AndroidPlaybackTrackOwner): AudioTrack? =
        synchronized(lock) {
            if (!released && playbackTrackForLocked(owner) === track) {
                track
            } else {
                null
            }
        }

    private fun playbackTrackForLocked(owner: AndroidPlaybackTrackOwner): AudioTrack? = when (owner) {
        AndroidPlaybackTrackOwner.Assistant -> assistantTrack
        AndroidPlaybackTrackOwner.LocalCue -> localCueTrack
    }

    private fun setPlaybackTrackForLocked(owner: AndroidPlaybackTrackOwner, track: AudioTrack?) {
        when (owner) {
            AndroidPlaybackTrackOwner.Assistant -> assistantTrack = track
            AndroidPlaybackTrackOwner.LocalCue -> localCueTrack = track
        }
    }

    private fun createAudioTrackOrNull(owner: AndroidPlaybackTrackOwner): AudioTrack? {
        val bufferSize = playbackBufferSizeOrNull() ?: return null
        val format = AudioFormat.Builder()
            .setSampleRate(PLAYBACK_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val track = runCatching {
            AudioTrack(
                audioAttributes(),
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
                onAssistantPlaybackError("AudioTrack creation failed: ${it.message ?: it.javaClass.simpleName}")
            }
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack initialization failed: state=${track.state}")
            if (owner == AndroidPlaybackTrackOwner.LocalCue) {
                Log.w(TAG, "Local cue playback failed: AudioTrack initialization failed")
            } else {
                onAssistantPlaybackError("AudioTrack initialization failed: state=${track.state}")
            }
            track.releaseSafely()
            return null
        }

        return track
    }

    private fun removeTrack(track: AudioTrack) {
        synchronized(lock) {
            if (assistantTrack === track) {
                assistantTrack = null
            }
            if (localCueTrack === track) {
                localCueTrack = null
            }
        }
    }

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
                onAssistantPlaybackError("AudioTrack play failed: ${it.message ?: it.javaClass.simpleName}")
            }
            removeTrack(this)
            releaseSafely()
        }.getOrDefault(false)
    }

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

    private fun AudioTrack.pauseSafely() {
        runCatching { pause() }
    }

    private fun AudioTrack.flushSafely() {
        runCatching { flush() }
    }

    private fun AudioTrack.stopSafely() {
        runCatching { stop() }
    }

    private fun AudioTrack.releaseSafely() {
        runCatching { release() }
    }

    private companion object {
        const val TAG = "AndroidVoicePlaybackTracks"
        const val PLAYBACK_SAMPLE_RATE = 24_000
        const val MIN_PLAYBACK_BUFFER_BYTES = 4_800
        const val MAX_BLOCKING_ZERO_WRITES = 16

        fun playbackBufferSizeOrNull(): Int? {
            val bufferSize = AudioTrack.getMinBufferSize(
                PLAYBACK_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (bufferSize <= 0) {
                Log.w(TAG, "AudioTrack min buffer size failed: $bufferSize")
                return null
            }
            return bufferSize.coerceAtLeast(MIN_PLAYBACK_BUFFER_BYTES)
        }
    }
}
```

- [ ] **Step 2: Wire the track owner into AndroidVoiceAudioEngine**

In `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`, remove:

```kotlin
private enum class AndroidPlaybackTrackOwner {
    Assistant,
    LocalCue,
}
```

Remove these properties:

```kotlin
private var audioTrack: AudioTrack? = null
private var localCueAudioTrack: AudioTrack? = null
```

Add `playbackTracks` before `playbackWriter`:

```kotlin
private val playbackTracks = AndroidVoicePlaybackTracks(
    audioAttributes = ::voiceAudioAttributes,
    onAssistantPlaybackError = ::notifyAudioError,
)
```

Replace writer and cue player sink factories:

```kotlin
private val playbackWriter = VoicePlaybackWriter(
    scope = scope,
    createSink = playbackTracks::createAssistantSinkOrNull,
    onDiagnostic = ::handlePlaybackDiagnostic,
)
private val localCuePlayer = VoiceLocalCuePlayer(
    scope = scope,
    createSink = playbackTracks::createLocalCueSinkOrNull,
    onDiagnostic = ::handleLocalCueDiagnostic,
)
```

- [ ] **Step 3: Remove moved playback track methods from AndroidVoiceAudioEngine**

Delete these methods and inner class from `AndroidVoiceAudioEngine.kt` because they now live in `AndroidVoicePlaybackTracks.kt`:

```kotlin
createAssistantAudioTrackSinkOrNull
createLocalCueAudioTrackSinkOrNull
getOrCreatePlaybackTrack
createLocalCuePlaybackTrack
currentPlaybackTrack
playbackTrackForLocked
setPlaybackTrackForLocked
createAudioTrackOrNull
removeTrack
AudioTrack.playSafely
AndroidAudioTrackSink
AudioTrack.pauseSafely
AudioTrack.flushSafely
AudioTrack.stopSafely
AudioTrack.releaseSafely
playbackBufferSizeOrNull
```

Keep `AudioRecord.stopSafely()` and `AudioRecord.releaseSafely()` in `AndroidVoiceAudioEngine.kt`.

- [ ] **Step 4: Release playback tracks during engine release**

In `AndroidVoiceAudioEngine.release()`, add `playbackTracks.releaseAll()` after releasing the writer and local cue player:

```kotlin
playbackWriter.release()
localCuePlayer.release()
playbackTracks.releaseAll()
clearVoiceCommunicationRoutingBestEffort()
```

- [ ] **Step 5: Clean imports and constants**

In `AndroidVoiceAudioEngine.kt`, remove imports that are only used by moved playback code:

```kotlin
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
```

Keep `AudioFormat` because `captureBufferSize()` still uses it.

Remove companion constants that moved to `AndroidVoicePlaybackTracks`:

```kotlin
const val PLAYBACK_SAMPLE_RATE = 24_000
const val MIN_PLAYBACK_BUFFER_BYTES = 4_800
const val MAX_BLOCKING_ZERO_WRITES = 16
```

- [ ] **Step 6: Run compile and focused tests**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesWaitingToneControllerTest' \
  --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Check Android engine no longer owns playback tracks**

Run:

```bash
rg -n "AndroidPlaybackTrackOwner|audioTrack|localCueAudioTrack|createAssistantAudioTrackSinkOrNull|createLocalCueAudioTrackSinkOrNull|AndroidAudioTrackSink|currentPlaybackTrack|removeTrack|playbackBufferSizeOrNull" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt
```

Expected: no matches.

Run:

```bash
wc -l app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoicePlaybackTracks.kt
```

Expected: `AndroidVoiceAudioEngine.kt` is shorter than it was before this task, and `AndroidVoicePlaybackTracks.kt` contains the moved playback code.

- [ ] **Step 8: Commit**

Run:

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoicePlaybackTracks.kt
git commit -m "refactor: extract Android playback tracks"
```

## Task 5: Final Regression, Naming Audit, and Review Prep

**Files:**
- Verify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioEngine.kt`
- Verify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`
- Verify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`
- Verify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
- Verify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoicePlaybackTracks.kt`
- Verify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycle.kt`
- Verify: `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneController.kt`

- [ ] **Step 1: Run the full focused voice-agent regression suite**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePcm16SinkLifecycleTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesWaitingToneControllerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCoordinatorWaitingToneTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallSessionTest' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesJobManagerTest' \
  --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run formatting and whitespace checks**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 3: Audit local cue token naming**

Run:

```bash
rg -n "playLocalCuePcm16\\([^\\n]*sessionId|invalidateLocalCuePlayback\\([^\\n]*sessionId|rejectedToken|playedLocalCueSessionIds|invalidatedLocalCueSessionIds" \
  app/src/main/java/me/rerere/rikkahub/voiceagent \
  app/src/test/java/me/rerere/rikkahub/voiceagent
```

Expected: no matches.

Run:

```bash
rg -n "sessionId" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio \
  app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneController.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio \
  app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneControllerTest.kt
```

Expected: matches are allowed only for assistant playback session APIs, not local cue APIs.

- [ ] **Step 4: Audit playback ownership boundaries**

Run:

```bash
rg -n "AudioTrack|AndroidAudioTrackSink|AndroidPlaybackTrackOwner|currentPlaybackTrack|playbackBufferSizeOrNull|MAX_BLOCKING_ZERO_WRITES" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt
```

Expected: no matches.

Run:

```bash
rg -n "VoicePcm16SinkLifecycle" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt
```

Expected: matches in both files.

- [ ] **Step 5: Review diff for behavior drift**

Run:

```bash
git diff origin/master...HEAD -- \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio \
  app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneController.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio \
  app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneControllerTest.kt
```

Expected review points:

- assistant playback still reports fatal audio errors through `notifyAudioError`;
- local cue failures still report only local cue diagnostics and waiting-tone errors;
- `VoicePlaybackWriter` still owns assistant stale/suppression policy;
- `VoiceLocalCuePlayer` still owns cue queue capacity, cue invalidation, and retired cue sink behavior;
- `AndroidVoiceAudioEngine` no longer owns raw playback `AudioTrack` fields;
- waiting-tone grace delay, repeat interval, and stop behavior are unchanged.

- [ ] **Step 6: Commit final verification notes if files changed**

If Step 5 finds required code or test fixes, make those fixes, rerun Steps 1-4, then commit:

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent app/src/test/java/me/rerere/rikkahub/voiceagent
git commit -m "chore: verify voice playback cleanup"
```

If Step 5 finds no required fixes, do not create an empty commit.
