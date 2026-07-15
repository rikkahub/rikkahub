# Hermes Announcement Architecture Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the four structural defects in Hermes safe-playback coordination while preserving its verified announcement ordering and physical-drain safety behavior.

**Architecture:** Keep `AnnouncerReducer` as the canonical announcement-safety state machine, derive progress/final dispatch from `PendingAnnouncementJob`, and move interrupted-turn state into the reducer. Keep sink mechanics in `VoicePlaybackWriter`, but commit playback events to a FIFO dispatcher so handlers run outside writer locks; distinguish writer invalidation from physical playback with `WriterGeneration` and `PlaybackEpoch` value types.

**Tech Stack:** Kotlin, Android `AudioTrack`, Kotlin coroutines and channels, JUnit 4, kotlinx-coroutines-test, Gradle Android tests, Bash E2E harness, wireless ADB.

## Global Constraints

- Preserve the user-visible order: latest progress first, then the final result at a separate safe boundary.
- A proactive Hermes send requires an idle Gemini gate, a drained physical playback epoch, and an elapsed input quiet window.
- `Interrupted` never acts as `TurnComplete`; the interrupted response boundary is consumed conservatively.
- Drain failure emits `Drained` only after successful sink retirement; failed retirement remains blocked.
- The blocked watchdog is diagnostic-only and never emits a send.
- Preserve bridge admission, session retirement, cancellation, visible-text fallback, and user barge-in behavior.
- Do not introduce a general-purpose playback actor or speech scheduler.
- Install `app/build/outputs/apk/debug/app-universal-debug.apk`; ABI splits do not produce `app-debug.apk`.
- Do not commit device state, credentials, PCM, hashes, answers, traces, or logcat output.

## File Structure

**Create:**

- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/PlaybackEventDispatcher.kt` — FIFO event commitment and post-lock handler delivery.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackIdentity.kt` — `PlaybackEpoch` and `WriterGeneration` value types.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/PlaybackEventDispatcherTest.kt` — FIFO, reentrancy, and failure containment.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackIdentityTest.kt` — playback identity contract.

**Modify:**

- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackEvent.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncementLifecycle.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncer.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/FakeVoiceAudioEngine.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackEventOwnerTest.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncementLifecycleTest.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncerTest.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesJobManagerTest.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentPlaybackCoordinationTest.kt`

---

### Task 1: Add the ordered playback event dispatcher

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/PlaybackEventDispatcher.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/PlaybackEventDispatcherTest.kt`

**Interfaces:**
- Consumes: existing `VoicePlaybackEvent`.
- Produces: `PlaybackEventDispatcher(onEvent, onFailure)`, `enqueue(event)`, and `drain()`.

- [ ] **Step 1: Write the failing dispatcher tests**

Create `PlaybackEventDispatcherTest.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackEventDispatcherTest {
    @Test
    fun `drain delivers committed events in fifo order`() {
        val delivered = mutableListOf<VoicePlaybackEvent>()
        val dispatcher = PlaybackEventDispatcher(
            onEvent = delivered::add,
            onFailure = { _, failure -> throw AssertionError(failure) },
        )
        dispatcher.enqueue(VoicePlaybackEvent.Active(1L))
        dispatcher.enqueue(VoicePlaybackEvent.DrainStarted(1L))
        dispatcher.enqueue(VoicePlaybackEvent.Drained(1L))

        dispatcher.drain()

        assertEquals(
            listOf(
                VoicePlaybackEvent.Active(1L),
                VoicePlaybackEvent.DrainStarted(1L),
                VoicePlaybackEvent.Drained(1L),
            ),
            delivered,
        )
    }

    @Test
    fun `reentrant drain appends behind the current event`() {
        val delivered = mutableListOf<VoicePlaybackEvent>()
        lateinit var dispatcher: PlaybackEventDispatcher
        dispatcher = PlaybackEventDispatcher(
            onEvent = { event ->
                delivered += event
                if (event == VoicePlaybackEvent.Active(1L)) {
                    dispatcher.enqueue(VoicePlaybackEvent.Drained(1L))
                    dispatcher.drain()
                }
            },
            onFailure = { _, failure -> throw AssertionError(failure) },
        )
        dispatcher.enqueue(VoicePlaybackEvent.Active(1L))

        dispatcher.drain()

        assertEquals(
            listOf(VoicePlaybackEvent.Active(1L), VoicePlaybackEvent.Drained(1L)),
            delivered,
        )
    }

    @Test
    fun `throwing handler is diagnosed and later events continue`() {
        val delivered = mutableListOf<VoicePlaybackEvent>()
        val failures = mutableListOf<Pair<VoicePlaybackEvent, String>>()
        val dispatcher = PlaybackEventDispatcher(
            onEvent = { event ->
                if (event is VoicePlaybackEvent.Active) error("active failed")
                delivered += event
            },
            onFailure = { event, failure -> failures += event to failure.message.orEmpty() },
        )
        dispatcher.enqueue(VoicePlaybackEvent.Active(1L))
        dispatcher.enqueue(VoicePlaybackEvent.Drained(1L))

        dispatcher.drain()

        assertEquals(
            listOf(VoicePlaybackEvent.Active(1L) to "active failed"),
            failures,
        )
        assertEquals(listOf(VoicePlaybackEvent.Drained(1L)), delivered)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.PlaybackEventDispatcherTest'
```

