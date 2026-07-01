# Voice Agent Playback Cleanup Design

Date: 2026-06-30

## Context

Stage 1 of the waiting-tone cleanup split local cue playback out of `VoicePlaybackWriter` and into `VoiceLocalCuePlayer`. That restored separate assistant and cue policy, but it left three maintainability issues:

- `VoicePlaybackWriter` and `VoiceLocalCuePlayer` still duplicated mechanical PCM sink start/write/release handling.
- `AndroidVoiceAudioEngine` still owned Android playback track state inside a file that also owned capture, routing, Bluetooth, audio focus, diagnostics, and top-level release ordering.
- The local cue API still used `sessionId` even though the value was a monotonic cue invalidation token.

This cleanup preserves waiting-tone behavior while improving ownership boundaries. It intentionally avoids a generic playback framework because assistant playback and local cue playback have different policy.

## Target Approach

Use a medium-scope cleanup:

1. make local cue token semantics explicit with `cueToken: Long?` naming;
2. extract Android playback track ownership from `AndroidVoiceAudioEngine`;
3. extract only low-level PCM sink lifecycle mechanics shared by assistant playback and local cue playback.

The original brainstorm considered a `VoiceLocalCueToken` value class. The implementation keeps the existing wire/runtime representation as `Long?` and fixes the ambiguity through API naming instead. That gives the readability benefit without spreading a new wrapper through test fakes and engine interfaces.

## Architecture

`AndroidVoicePlaybackTracks` owns assistant and local cue `AudioTrack` instances plus the `AndroidAudioTrackSink` adapter. `AndroidVoiceAudioEngine` keeps capture, routing, focus, Bluetooth, permissions, diagnostics, and top-level release ordering, but it no longer directly manages playback tracks.

The local cue boundary uses `cueToken` language:

- `playLocalCuePcm16(base64Pcm16, cueToken: Long?)`
- `invalidateLocalCuePlayback(cueToken: Long?)`
- `VoiceLocalCuePlayer.playBase64(base64Pcm16, cueToken)`
- `VoiceLocalCuePlayer.invalidate(cueToken)`

The old `sessionId` name should appear only where the value is a real assistant voice session identity.

`VoicePcm16SinkLifecycle` centralizes mechanical sink operations:

- create and start a sink;
- run writes and normalize thrown exceptions into failed write results;
- pause/flush and stop/release sinks safely.

It does not own queues, stale checks, suppression, diagnostics, token invalidation, or fatal-error policy.

## Behavioral Boundaries

Assistant PCM still flows through `VoicePlaybackWriter`. The writer owns assistant session generation checks, suppression, stale-chunk handling, and fatal playback diagnostics.

Local cue PCM still flows through `VoiceLocalCuePlayer`. The cue player owns cue queue capacity, cue invalidation, stale cue rejection, active sink retirement, and diagnostic-only local cue failures.

`AndroidVoicePlaybackTracks` owns Android `AudioTrack` references and current-track checks. Assistant and cue tracks remain separate because their interruption behavior and error severity differ. Top-level engine release marks the track owner released before releasing writer/player state, then releases any remaining Android tracks.

## Error Handling

Assistant playback failures remain user-visible audio/session errors where they were before this cleanup.

Local cue playback failures remain diagnostic-only. They must not transition the voice session to an error state.

Release and invalidation remain idempotent. Calling cue invalidation before grace delay, while queued, while writing, after release, or multiple times should not throw.

## Testing

Coverage should include:

- local cue token naming and stale-token rejection;
- local cue invalidation before write, during write, after repeated invalidation, and after release;
- assistant playback stale chunks, suppression, start failure, write failure, and release behavior;
- pure `VoicePcm16SinkLifecycle` start, write, exception, interruption, and cleanup behavior;
- waiting-tone grace delay, repeat behavior, stop behavior, local cue rejection diagnostics, and invalidation errors;
- the focused voice-agent regression suite before merge.

Direct JVM tests for `AndroidVoicePlaybackTracks` are limited by Android framework `AudioTrack` construction. Keep that owner simple and cover the extractable behavior through `VoicePlaybackWriter`, `VoiceLocalCuePlayer`, lifecycle helper tests, and Android engine compile coverage.

## Explicit Non-Goals

- Do not create one shared playback queue or coroutine worker for assistant and local cue playback.
- Do not refactor capture, Bluetooth, audio focus, transcript persistence, reconnect policy, or Hermes polling.
- Do not make local cue failures fatal.
- Do not change waiting-tone timing or user-visible behavior.
