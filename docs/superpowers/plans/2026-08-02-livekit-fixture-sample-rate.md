# LiveKit Fixture Sample-Rate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the canonical 16 kHz fixture in real time and encode it in LiveKit's actual normalized-float callback format, so a valid physical-device call can form a final user transcript.

**Architecture:** `LiveKitAutomationPcmSource` remains the synchronized owner of queued fixture bytes and gains a small streaming PCM16-to-normalized-float mono converter driven by the sample rate reported by `AudioProcessorInterface`. `LiveKitInjectedPcmProcessor` forwards initialization and reset events to that source, while production microphone capture remains untouched because the processor is enabled only during fixture automation. The SDK callback buffer is a native `float*` view of the first planar capture channel, not an interleaved PCM16 buffer.

**Tech Stack:** Kotlin, LiveKit Android `AudioProcessorInterface`, little-endian PCM16 fixture input, native-endian float32 callback output, kotlinx-coroutines-test, JUnit 4, Gradle Android unit tests, managed `mdev` physical-device testing.

## Global Constraints

- Fixture input remains little-endian PCM16 at exactly 16,000 Hz and one channel.
- LiveKit callback output is one normalized native-endian float per callback frame.
- The existing 3,200-byte / 100 ms harness pacing remains unchanged.
- No new telemetry is added unless post-fix physical evidence remains undecidable.
- Never access or pull the raw private voice-experience artifact; collect only the sanitized NDJSON evidence file.
- A physical call is one evidence transaction: capture worker logs before the call, keep capture alive through cleanup, classify locally, and do not retry inside the transaction.
- Use the managed physical-phone lane and always release it in a finally-style cleanup.

---

### Task 1: Stream 16 kHz fixture PCM into the negotiated LiveKit float capture format

**Files:**
- Modify: `app/src/livekitEnabled/java/me/rerere/rikkahub/voiceagent/livekit/LiveKitInjectedPcmProcessor.kt`
- Test: `app/src/livekitEnabledTest/java/me/rerere/rikkahub/voiceagent/livekit/LiveKitInjectedPcmProcessorTest.kt`

**Interfaces:**
- Consumes: `VoiceCaptureFixtureSource.pump(onPcm16, onFixtureComplete)` chunks containing little-endian 16 kHz mono PCM16.
- Produces: `LiveKitAutomationPcmSource.configureOutputFormat(sampleRateHz: Int, numChannels: Int)` and normalized-float `replaceOrZero(buffer: ByteBuffer)` behavior.
- Produces: `LiveKitInjectedPcmProcessor.initializeAudioProcessing(sampleRateHz, numChannels)` and `resetAudioProcessing(newRate)` forwarding that format without changing `AudioProcessorInterface`.

- [ ] **Step 1: Write failing representation and 16-to-48 kHz regression tests**

First add a test that places `+0.5` and `-0.5` PCM16 fixture samples into a direct callback buffer and expects normalized native-endian floats. Confirm that it fails while the processor still writes PCM16 bytes. Then add a test that initializes the real processor at 48 kHz mono and checks hand-derived output samples:

```kotlin
@Test
fun `16 kHz fixture keeps real time pacing in a 48 kHz capture buffer`() = runTest {
    val fixture = fixtureSource(pcm16(0x0102, 0x0304))
    val source = activeSource()
    val processor = LiveKitInjectedPcmProcessor(source)
    val activation = source.activate(RUN_HASH, fixture, this)
    processor.initializeAudioProcessing(sampleRateHz = 48_000, numChannels = 1)

    fixture.startInitial()
    advanceUntilIdle()

    assertArrayEquals(
        floatArrayOf(
            normalized(0x0102), normalized(0x0102), normalized(0x0102),
            normalized(0x0304), normalized(0x0304), normalized(0x0304),
        ),
        processFloats(processor, sampleCount = 6),
        0.0f,
    )
    assertTrue(source.injectionComplete())

    activation.close()
    fixture.close()
}
```

