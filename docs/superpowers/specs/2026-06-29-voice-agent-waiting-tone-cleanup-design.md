# Voice Agent Waiting Tone Cleanup Design

Date: 2026-06-29

## Context

RikkaHub PR #16 added Hermes waiting-tone playback while background Hermes jobs are queued or running. The behavior is useful and covered by tests, but the implementation spread cue-specific lifecycle state across `VoiceAgentCoordinator`, `VoicePlaybackWriter`, and `AndroidVoiceAudioEngine`.

The cleanup goal is behavior preservation plus better long-term ownership:

- keep the current waiting-tone UX;
- remove local-cue state from assistant playback paths;
- reduce source-specific branching in Android audio code;
- replace scattered coordinator flags with one explicit waiting-tone eligibility model;
- stage the work into small PRs that are easy to review and verify.

## Current Problems

`VoicePlaybackWriter` now handles assistant playback and local cue playback in one state machine. That adds `VoicePlaybackSource`, local cue generation, local cue invalidation, and retired local cue sink ownership to a class that previously had one job.

`AndroidVoiceAudioEngine` now branches on playback source in track creation, track lookup, diagnostics, and sink writing. This pushes more feature-specific logic into a file that already owns capture, routing, Bluetooth, audio focus, playback, and release ordering.

`VoiceAgentCoordinator` now owns waiting-tone lifecycle with scattered flags and refresh calls. `hermesWaitingToneSuspended`, `assistantOutputAudioActive`, and direct `HermesWaitingToneController` calls appear in session status updates, suppression, reconnect prep, close, output audio, and tool status updates.

`VoiceAudioEngine.setLocalCueErrorHandler` exposes a global mutable callback for a narrow Hermes cue concern. Cue failure handling should be scoped to the cue playback owner instead.

## Target Architecture

`VoicePlaybackWriter` should return to being assistant-only. It owns active assistant playback session, assistant generation, one assistant sink, suppression, release, and assistant playback diagnostics. It should not know about local cues, cue tokens, source enums, or retired cue sinks.

A new local cue component, `VoiceLocalCuePlayer`, should own short best-effort cue playback. It owns cue sink creation, cue token/generation invalidation, release, and cue-specific diagnostics. It should be small and independent from assistant playback.

`AndroidVoiceAudioEngine` should keep the public `VoiceAudioEngine` API but delegate assistant playback and local cue playback to separate collaborators. It remains responsible for Android platform primitives: capture, permissions, routing, Bluetooth, audio focus, and top-level release ordering.

`VoiceAgentCoordinator` should stop manually coordinating waiting-tone state with several atomic flags. It should update a small waiting-tone eligibility model, or pass the relevant lifecycle events into a small controller that derives whether the tone should be active.

Broader decomposition of `VoiceAgentCoordinator` and `HermesJobManager` is deferred until the waiting-tone and audio boundaries are clean. Further extraction should happen only where these stages reveal a clear behavior-preserving boundary.

## Components

### Assistant Playback Writer

`VoicePlaybackWriter` keeps assistant-stream playback only.

Responsibilities:

- decode assistant PCM chunks;
- reject stale assistant chunks by active session id/generation;
- own one assistant playback sink;
- suppress/release assistant playback;
- emit assistant playback diagnostics.

Removed responsibilities:

- `VoicePlaybackSource`;
- local cue generation;
- local cue invalidation;
- local cue sink and retired local cue sink ownership;
- cue-specific diagnostic routing.

### Local Cue Player

Add `VoiceLocalCuePlayer` in `voiceagent/audio`.

Responsibilities:

- accept base64 PCM cue playback requests;
- associate requests with a token/generation;
- invalidate queued or active cue playback by token/generation;
- own one cue sink at a time;
- make cue playback best-effort and diagnostic-only;
- release idempotently.

The local cue player can reuse low-level sink interfaces where useful, but it should not force assistant playback to accept cue-specific parameters.

### Audio Engine Playback Facade

`AndroidVoiceAudioEngine` delegates:

- assistant PCM to `VoicePlaybackWriter`;
- local cue PCM to `VoiceLocalCuePlayer`.

Stage 3 should extract track creation into a helper or factory so the engine does not carry repeated source-specific branches. A small Android playback sink factory can own `AudioTrack` construction and `AndroidAudioTrackSink` details for both assistant and cue playback.

### Waiting Tone Eligibility

Add `HermesWaitingToneEligibility` as the small model that derives whether the tone should be active.

Inputs:

- session status;
- tool status and active tool calls;
- playback suppression/interruption state;
- assistant output activity;
- closing/closed state.

Output:

- a single active/inactive decision for `HermesWaitingToneController`.

The coordinator should update this model at existing lifecycle boundaries, but it should not hold multiple waiting-tone-specific atomic flags or manually refresh the controller from unrelated methods.

## Data Flow

