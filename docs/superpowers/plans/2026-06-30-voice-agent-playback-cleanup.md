# Voice Agent Playback Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Clean up voice-agent playback ownership by making local cue tokens explicit, extracting Android playback track management, and sharing only mechanical PCM sink lifecycle code.

**Architecture:** Assistant playback and local cue playback keep separate state machines and diagnostics. Local cue APIs use `cueToken: Long?` so cue invalidation cannot be confused with assistant session ids. `AndroidVoicePlaybackTracks` owns Android `AudioTrack` state, while `VoicePcm16SinkLifecycle` centralizes sink start/write/release mechanics without owning playback policy.

**Tech Stack:** Kotlin, Android `AudioTrack`/`AudioRecord`, coroutines, JUnit 4, Gradle Android unit tests.

## Implementation Note

The design discussion considered a `VoiceLocalCueToken` value class. The final implementation deliberately keeps cue tokens as `Long?` and fixes the ambiguity through parameter, field, fake, and diagnostic naming. That keeps the change smaller while still removing the misleading `sessionId` terminology from local cue playback.

## Task 1: Make Local Cue Tokens Explicit

Files:

- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioEngine.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/hermes/HermesWaitingToneController.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentFakes.kt`
- local cue and waiting-tone tests

Steps:

- Rename local cue API parameters from `sessionId` or generic `token` to `cueToken`.
- Rename stale local cue diagnostics to `rejectedCueToken`.
- Keep assistant playback APIs using `sessionId`; those values are real assistant playback session ids.
- Update `HermesWaitingToneController` to pass its monotonic generation as the local cue token.
- Update fakes and tests to assert `playedLocalCueTokens` and `invalidatedLocalCueTokens`.
- Audit local cue code to make sure stale `sessionId`, `rejectedToken`, `playedLocalCueSessionIds`, and `invalidatedLocalCueSessionIds` names are gone.

Verification:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.hermes.HermesWaitingToneControllerTest' \
  --rerun-tasks
```

## Task 2: Extract Shared PCM Sink Lifecycle Mechanics

Files:

- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycle.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoicePcm16SinkLifecycleTest.kt`
- writer and local cue tests

Steps:

- Add `VoicePcm16SinkLifecycle.createStarted`, `writeFully`, `pauseAndFlushSafely`, and `stopAndReleaseSafely`.
- Keep queue ownership, session/cue staleness, suppression, and diagnostics in the callers.
- Normalize thrown sink exceptions into failed start/write outcomes.
- Safely swallow cleanup exceptions because sink cleanup is best-effort on invalidation/release paths.
- Reuse `VoicePcm16Sink.WriteResult` rather than adding a duplicate write outcome type.

Verification:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePcm16SinkLifecycleTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoicePlaybackWriterTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceLocalCuePlayerTest' \
  --rerun-tasks
```

## Task 3: Adopt Lifecycle Helper In Playback Callers

Files:

- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoicePlaybackWriter.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceLocalCuePlayer.kt`
- writer and local cue tests

Steps:

- Use the helper in assistant playback start, write, suppression, invalidation, and release paths.
- Use the helper in local cue sink start, write, invalidation, active sink retirement, and release paths.
- Keep the local cue retired-sink release rule local to `VoiceLocalCuePlayer`, because that rule is cue-specific policy rather than generic lifecycle behavior.
- Cover cleanup exception paths at the caller level.

## Task 4: Extract Android Playback Track Ownership

Files:

- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoicePlaybackTracks.kt`
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`

Steps:

- Move assistant/local cue `AudioTrack` ownership into `AndroidVoicePlaybackTracks`.
- Keep assistant playback creation and local cue creation separate.
- Route assistant `AudioTrack` creation/play/write failures to the engine audio error handler.
- Keep local cue `AudioTrack` creation/play/write failures diagnostic-only.
- In top-level engine release, mark the track owner released before releasing writer/player state so no release-racing write can reuse a track.
- Release any remaining tracks through `releaseAll`.

Verification:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:compileDebugKotlin
```

## Task 5: Final Regression And Audit

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

Also run:

```bash
git diff --check
rg -n "playLocalCuePcm16\([^\\n]*sessionId|invalidateLocalCuePlayback\([^\\n]*sessionId|rejectedToken|playedLocalCueSessionIds|invalidatedLocalCueSessionIds" app/src/main app/src/test
rg -n "AudioTrack|AndroidAudioTrackSink|AndroidPlaybackTrackOwner|currentPlaybackTrack|playbackBufferSizeOrNull|MAX_BLOCKING_ZERO_WRITES" app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt
```

Expected audit results:

- no stale local cue session-token names;
- no raw playback `AudioTrack` ownership left in `AndroidVoiceAudioEngine`;
- assistant playback session ids remain named `sessionId`;
- waiting-tone grace delay, repeat timing, stop behavior, and local cue invalidation behavior remain unchanged.

## Explicit Non-Goals

- Do not create one shared playback queue or coroutine worker for assistant and local cue playback.
- Do not refactor capture, Bluetooth, audio focus, transcript persistence, reconnect policy, or Hermes polling.
- Do not make local cue failures fatal.
- Do not change waiting-tone timing or user-visible behavior.
