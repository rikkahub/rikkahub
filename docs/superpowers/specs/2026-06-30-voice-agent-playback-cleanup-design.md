# Voice Agent Playback Cleanup Design

Date: 2026-06-30

## Context

Stage 1 of the waiting-tone cleanup split local cue playback out of `VoicePlaybackWriter` and into `VoiceLocalCuePlayer`. That restored separate assistant and cue policy, but it left three maintainability issues:

- `VoicePlaybackWriter` and `VoiceLocalCuePlayer` still duplicate mechanical PCM sink start/write/release handling.
- `AndroidVoiceAudioEngine` still owns Android playback track state inside a file that also owns capture, routing, Bluetooth, audio focus, diagnostics, and top-level release ordering.
- the local cue API still uses `sessionId` even though the value is a monotonic cue invalidation token.

This cleanup should preserve current waiting-tone behavior while improving ownership boundaries. It should avoid introducing a generic playback framework before the stable abstraction is proven.

## Target Approach

Use a medium-scope follow-up cleanup:

1. make local cue token semantics explicit;
2. extract Android playback track ownership from `AndroidVoiceAudioEngine`;
3. extract only low-level PCM sink lifecycle mechanics shared by assistant playback and local cue playback.

Do not merge assistant playback and local cue playback into one shared queue or worker. Their policies are intentionally different: assistant playback can report fatal audio errors, while local cue playback is short, best-effort, and diagnostic-only.

## Architecture

Introduce a small Android playback-track owner named `AndroidVoicePlaybackTracks` that owns assistant and local cue `AudioTrack` instances plus the `AndroidAudioTrackSink` adapter. `AndroidVoiceAudioEngine` keeps capture, routing, focus, Bluetooth, permissions, and top-level release ordering, but it stops directly managing playback tracks.

Rename the local cue boundary from `sessionId` to `cueToken`. The cue player already treats the value as a monotonic invalidation token, so the API should say that directly. Add a small value type, `VoiceLocalCueToken`, for code touched by this cleanup so voice session ids and cue invalidation tokens cannot be confused.

Extract a small shared helper named `VoicePcm16SinkLifecycle` for PCM sink lifecycle. The helper centralizes mechanical work: create or start a sink, write PCM, normalize exceptions and failed sink results, and close or release retired sinks. It does not own queues, stale checks, suppression, diagnostics, token invalidation, or fatal-error policy.

## Components

### Android Playback Tracks

`AndroidVoicePlaybackTracks` owns Android playback details currently embedded in `AndroidVoiceAudioEngine`:

- assistant playback track;
- local cue playback track;
- current-track checks;
- track replacement;
- assistant-only release;
- cue-only release;
- full playback release;
- creation of `VoicePcm16Sink` wrappers around Android `AudioTrack` instances.

Expected public surface inside the audio package:

- `createAssistantSinkOrNull(): VoicePcm16Sink?`
- `createLocalCueSinkOrNull(): VoicePcm16Sink?`
- `releaseAssistant()`
- `releaseLocalCue()`
- `releaseAll()`

The exact method names can change during implementation, but consumers should use intent-level operations rather than manipulating raw `AudioTrack` references.

### Android Voice Audio Engine

`AndroidVoiceAudioEngine` keeps the public `VoiceAudioEngine` contract. It delegates playback sink creation and playback-track release to `AndroidVoicePlaybackTracks`.

It should still coordinate engine-level ordering. For example, top-level release should release recorder/capture resources and playback resources in a deliberate order, but the actual playback-track mechanics should live in the extracted owner.

The engine should not grow new assistant-vs-cue branching beyond wiring `VoicePlaybackWriter` to assistant sinks and `VoiceLocalCuePlayer` to local cue sinks.

### Local Cue Token

The local cue API should use explicit cue-token language:

- `playLocalCuePcm16(base64Pcm16, cueToken: VoiceLocalCueToken?)`
- `invalidateLocalCuePlayback(cueToken: VoiceLocalCueToken?)`
- `VoiceLocalCuePlayer.playBase64(base64Pcm16, cueToken)`
- `VoiceLocalCuePlayer.invalidate(cueToken)`

`HermesWaitingToneController` should keep generating monotonic values and wrap them in `VoiceLocalCueToken`. Diagnostics should use cue-token wording consistently.

