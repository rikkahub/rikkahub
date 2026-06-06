# Voice Agent Debug Audio Injection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a debug-only ADB-triggered PCM injection hook that feeds audio into the active Voice Agent capture path.

**Status:** Implemented and physically smoked on the Samsung tablet. Follow-up hardening added optional leading/trailing silence and PCM16 sample alignment for more reliable future smokes.

**Architecture:** `AndroidVoiceAudioEngine` registers its current capture callback with a small `VoiceAudioDebugInjector` registry while microphone capture is active. A debug-build-only broadcast receiver reads a private PCM16 file and streams it through that registry in capture-sized chunks.

**Tech Stack:** Kotlin, Android debug source set, ADB broadcast, JVM unit tests, existing Voice Agent audio abstractions.

---

### Task 1: Test and Implement the Injection Registry

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioDebugInjector.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioDebugInjectorTest.kt`

- [x] Add tests proving no active capture rejects injection, active capture receives ordered PCM chunks, closing a registration disables injection, optional silence is applied, and PCM16 chunks stay sample-aligned.
- [x] Implement `VoiceAudioDebugInjector.registerCapture()` and `VoiceAudioDebugInjector.injectPcm16()`.
- [x] Run `./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceAudioDebugInjectorTest'`.

### Task 2: Wire Active Android Capture

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`

- [x] Register the active capture callback after `AudioRecord.startRecording()` succeeds.
- [x] Unregister on stop, failed start, and release.
- [x] Deliver injected buffers under the same callback lock and generation checks as real microphone buffers.
- [x] Run `./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.*'`.

### Task 3: Add Debug Broadcast Receiver

**Files:**
- Create: `app/src/debug/AndroidManifest.xml`
- Create: `app/src/debug/java/me/rerere/rikkahub/voiceagent/debug/VoiceAudioDebugInjectionReceiver.kt`

- [x] Add a debug-build-only receiver for action `me.rerere.rikkahub.debug.voiceagent.INJECT_PCM`. It is exported so `adb shell am broadcast` can invoke it; file reads are constrained to the app private files directory.
- [x] Read `path`, `chunk_bytes`, `chunk_delay_ms`, `leading_silence_ms`, and `trailing_silence_ms` extras.
- [x] Stream the file through `VoiceAudioDebugInjector` from a background thread using `goAsync()`.
- [x] Run `./gradlew :app:assembleDebug`.

### Task 4: Device Smoke

**Files:**
- Modify: `execution-gaps.md` in `/home/muly/agora2`

- [x] Build and install the debug APK with a local Voice Lab override.
- [x] Generate or fetch a short speech file, convert it to 16 kHz mono signed PCM16, and push it into app private storage with `run-as`.
- [x] Launch Voice Agent, broadcast the injection action, and verify transcript/assistant/tool/history behavior as far as the injected prompt allows.
- [x] Update `execution-gaps.md` with the smoke result.