Expected: compilation fails because `PlaybackEventDispatcher` does not exist.

- [ ] **Step 3: Implement the dispatcher**

Create `PlaybackEventDispatcher.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

internal class PlaybackEventDispatcher(
    private val onEvent: (VoicePlaybackEvent) -> Unit,
    private val onFailure: (VoicePlaybackEvent, Throwable) -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayDeque<VoicePlaybackEvent>()
    private var draining = false

    fun enqueue(event: VoicePlaybackEvent) {
        synchronized(lock) { pending.addLast(event) }
    }

    fun drain() {
        val ownsDrain = synchronized(lock) {
            if (draining) {
                false
            } else {
                draining = true
                true
            }
        }
        if (!ownsDrain) return

        while (true) {
            val event = synchronized(lock) {
                pending.removeFirstOrNull().also { next ->
                    if (next == null) draining = false
                }
            } ?: return
            try {
                onEvent(event)
            } catch (failure: Throwable) {
                runCatching { onFailure(event, failure) }
            }
        }
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run the command from Step 2.

Expected: all three dispatcher tests pass.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/PlaybackEventDispatcher.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/PlaybackEventDispatcherTest.kt
git commit -m "refactor(voiceagent): serialize playback event delivery"
```

---

### Task 2: Publish writer events outside its state lock

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt:8-52,66-120,204-228,323-347`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt:31-42,771-814`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt`

**Interfaces:**
- Consumes: `PlaybackEventDispatcher` from Task 1.
- Produces: post-lock ordered delivery and `VoicePlaybackDiagnostic.PlaybackEventHandlerFailed`.

- [ ] **Step 1: Add failing writer tests**

Add imports:

```kotlin
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
```

Add before `testScope()`:

```kotlin
@Test
fun `active callback runs after the writer state lock is released`() {
    val scope = testScope()
    val callbackObservedUnlocked = AtomicBoolean(false)
    lateinit var writer: VoicePlaybackWriter
    writer = VoicePlaybackWriter(
        scope = scope,
        createSink = { FakeVoicePcm16Sink(expectedWrites = 1) },
        onPlaybackEvent = { event ->
            if (event is VoicePlaybackEvent.Active) {
                val completed = CountDownLatch(1)
                thread(start = true, name = "playback-reentrant-boundary") {
                    writer.markTurnComplete(sessionId = 100L)
                    completed.countDown()
                }
                callbackObservedUnlocked.set(completed.await(500, TimeUnit.MILLISECONDS))
            }
        },
    )
    writer.activateSession(100L)

    assertTrue(writer.playBase64("AQID", sessionId = 100L))

    assertTrue(callbackObservedUnlocked.get())
    writer.release()
    scope.cancel()
}

@Test
fun `throwing active callback does not kill later drain delivery`() {
    val scope = testScope()
    val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
    val delivered = CopyOnWriteArrayList<VoicePlaybackEvent>()
    var throwActive = true
    val writer = VoicePlaybackWriter(
        scope = scope,
        createSink = { FakeVoicePcm16Sink(expectedWrites = 1) },
        onDiagnostic = diagnostics::add,
        onPlaybackEvent = { event ->
            if (event is VoicePlaybackEvent.Active && throwActive) {
                throwActive = false
                error("handler failed")
            }
            delivered += event
        },
    )
    writer.activateSession(100L)
    assertTrue(writer.playBase64("AQID", sessionId = 100L))
    assertTrue(writer.markTurnComplete(sessionId = 100L))

    assertTrue(waitUntil { delivered.contains(VoicePlaybackEvent.Drained(1L)) })
    assertTrue(diagnostics.any {
        it is VoicePlaybackDiagnostic.PlaybackEventHandlerFailed && it.message == "handler failed"
    })
    writer.release()
    scope.cancel()
}
```

- [ ] **Step 2: Run the focused tests and verify they fail**

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest.active callback runs after the writer state lock is released' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest.throwing active callback does not kill later drain delivery'
```

Expected: the first test cannot complete the cross-thread boundary while the old lock is held; the second exposes the uncontained callback.

- [ ] **Step 3: Add dispatcher-backed publication**

Add the diagnostic:

```kotlin
data class PlaybackEventHandlerFailed(
    val event: VoicePlaybackEvent,
    val message: String,
) : VoicePlaybackDiagnostic
```

Add the writer field:

```kotlin
private val playbackEvents = PlaybackEventDispatcher(
    onEvent = onPlaybackEvent,
    onFailure = { event, failure ->
        onDiagnostic(
            VoicePlaybackDiagnostic.PlaybackEventHandlerFailed(
                event = event,
                message = failure.message ?: failure.javaClass.simpleName,
            )
        )
    },
)
```

In `playBase64`, enqueue `Active` while locked and drain immediately after the synchronized expression:

```kotlin
if (existingEpoch == null) {
    playbackEvents.enqueue(VoicePlaybackEvent.Active(playbackEpoch))
}
```

```kotlin
playbackEvents.drain()
```

Replace `drainCommand`:

```kotlin
private fun drainCommand(command: PlaybackCommand.Drain) {
    val sink = synchronized(lock) {
        if (released || generation != command.writerGeneration) return
        playbackEvents.enqueue(VoicePlaybackEvent.DrainStarted(command.playbackEpoch))
        activeSink
    }
    playbackEvents.drain()
    val result = if (sink == null) {
        VoicePcm16Sink.DrainResult.Drained
    } else {
        VoicePcm16SinkLifecycle.awaitDrained(sink)
    }
    when (result) {
        VoicePcm16Sink.DrainResult.Drained -> {
            synchronized(lock) {
                val completed = epochWriterGenerations.remove(command.playbackEpoch) ==
                    command.writerGeneration
                if (completed) {
                    playbackEvents.enqueue(VoicePlaybackEvent.Drained(command.playbackEpoch))
                }
            }
            playbackEvents.drain()
        }
        VoicePcm16Sink.DrainResult.Interrupted -> Unit
        is VoicePcm16Sink.DrainResult.Failed -> {
            onDiagnostic(VoicePlaybackDiagnostic.SinkDrainFailed(result.message))
            retireWriterGenerationAfterFlush(command.writerGeneration, sink)
        }
    }
}
```

In `finishRetirement`, replace the successful tail with:

```kotlin
VoicePcm16SinkLifecycle.stopAndReleaseSafely(sink)

synchronized(lock) {
    retirement.playbackEpochs.forEach { epoch ->
        if (epochWriterGenerations.remove(epoch) == retirement.writerGeneration) {
            playbackEvents.enqueue(VoicePlaybackEvent.Drained(epoch))
        }
    }
    retirementInProgress = false
}
playbackEvents.drain()
```

- [ ] **Step 4: Make Android diagnostic handling exhaustive**

Add `PlaybackEventHandlerFailed` to the `audioErrorMessageOrNull()` null cases and add:

```kotlin
is VoicePlaybackDiagnostic.PlaybackEventHandlerFailed -> {
    Log.w(
        TAG,
        "Voice playback event handler failed: event=${diagnostic.event} " +
            "message=${diagnostic.message}",
    )
}
```

- [ ] **Step 5: Run dispatcher and writer suites**

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.PlaybackEventDispatcherTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest'
```

Expected: both classes pass, including existing drain and retirement failure cases.

- [ ] **Step 6: Commit**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/PlaybackEventDispatcher.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriterTest.kt
git commit -m "fix(voiceagent): publish playback events outside writer lock"
```

---

### Task 3: Separate writer generations from playback epochs

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackIdentity.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackIdentityTest.kt`
- Modify: all production and test files listed under File Structure that construct playback events or call announcer playback methods.

**Interfaces:**
- Consumes: post-lock delivery from Task 2.
- Produces: public `PlaybackEpoch`, internal `WriterGeneration`, and typed playback events/gates.

- [ ] **Step 1: Write the failing identity test**

Create `VoicePlaybackIdentityTest.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackIdentityTest {
    @Test
    fun `voice playback events expose a playback epoch`() {
        val epoch = PlaybackEpoch(7L)
        val event: VoicePlaybackEvent = VoicePlaybackEvent.Active(epoch)
        assertEquals(epoch, event.playbackEpoch)
        assertTrue(PlaybackEpoch(7L) < PlaybackEpoch(8L))
    }

    @Test
    fun `writer generation remains a separate comparable type`() {
        assertTrue(WriterGeneration(3L) < WriterGeneration(4L))
        assertEquals(4L, WriterGeneration(4L).value)
    }
}
```

- [ ] **Step 2: Run it and verify compilation fails**

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackIdentityTest'
```

Expected: the new types and property are unresolved.

- [ ] **Step 3: Add the identity types and typed event**

Create `VoicePlaybackIdentity.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

@JvmInline
value class PlaybackEpoch(val value: Long) : Comparable<PlaybackEpoch> {
    override fun compareTo(other: PlaybackEpoch): Int = value.compareTo(other.value)
}

@JvmInline
internal value class WriterGeneration(val value: Long) : Comparable<WriterGeneration> {
    override fun compareTo(other: WriterGeneration): Int = value.compareTo(other.value)
    fun next(): WriterGeneration = WriterGeneration(value + 1L)
}
```

Replace `VoicePlaybackEvent.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent.audio

sealed interface VoicePlaybackEvent {
    val playbackEpoch: PlaybackEpoch

    data class Active(override val playbackEpoch: PlaybackEpoch) : VoicePlaybackEvent
    data class DrainStarted(override val playbackEpoch: PlaybackEpoch) : VoicePlaybackEvent
    data class Drained(override val playbackEpoch: PlaybackEpoch) : VoicePlaybackEvent
}
```

- [ ] **Step 4: Type writer state and diagnostics**

Use:

```kotlin
private var writerGeneration = WriterGeneration(0L)
private var nextPlaybackEpoch = PlaybackEpoch(0L)
private var acceptingPlaybackEpoch: PlaybackEpoch? = null
private val epochWriterGenerations = mutableMapOf<PlaybackEpoch, WriterGeneration>()
```

Allocate with:

```kotlin
val playbackEpoch = existingEpoch ?: PlaybackEpoch(nextPlaybackEpoch.value + 1L).also { epoch ->
    nextPlaybackEpoch = epoch
    acceptingPlaybackEpoch = epoch
    epochWriterGenerations[epoch] = writerGeneration
}
```