The old `sessionId` name should disappear from local cue code unless it refers to a real voice session identity.

### PCM Sink Lifecycle Helper

Add `VoicePcm16SinkLifecycle` for shared sink mechanics.

Responsibilities:

- run `VoicePcm16Sink.start()` and return a structured start outcome;
- run `VoicePcm16Sink.writeFully(ByteArray)` and return a structured write outcome;
- convert thrown exceptions into failure outcomes;
- release sinks idempotently;
- avoid deciding whether failures are fatal.

Non-responsibilities:

- no command queue;
- no coroutine worker;
- no assistant session generation checks;
- no local cue token checks;
- no suppression policy;
- no diagnostics routing;
- no session error handling.

`VoicePlaybackWriter` and `VoiceLocalCuePlayer` keep their separate state machines. They call the helper only for mechanical sink operations, then map outcomes into their own diagnostics and policy.

## Data Flow

Assistant PCM still flows through `VoicePlaybackWriter`. When the writer needs output, it asks the Android track owner for an assistant sink through the engine wiring. The sink lifecycle helper starts the sink and writes PCM. The writer decides whether a chunk is stale, suppressed, released, or fatal.

Local cue PCM still flows through `VoiceLocalCuePlayer`. `HermesWaitingToneController` passes a cue token. The cue player rejects stale tokens and invalidates queued or active cue work based on token ordering. The sink lifecycle helper handles only mechanical sink start/write/release operations.

`AndroidVoicePlaybackTracks` owns the Android `AudioTrack` objects. Assistant and cue tracks remain separate because their invalidation and interruption behavior differs. Cue invalidation should release or replace only the cue track unless a top-level release explicitly releases everything.

## Error Handling

Assistant playback failures remain user-visible audio/session errors where they are today.

Local cue playback failures remain diagnostic-only. They must not transition the voice session to an error state.

The shared sink helper returns structured outcomes and never decides severity. `VoicePlaybackWriter` maps failures to assistant playback diagnostics and existing fatal audio handling. `VoiceLocalCuePlayer` maps failures to local cue diagnostics and waiting-tone error recording.

Release and invalidation must remain idempotent. Calling cue invalidation before grace delay, while queued, while writing, after release, or multiple times should not throw.

## Testing

`VoicePlaybackWriterTest` remains assistant-only. It should continue to cover:

- stale assistant chunks;
- suppression;
- sink start failure;
- sink write failure;
- release behavior;
- absence of local cue concepts.

`VoiceLocalCuePlayerTest` should cover token-focused cue behavior:

- cue acceptance by current token;
- stale token rejection;
- invalidation before write;
- invalidation during write;
- repeated invalidation;
- release;
- malformed base64;
- diagnostic-only sink failures.

Add focused tests for `AndroidVoicePlaybackTracks` if it can be tested with JVM-friendly fakes around track creation and sink release. If Android framework constraints make that awkward, cover the extraction through the narrowest existing engine-level test seam and keep the track owner itself simple.

Add focused tests for `VoicePcm16SinkLifecycle` because it is pure mechanical behavior:

- start success;
- start failure result;
- start exception;
- write success;
- write failure result;
- write exception;
- interrupted write result;
- idempotent release.

Existing waiting-tone and voice-agent regression tests should continue to run before merge:

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

## Rollout

Implement on one follow-up branch in small commits:

1. add `VoiceLocalCueToken`, rename the cue-token API, and update cue tests;
2. extract Android playback track ownership;
3. extract the PCM sink lifecycle helper and update `VoicePlaybackWriter` and `VoiceLocalCuePlayer` to use it;
4. run focused tests and the voice-agent regression suite;
5. run another strict maintainability review before creating the PR.

No UX behavior should change. Waiting-tone grace delay, repeat timing, cancellation, reconnect cleanup, local cue invalidation, and assistant-audio suppression should remain unchanged.

## Explicit Non-Goals

- Do not create one shared playback queue or coroutine worker for assistant and local cue playback.
- Do not refactor capture, Bluetooth, audio focus, transcript persistence, reconnect policy, or Hermes polling.
- Do not make local cue failures fatal.
- Do not change waiting-tone timing or user-visible behavior.