Add a test-only `pcm16(vararg samples: Int): ByteArray` helper that writes each literal sample low byte then high byte, plus a `processFloats` helper that uses `ByteBuffer.allocateDirect(...).order(ByteOrder.nativeOrder())`. The tests catch both writing the wrong representation and returning to one input sample per output frame.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
VOICE_AGENT_LIVEKIT_EXPERIMENT_ENABLED=true ./gradlew --no-configuration-cache \
  :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.livekit.LiveKitInjectedPcmProcessorTest.16 kHz fixture keeps real time pacing in a 48 kHz capture buffer'
```

Expected: the representation test fails because the original processor writes PCM16 bytes into float storage. The pacing test fails before resampling because the two source samples are consumed once and the remaining four callback frames are zero-filled.

- [ ] **Step 3: Implement the minimal streaming converter**

In `LiveKitAutomationPcmSource`, retain these format and phase values under its existing lock:

```kotlin
private var outputSampleRateHz = FIXTURE_SAMPLE_RATE_HZ
private var outputChannelCount = 1
private var resamplePhase = 0
private var currentSample: Short? = null
```

Add:

```kotlin
fun configureOutputFormat(sampleRateHz: Int, numChannels: Int)
fun replaceOrZero(buffer: ByteBuffer)
```

`configureOutputFormat` requires positive values and resets `resamplePhase` and `currentSample` only when the negotiated format changes. `replaceOrZero` sets native byte order, reads each source sample as signed little-endian PCM16, divides it by 32,768, and writes one float per callback frame. It adds 16,000 to `resamplePhase` for each output frame and consumes source samples while the phase is at least `outputSampleRateHz`. When no complete source sample is queued, it emits `0.0f`. Any trailing bytes that cannot form a float are zero-filled.

Do not duplicate samples for `numChannels`. The exact WebRTC bridge passes `audio->channels()[0]` as one planar float stream and uses the reported channel count only as format metadata.

Reset phase/sample state when automation activates, deactivates, rolls over to a new run, or clears queued audio. Keep `injectionComplete()` false while `currentSample` still has scheduled output frames.

In `LiveKitInjectedPcmProcessor`:

```kotlin
override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {
    source.configureOutputFormat(sampleRateHz, numChannels)
}

override fun resetAudioProcessing(newRate: Int) {
    source.configureOutputFormat(newRate, currentChannelCount)
}
```

Retain the last initialized channel count in the processor, defaulting to one before initialization.

- [ ] **Step 4: Verify GREEN and preserve existing behavior**

Run the whole focused class:

```bash
VOICE_AGENT_LIVEKIT_EXPERIMENT_ENABLED=true ./gradlew --no-configuration-cache \
  :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.livekit.LiveKitInjectedPcmProcessorTest'
```

Expected: PASS. Update active-path assertions to decode normalized floats from direct native-order buffers; do not weaken their ordering, zero-fill, stale-run, or inactive-microphone assertions.

- [ ] **Step 5: Add planar-channel and reset coverage**

Add two real-processor tests:

```kotlin
@Test
fun `capture callback receives one float stream even when capture reports stereo`() = runTest {
    val fixture = fixtureSource(pcm16(0x0102))
    val source = activeSource()
    val processor = LiveKitInjectedPcmProcessor(source)
    val activation = source.activate(RUN_HASH, fixture, this)
    processor.initializeAudioProcessing(sampleRateHz = 16_000, numChannels = 2)

    fixture.startInitial()
    advanceUntilIdle()

    assertArrayEquals(
        floatArrayOf(normalized(0x0102)),
        processFloats(processor, sampleCount = 1),
        0.0f,
    )
    activation.close()
    fixture.close()
}