Use these declarations:

```kotlin
data class ChunkQueued(val bytes: Int, val writerGeneration: WriterGeneration) : VoicePlaybackDiagnostic
data class ChunkWritten(val bytes: Int, val writerGeneration: WriterGeneration) : VoicePlaybackDiagnostic
data class StaleChunkRejected(
    val writerGeneration: WriterGeneration,
    val activeWriterGeneration: WriterGeneration,
    val rejectedSessionId: Long? = null,
    val activeSessionId: Long? = null,
) : VoicePlaybackDiagnostic
data class PlaybackSuppressed(val writerGeneration: WriterGeneration) : VoicePlaybackDiagnostic
```

```kotlin
private sealed interface PlaybackCommand {
    data class Play(
        val pcm16: ByteArray,
        val writerGeneration: WriterGeneration,
    ) : PlaybackCommand

    data class Drain(
        val writerGeneration: WriterGeneration,
        val playbackEpoch: PlaybackEpoch,
    ) : PlaybackCommand
}

private data class Retirement(
    val writerGeneration: WriterGeneration,
    val sink: VoicePcm16Sink?,
    val playbackEpochs: List<PlaybackEpoch>,
)
```

Rename current/stale helper parameters to `commandWriterGeneration: WriterGeneration`. Advance retirement with `writerGeneration = writerGeneration.next()`.

- [ ] **Step 5: Type the announcer boundary**

Import `PlaybackEpoch` and use:

```kotlin
data class PlaybackGate(
    val playbackEpoch: PlaybackEpoch = PlaybackEpoch(0L),
    val drained: Boolean = true,
)

data class PlaybackActive(val playbackEpoch: PlaybackEpoch, val nowMs: Long) : AnnouncerEvent
data class PlaybackDrainStarted(val playbackEpoch: PlaybackEpoch, val nowMs: Long) : AnnouncerEvent
data class PlaybackDrained(val playbackEpoch: PlaybackEpoch, val nowMs: Long) : AnnouncerEvent
```

Change announcer methods to accept `PlaybackEpoch`, coordinator routing to use `event.playbackEpoch`, and diagnostics to print `playbackEpoch=${playbackEpoch.value}`.

- [ ] **Step 6: Update Android logs, fake state, and test constructors**

In `FakeVoiceAudioEngine`, use:

```kotlin
private var nextPlaybackEpoch = PlaybackEpoch(0L)
private var acceptingPlaybackEpoch: PlaybackEpoch? = null
private val pendingDrainEpochs = ArrayDeque<PlaybackEpoch>()
```

Allocate with:

```kotlin
val playbackEpoch = PlaybackEpoch(nextPlaybackEpoch.value + 1L)
nextPlaybackEpoch = playbackEpoch
acceptingPlaybackEpoch = playbackEpoch
playbackEventHandler?.invoke(VoicePlaybackEvent.Active(playbackEpoch))
```

Across tests use:

```kotlin
VoicePlaybackEvent.Active(PlaybackEpoch(1L))
VoicePlaybackEvent.DrainStarted(PlaybackEpoch(1L))
VoicePlaybackEvent.Drained(PlaybackEpoch(1L))
AnnouncerEvent.PlaybackActive(PlaybackEpoch(1L), nowMs = 10L)
AnnouncerEvent.PlaybackDrained(PlaybackEpoch(1L), nowMs = 20L)
announcer.onPlaybackActive(PlaybackEpoch(1L))
announcer.onPlaybackDrained(PlaybackEpoch(1L))
```

Update Android writer diagnostics to print `.writerGeneration.value`; remove generic playback `generation` labels.

- [ ] **Step 7: Run the affected suites**

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.*' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesAnnouncementLifecycleTest' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesAnnouncerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesJobManagerTest'
```

Expected: compilation succeeds and all selected tests pass.

- [ ] **Step 8: Prove ambiguous names are gone**

```bash
if rg -n 'VoicePlaybackEvent.*generation|playbackGeneration|PlaybackGate\(generation' \
  app/src/main app/src/test; then
  exit 1
fi
```

Expected: no matches.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent \
  app/src/test/java/me/rerere/rikkahub/voiceagent
git commit -m "refactor(voiceagent): type playback identities"
```

---

### Task 4: Move interrupted-turn semantics into the reducer

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncementLifecycle.kt:46-53,83-101,129-211,319-342`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncementLifecycleTest.kt`

**Interfaces:**
- Consumes: typed `PlaybackEpoch` from Task 3.
- Produces: `GeminiTurnGate.InterruptedAwaitingBoundary` and `AnnouncerEvent.GeminiTurnInterrupted(nowMs)`.

- [ ] **Step 1: Add failing reducer transition tests**

Add:

```kotlin
@Test
fun `interruption closes an idle gate until two completion boundaries`() {
    var state = reducer.reduce(
        attached,
        AnnouncerEvent.GeminiTurnInterrupted(nowMs = 10L),
    ).state
    assertEquals(GeminiTurnGate.InterruptedAwaitingBoundary, state.geminiTurn)

    state = reducer.reduce(state, AnnouncerEvent.GeminiTurnComplete(nowMs = 20L)).state
    assertEquals(GeminiTurnGate.Active, state.geminiTurn)

    state = reducer.reduce(state, AnnouncerEvent.GeminiTurnComplete(nowMs = 30L)).state
    assertEquals(GeminiTurnGate.Idle, state.geminiTurn)
}

@Test
fun `activity and repeated interruptions do not weaken interrupted state`() {
    var state = attached.copy(geminiTurn = GeminiTurnGate.Active)
    state = reducer.reduce(state, AnnouncerEvent.GeminiTurnInterrupted(10L)).state
    state = reducer.reduce(state, AnnouncerEvent.GeminiTurnActive(20L)).state
    state = reducer.reduce(state, AnnouncerEvent.GeminiTurnInterrupted(30L)).state

    assertEquals(GeminiTurnGate.InterruptedAwaitingBoundary, state.geminiTurn)
    val firstCompletion = reducer.reduce(state, AnnouncerEvent.GeminiTurnComplete(40L))
    assertEquals(GeminiTurnGate.Active, firstCompletion.state.geminiTurn)
}

@Test
fun `interruption wins a send reservation race`() {
    var state = reducer.reduce(
        attached,
        AnnouncerEvent.IntentEnqueued(completion, nowMs = 10L),
    ).state
    state = reducer.reduce(state, AnnouncerEvent.GeminiTurnInterrupted(20L)).state

    val returned = reducer.reduce(
        state,
        AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Sent, nowMs = 30L),
    )

    assertEquals(GeminiTurnGate.InterruptedAwaitingBoundary, returned.state.geminiTurn)
    assertEquals(null, returned.state.inFlight)
}

@Test
fun `session retirement clears interrupted ownership`() {
    val interrupted = attached.copy(geminiTurn = GeminiTurnGate.InterruptedAwaitingBoundary)

    val retired = reducer.reduce(interrupted, AnnouncerEvent.GeminiSessionRetired(20L))

    assertEquals(GeminiTurnGate.Idle, retired.state.geminiTurn)
    assertTrue(retired.state.bridgeRetired)
}
```

- [ ] **Step 2: Run reducer tests and verify compilation fails**

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesAnnouncementLifecycleTest'
```

Expected: the new state and event are unresolved.

- [ ] **Step 3: Add the state, event, and reducer branch**

Extend the state:

```kotlin
sealed interface GeminiTurnGate {
    data object Idle : GeminiTurnGate
    data class SendReserved(
        val activityObserved: Boolean = false,
        val completed: Boolean = false,
    ) : GeminiTurnGate
    data object Active : GeminiTurnGate
    data object InterruptedAwaitingBoundary : GeminiTurnGate
}
```

Add:

```kotlin
data class GeminiTurnInterrupted(val nowMs: Long) : AnnouncerEvent
```

Add the reducer branch:

```kotlin
is AnnouncerEvent.GeminiTurnInterrupted -> when {
    state.closed -> noChange(state)
    else -> settle(
        state.copy(geminiTurn = GeminiTurnGate.InterruptedAwaitingBoundary),
        event.nowMs,
    )
}
```

- [ ] **Step 4: Make turn transitions exhaustive**

Use:

```kotlin
private fun GeminiTurnGate.onActivity(): GeminiTurnGate = when (this) {
    GeminiTurnGate.Idle -> GeminiTurnGate.Active
    is GeminiTurnGate.SendReserved -> copy(activityObserved = true)
    GeminiTurnGate.Active -> this
    GeminiTurnGate.InterruptedAwaitingBoundary -> this
}

private fun GeminiTurnGate.onComplete(): GeminiTurnGate = when (this) {
    GeminiTurnGate.Idle -> this
    is GeminiTurnGate.SendReserved -> copy(completed = true)
    GeminiTurnGate.Active -> GeminiTurnGate.Idle
    GeminiTurnGate.InterruptedAwaitingBoundary -> GeminiTurnGate.Active
}

private fun GeminiTurnGate.onSendReturned(
    outcome: AnnouncementSendOutcome,
): GeminiTurnGate = when (this) {
    GeminiTurnGate.Idle -> this
    GeminiTurnGate.Active -> this
    GeminiTurnGate.InterruptedAwaitingBoundary -> this
    is GeminiTurnGate.SendReserved -> when (outcome) {
        AnnouncementSendOutcome.Sent -> if (completed) GeminiTurnGate.Idle else GeminiTurnGate.Active
        AnnouncementSendOutcome.Failed,
        AnnouncementSendOutcome.Skipped,
        AnnouncementSendOutcome.AttachmentInvalidated,
            -> if (activityObserved && !completed) GeminiTurnGate.Active else GeminiTurnGate.Idle
    }
}
```

- [ ] **Step 5: Run reducer tests and verify they pass**

Run the command from Step 2.

Expected: all lifecycle tests pass.

- [ ] **Step 6: Commit**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncementLifecycle.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncementLifecycleTest.kt
git commit -m "refactor(voiceagent): model interrupted Gemini boundaries"
```

---