1. `HermesJobManager` emits `VoiceToolStatus` updates exactly as it does today.
2. `VoiceAgentCoordinator` updates `VoiceAgentUiState.tool` and `toolCalls`.
3. The waiting-tone eligibility component derives `active = true` only when:
   - the session is connected;
   - at least one Hermes call is queued or running;
   - playback is not suppressed or interrupted;
   - assistant audio is not currently active;
   - the coordinator is not closing or closed.
4. `HermesWaitingToneController` receives the active/inactive signal and owns grace delay and repeat timing.
5. When the controller emits a cue, it calls the local cue API on `VoiceAudioEngine`.
6. `AndroidVoiceAudioEngine` forwards cue playback to `VoiceLocalCuePlayer`.
7. Assistant audio remains separate: Gemini output audio goes through `VoicePlaybackWriter`, and assistant-audio start sends one clean inactive signal into the waiting-tone path.

There should be no shared `VoicePlaybackSource` enum driving both assistant and cue behavior. Shared helper code is fine when it is mechanical and does not merge the two lifecycles.

## Error Handling

Assistant playback errors remain user-visible session/audio errors where they are today.

Local cue failures are diagnostic-only. They must not transition the voice session to an error state.

Local cue failures should record diagnostics such as `hermes_waiting_tone_failed` through a scoped callback owned by the cue player/controller path, not through a global mutable `setLocalCueErrorHandler`.

Cue invalidation and release must be idempotent. Calling stop during grace delay, during queued playback, while a cue is writing, after close, or multiple times should not throw.

If a cue write is already in progress, invalidation should prevent future/repeated cues and make a best-effort attempt to interrupt or flush the current cue. Coordinator close should not block on Android audio internals longer than necessary.

Reconnect and session cleanup should suppress both assistant playback and local cues through explicit component APIs rather than scattered coordinator flags.

## Testing

`VoicePlaybackWriterTest` should become assistant-only. It should assert assistant session invalidation, suppression, stale chunk rejection, sink start/write failures, and release behavior.

Add `VoiceLocalCuePlayerTest`. It should cover:

- cue acceptance and rejection by token/generation;
- invalidate before write;
- invalidate during write;
- repeated invalidate;
- release;
- sink start/write failure diagnostics;
- local cue failures remaining diagnostic-only.

`HermesWaitingToneControllerTest` keeps timing coverage:

- grace delay;
- repeat interval;
- stop before grace;
- stop while cue is active;
- close;
- diagnostic resilience.

It should use a fake `VoiceAudioEngine` until a narrower cue interface exists. If Stage 2 extracts a narrower cue interface for `HermesWaitingToneController`, these tests should switch to that interface.

`VoiceAgentCoordinatorWaitingToneTest` should remain coordinator-level. It should focus on policy and integration:

- queued/running Hermes state starts eligibility;
- completion, cancellation, interruption, reconnect, and session end clear eligibility;
- assistant audio suppresses tone.

Existing runtime, call-session, Hermes manager, and playback tests continue to run as regression coverage.

## Staged PR Plan

### Stage 1: Split Local Cue Playback

Add `VoiceLocalCuePlayer`.

Move cue generation, invalidation, cue sink lifecycle, and cue diagnostics out of `VoicePlaybackWriter`.

Return `VoicePlaybackWriter` to assistant-only logic.

Keep the `VoiceAudioEngine` caller-facing behavior unchanged.

### Stage 2: Replace Coordinator Flags With Eligibility

Introduce `HermesWaitingToneEligibility`.

Derive active/inactive from explicit inputs instead of scattered `AtomicBoolean`s and direct refresh calls.

Keep current waiting-tone behavior unchanged.

### Stage 3: Extract Android Playback Track Ownership

Move assistant/cue `AudioTrack` creation, current-track checks, and sink release mechanics behind smaller Android playback collaborators or a sink factory.

Remove `VoicePlaybackSource` and source-specific branching from Android audio code.

Leave capture, Bluetooth, routing, and focus behavior untouched unless release ordering requires a narrow adjustment.

### Stage 4: Reassess Broader Decomposition

Run another strict maintainability review.

Only split `VoiceAgentCoordinator` or `HermesJobManager` further where the prior stages reveal a clear behavior-preserving extraction boundary.

Do not refactor transcript persistence, reconnect policy, or Hermes polling only for line count.

## Verification

Each staged PR should run the smallest focused test set first, then broader voice-agent regression tests before merge.

Expected focused commands:

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

If a stage does not yet introduce `VoiceLocalCuePlayerTest`, omit that filter for that stage.

## Non-Goals

- Change the waiting-tone UX.
- Change Hermes queue semantics.
- Change Gemini audio playback behavior.
- Change reconnect policy.
- Split `VoiceAgentCoordinator` or `HermesJobManager` only to reduce line count.
- Introduce new user-visible audio settings.