@Test
fun `capture format reset discards an unfinished resampling phase`() = runTest {
    val fixture = fixtureSource(pcm16(0x0102, 0x0304))
    val source = activeSource()
    val processor = LiveKitInjectedPcmProcessor(source)
    val activation = source.activate(RUN_HASH, fixture, this)
    processor.initializeAudioProcessing(sampleRateHz = 48_000, numChannels = 1)

    fixture.startInitial()
    advanceUntilIdle()
    assertArrayEquals(
        floatArrayOf(normalized(0x0102)),
        processFloats(processor, sampleCount = 1),
        0.0f,
    )

    processor.resetAudioProcessing(newRate = 16_000)

    assertArrayEquals(
        floatArrayOf(normalized(0x0304)),
        processFloats(processor, sampleCount = 1),
        0.0f,
    )
    activation.close()
    fixture.close()
}
```

Use literal PCM16 input samples and exact normalized-float assertions. Run each new test first and confirm it fails for the missing planar/reset behavior before changing production code further.

- [ ] **Step 6: Run the focused class again and commit**

Run the command from Step 4, then:

```bash
git add \
  app/src/livekitEnabled/java/me/rerere/rikkahub/voiceagent/livekit/LiveKitInjectedPcmProcessor.kt \
  app/src/livekitEnabledTest/java/me/rerere/rikkahub/voiceagent/livekit/LiveKitInjectedPcmProcessorTest.kt
git commit -m "fix: write LiveKit fixture as normalized floats"
```

### Task 2: Verify the Android change and close the transcript boundary on the phone

**Files:**
- Verify: `scripts/test-voice-agent-stage1-e2e.sh`
- Build: `app/build/outputs/apk/debug/app-debug.apk`
- Evidence: the existing private transaction directory outside the repository; do not commit evidence.

**Interfaces:**
- Consumes: the corrected LiveKit capture processor from Task 1.
- Produces: one installed debug APK and one two-source transcript verdict for a single physical call.

- [ ] **Step 1: Run regression verification**

Run:

```bash
VOICE_AGENT_LIVEKIT_EXPERIMENT_ENABLED=true ./gradlew --no-configuration-cache \
  :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.livekit.*'
bash scripts/test-voice-agent-stage1-e2e.sh
```

Expected: all LiveKit unit tests and the shell harness pass.

- [ ] **Step 2: Build and inspect exactly one debug APK**

Use the existing private local build settings and run:

```bash
VOICE_AGENT_LIVEKIT_EXPERIMENT_ENABLED=true ./gradlew --no-configuration-cache :app:assembleDebug
test -f app/build/outputs/apk/debug/app-debug.apk
test -s app/build/outputs/apk/debug/app-debug.apk
```

Read the APK package/version metadata before installation. Do not print credentials or private build configuration.

- [ ] **Step 3: Install through the managed physical-phone lane**

Run `mdev android status`, install the verified APK with:

```bash
mdev android adb --device phone -- install -r app/build/outputs/apk/debug/app-debug.apk
```

Read back the exact installed package/version. Put `mdev android release` in a finally-style cleanup. Do not clear app data, uninstall, reboot, root, remount, reset, or wipe the phone.

- [ ] **Step 4: Run one clean transcript evidence transaction**

Reuse the proven valid 8.12-second 16 kHz speech fixture and one-second startup silence. Start `lk agent logs --quiet` with stdout and stderr separated, wait for replayed history to settle, record a baseline line count, run the Stage 1 call once, keep log capture alive through runner cleanup, then extract only appended worker rows. Pull only `voice-experience-events.ndjson`; never access the raw private artifact.

- [ ] **Step 5: Classify and stop at the first missing boundary**

Run the existing file classifier and worker-log tie-breaker on the same private session window:

- A valid user transcript row is `PRESENT`; continue next at `ask_hermes`.
- Zero phone rows plus one covered worker request, one ready marker, and no transcript marker is `ABSENT_AT_WORKER`; keep diagnosis at transcription/input finalization.
- A worker transcript marker plus zero phone rows is bridge breakage.
- Unretrievable or lossy worker logs are `UNKNOWN`; only then consider instrument repair.

- [ ] **Step 6: Commit any verification-only documentation update separately**

If the durable runbook required a correction discovered during execution, update only that procedure and commit it separately. Otherwise leave repository documentation unchanged.