### Task 5: Route interruption through the announcer

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncer.kt:279-320`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt:138-142,228-246,564-583,647-734`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentPlaybackCoordinationTest.kt:442-564`

**Interfaces:**
- Consumes: `GeminiTurnInterrupted` from Task 4.
- Produces: interruption prepare/commit methods and one coordinator ownership predicate.

- [ ] **Step 1: Make the old coordinator diagnostic fail the test**

Inject `val diagnostics = VoiceDiagnostics()` into `Gemini interruption does not release Hermes before the following turn completes`, pass it to the coordinator, and add:

```kotlin
assertTrue(diagnostics.events.value.none {
    it.name == "gemini_interrupted_turn_complete_consumed"
})
```

Replace the session-retirement test's replacement-specific diagnostic assertion with the same whole-list assertion.

- [ ] **Step 2: Run the coordination suite and verify it fails**

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentPlaybackCoordinationTest'
```

Expected: the unscoped interruption test observes the legacy consumed-boundary diagnostic.

- [ ] **Step 3: Add announcer prepare/commit methods**

Add beside the active and complete methods:

```kotlin
fun onGeminiTurnInterrupted() {
    commitGeminiTurnInterrupted(prepareGeminiTurnInterrupted())
}

internal fun prepareGeminiTurnInterrupted(): AnnouncerEvent.GeminiTurnInterrupted =
    AnnouncerEvent.GeminiTurnInterrupted(nowMs())

internal fun commitGeminiTurnInterrupted(event: AnnouncerEvent.GeminiTurnInterrupted) {
    events.trySend(event)
}
```

- [ ] **Step 4: Centralize coordinator ownership**

Add near `isActiveSession`:

```kotlin
private fun ownsGeminiEventLocked(sessionId: Long?): Boolean {
    check(Thread.holdsLock(toolJobsLock))
    if (closed || closing) return false
    return if (sessionId == null) acceptsUnscopedGeminiEvents else activeSessionId == sessionId
}
```

Use this predicate inside `markGeminiTurnActive`, `handleInterrupted`, and `handleTurnComplete`.

- [ ] **Step 5: Delete coordinator interruption state and route the event**

Delete `interruptedResponseSessionId` and both reset assignments. Replace `handleInterrupted`:

```kotlin
private fun handleInterrupted(event: GeminiLiveEvent.Interrupted, sessionId: Long?) {
    val interrupted = hermesJobManager.announcer.prepareGeminiTurnInterrupted()
    val accepted = synchronized(toolJobsLock) {
        ownsGeminiEventLocked(sessionId).also { ownsTurn ->
            if (ownsTurn) hermesJobManager.announcer.commitGeminiTurnInterrupted(interrupted)
        }
    }
    if (!accepted) {
        diagnostics.record("stale_gemini_event", event.javaClass.simpleName)
        return
    }
    diagnostics.record("gemini_interrupted", event.reason)
    suppressionController.markInterruptedByGemini()
    suppressPlayback()
}
```

Replace `handleTurnComplete`:

```kotlin
private fun handleTurnComplete(sessionId: Long?) {
    val playbackAccepted = audio.markPlaybackTurnComplete(sessionId)
    val turnComplete = if (playbackAccepted) {
        hermesJobManager.announcer.prepareGeminiTurnComplete()
    } else {
        null
    }
    val turnAccepted = synchronized(toolJobsLock) {
        val ownsTurn = playbackAccepted && ownsGeminiEventLocked(sessionId)
        if (ownsTurn) {
            hermesJobManager.announcer.commitGeminiTurnComplete(requireNotNull(turnComplete))
        }
        ownsTurn
    }
    if (!turnAccepted) {
        diagnostics.record(
            name = "stale_gemini_turn_complete",
            detail = "sessionId=${sessionId ?: "none"}, playbackAccepted=$playbackAccepted",
        )
    }
}
```

- [ ] **Step 6: Run coordinator, reducer, and runtime suites**

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesAnnouncementLifecycleTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentPlaybackCoordinationTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest'
```

Expected: all selected tests pass.

- [ ] **Step 7: Prove legacy state is gone**

```bash
if rg -n 'interruptedResponseSessionId|gemini_interrupted_turn_complete_consumed' \
  app/src/main app/src/test; then
  exit 1
fi
```

Expected: no matches.

- [ ] **Step 8: Commit**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncer.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentPlaybackCoordinationTest.kt
git commit -m "refactor(voiceagent): route interruptions through announcer"
```

---

### Task 6: Derive progress eligibility from grouped queue state

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncementLifecycle.kt:10-35,60-113,293-317,344-421,462-545`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncer.kt:377-427,521-565`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncementLifecycleTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncerTest.kt`

**Interfaces:**
- Consumes: completed reducer and announcer shell from Tasks 4-5.
- Produces: `FinalAnnouncementIntent`, `AnnouncementDispatch`, `nextDispatch()`, typed `inFlight`, and typed send effects.

- [ ] **Step 1: Add failing dispatch assertions**

Keep behavior-oriented assertions stable and add a dispatch-specific helper:

```kotlin
private fun AnnouncerTransition.dispatches(): List<AnnouncementDispatch> =
    effects.filterIsInstance<AnnouncerEffect.Send>().map { it.dispatch }

private fun AnnouncerTransition.sends(): List<AnnouncementIntent> =
    dispatches().map { it.intent }
```

For the idle completion test assert:

```kotlin
val dispatch = AnnouncementDispatch.Final(completion)
assertEquals(listOf(dispatch), transition.dispatches())
assertEquals(dispatch, transition.state.inFlight)
```

For the queued progress/final test assert:

```kotlin
assertEquals(progress, job.progress)
assertEquals(completion, job.final)
assertEquals(AnnouncementDispatch.ProgressBeforeFinal(progress), job.nextDispatch())
```

Add:

```kotlin
@Test
fun `unpaired progress derives progress-only dispatch`() {
    val progress = AnnouncementIntent.StillWorking("c-progress", "j-progress")
    val job = PendingAnnouncementJob(
        key = AnnouncementJobKey("job:j-progress"),
        progress = progress,
    )
    assertEquals(AnnouncementDispatch.ProgressOnly(progress), job.nextDispatch())
}
```

- [ ] **Step 2: Run lifecycle tests and verify compilation fails**

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesAnnouncementLifecycleTest'
```

Expected: dispatch types and `nextDispatch()` are unresolved.

- [ ] **Step 3: Make final intents and dispatches explicit**

Use this hierarchy while preserving current nested intent names:

```kotlin
sealed interface AnnouncementIntent {
    data class Completion(
        val callId: String,
        val jobId: String?,
    ) : FinalAnnouncementIntent

    data class Terminal(
        val callId: String,
        val jobId: String?,
    ) : FinalAnnouncementIntent

    data class StillWorking(
        val callId: String,
        val jobId: String,
    ) : AnnouncementIntent
}

sealed interface FinalAnnouncementIntent : AnnouncementIntent

sealed interface AnnouncementDispatch {
    val intent: AnnouncementIntent

    sealed interface Progress : AnnouncementDispatch {
        val progress: AnnouncementIntent.StillWorking
        override val intent: AnnouncementIntent.StillWorking
            get() = progress
    }

    data class ProgressOnly(
        override val progress: AnnouncementIntent.StillWorking,
    ) : Progress

    data class ProgressBeforeFinal(
        override val progress: AnnouncementIntent.StillWorking,
    ) : Progress

    data class Final(val final: FinalAnnouncementIntent) : AnnouncementDispatch {
        override val intent: FinalAnnouncementIntent
            get() = final
    }
}
```

- [ ] **Step 4: Derive dispatch from the grouped job**

Change `PendingAnnouncementJob.final` to `FinalAnnouncementIntent?` and replace `head()`:

```kotlin
fun nextDispatch(): AnnouncementDispatch? = when {
    progress != null && final != null -> AnnouncementDispatch.ProgressBeforeFinal(progress)
    progress != null -> AnnouncementDispatch.ProgressOnly(progress)
    final != null -> AnnouncementDispatch.Final(final)
    else -> null
}
```

Change `AnnouncerState.inFlight` to `AnnouncementDispatch?` and `AnnouncerEffect.Send` to:

```kotlin
data class Send(
    val dispatch: AnnouncementDispatch,
    val sessionId: Long,
) : AnnouncerEffect
```

In `enqueueIntent`, replace the grouped completion/terminal branches with:

```kotlin
is FinalAnnouncementIntent -> enqueueFinal(state, key, intent)
```

Make `enqueueFinal` accept `FinalAnnouncementIntent`; assign `existing.copy(final = intent)` without copying progress.

- [ ] **Step 5: Reserve typed dispatches in the reducer**

In watchdog and `settle`, select:

```kotlin
val dispatch = requireNotNull(state.pendingJobs.first().nextDispatch())
```

Use `dispatch.intent.label()` for diagnostics and `AnnouncerEffect.Send(dispatch, sessionId)`. Remove the selected slot with:

```kotlin
val firstJob = state.pendingJobs.first()
val remainder = when (dispatch) {
    is AnnouncementDispatch.Progress -> firstJob.copy(progress = null)
    is AnnouncementDispatch.Final -> firstJob.copy(final = null)
}
val pendingJobs = if (remainder.nextDispatch() == null) {
    state.pendingJobs.drop(1)
} else {
    listOf(remainder) + state.pendingJobs.drop(1)
}
```

Store `inFlight = dispatch`. In `reduceSendReturned`, pass `dispatch.intent` to fallback logic.

- [ ] **Step 6: Execute typed dispatches in the announcer**

Change send-effect execution to `executeSend(effect.dispatch)` and use:

```kotlin
private suspend fun executeSend(dispatch: AnnouncementDispatch) {
    val current = currentAttachment()
    if (current == null) {
        events.trySend(
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.AttachmentInvalidated, nowMs())
        )
        return
    }
    val outcome = when (dispatch) {
        is AnnouncementDispatch.Progress -> sendStillWorking(dispatch, current)
        is AnnouncementDispatch.Final -> when (val final = dispatch.final) {
            is AnnouncementIntent.Completion -> sendCompletion(final, current)
            is AnnouncementIntent.Terminal -> sendTerminal(final, current)
        }
    }
    events.trySend(AnnouncerEvent.SendReturned(outcome, nowMs()))
}
```

Change the progress sender head:

```kotlin
private suspend fun sendStillWorking(
    dispatch: AnnouncementDispatch.Progress,
    current: HermesBridgeAttachment,
): AnnouncementSendOutcome {
    val intent = dispatch.progress
    val record = queueStore.latestRecord(callId = intent.callId, jobId = intent.jobId)
    if (
        record == null ||
        record.stillWorkingAnnounced ||
        (record.status.isTerminal && dispatch is AnnouncementDispatch.ProgressOnly)
    ) {
        return AnnouncementSendOutcome.Skipped
    }
```

Keep the current bridge admission, send, marking, and telemetry body after this guard.

- [ ] **Step 7: Update existing lifecycle assertions**

Keep existing behavior-order assertions on `sends()`. Use `dispatches()` only for dispatch-shape assertions. Remove every `copy(allowTerminalRecord = true)` expectation.

- [ ] **Step 8: Run reducer, announcer, and manager suites**

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesAnnouncementLifecycleTest' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesAnnouncerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesJobManagerTest'
```

Expected: all selected tests pass, including canceled unpaired progress and progress-before-final.

- [ ] **Step 9: Prove the intent flag is gone**

```bash
if rg -n 'allowTerminalRecord' app/src/main app/src/test; then
  exit 1
fi
```

Expected: no matches.

- [ ] **Step 10: Commit**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncementLifecycle.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncer.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncementLifecycleTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/hermes/HermesAnnouncerTest.kt
git commit -m "refactor(voiceagent): derive progress dispatch from queue"
```

---

### Task 7: Run full regression and wireless-device acceptance

**Files:**
- Verify: all files changed in Tasks 1-6.
- Read: `docs/voice-agent-hermes-gbrain-live-e2e.md`
- Run: `scripts/test-voice-agent-hermes-gbrain-e2e.sh`
- Run: `scripts/voice-agent-hermes-gbrain-e2e.sh`

**Interfaces:**
- Consumes: completed implementation and an already configured wireless Android device.
- Produces: unit, harness, build, installation, and device trace evidence.

- [ ] **Step 1: Run structural searches**

```bash
if rg -n 'interruptedResponseSessionId|gemini_interrupted_turn_complete_consumed|allowTerminalRecord' \
  app/src/main app/src/test; then
  exit 1
fi
if rg -n 'VoicePlaybackEvent.*generation|playbackGeneration|PlaybackGate\(generation' \
  app/src/main app/src/test; then
  exit 1
fi
```

Expected: no matches.

- [ ] **Step 2: Run all Android unit tests freshly**

```bash
ANDROID_HOME=/home/muly/Android/Sdk \
ANDROID_SDK_ROOT=/home/muly/Android/Sdk \
./gradlew :app:testDebugUnitTest --rerun-tasks
```

Expected: `BUILD SUCCESSFUL` and zero failures/errors in the JUnit XML results.

- [ ] **Step 3: Run the shell harness**

```bash
bash -n scripts/voice-agent-hermes-gbrain-e2e.sh \
  scripts/test-voice-agent-hermes-gbrain-e2e.sh
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: syntax succeeds and the fake-ADB harness reports success.

- [ ] **Step 4: Build the universal APK**

```bash
ANDROID_HOME=/home/muly/Android/Sdk \
ANDROID_SDK_ROOT=/home/muly/Android/Sdk \
./gradlew :app:assembleDebug
test -f app/build/outputs/apk/debug/app-universal-debug.apk
```

Expected: build succeeds and the APK exists.

- [ ] **Step 5: Connect and install over wireless ADB**

```bash
: "${VOICE_AGENT_E2E_SERIAL:?Set VOICE_AGENT_E2E_SERIAL to the current host:port}"
adb connect "$VOICE_AGENT_E2E_SERIAL"
scripts/adb-device-ready.sh "$VOICE_AGENT_E2E_SERIAL"
adb -s "$VOICE_AGENT_E2E_SERIAL" install -r \
  app/build/outputs/apk/debug/app-universal-debug.apk
```

Expected: readiness succeeds and installation ends with `Success`.

- [ ] **Step 6: Run the credentialed live E2E**

```bash
: "${VOICE_AGENT_E2E_CONVERSATION_ID:?Set an existing configured conversation id}"
VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
VOICE_AGENT_E2E_SERIAL="$VOICE_AGENT_E2E_SERIAL" \
VOICE_AGENT_E2E_CONVERSATION_ID="$VOICE_AGENT_E2E_CONVERSATION_ID" \
scripts/voice-agent-hermes-gbrain-e2e.sh
```

Expected: success, a nonempty `build/voice-agent-e2e/trace-id.txt`, and no Cloudflare, playback, or fatal exception markers.

- [ ] **Step 7: Verify trace ordering**

```bash
TRACE_ID="$(cat build/voice-agent-e2e/trace-id.txt)"
test -n "$TRACE_ID"
rg -n \
  'gemini_turn_complete|voice_playback_active|voice_playback_drain_started|voice_playback_drained|hermes_announcement_released_safe_boundary' \
  build/voice-agent-e2e/logcat.txt
```

Expected: each Hermes release follows the relevant TurnComplete and matching drained epoch; no release occurs between playback active and drained.

- [ ] **Step 8: Inspect final repository state**

```bash
git status --short --branch
git log --oneline -7
```

Expected: the tracked worktree is clean and Tasks 1-6 are separate commits after the design commit.
