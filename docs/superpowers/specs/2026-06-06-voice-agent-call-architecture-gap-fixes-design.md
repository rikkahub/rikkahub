# Voice Agent Call Architecture Gap Fixes Design

Date: 2026-06-06

## Goal

Fix the Voice Agent review gaps while moving the feature toward a real call-style Android experience.

The user must be able to start Voice Agent from a RikkaHub chat, switch to other apps while the call keeps running as much as Android allows, return from an ongoing notification, and still get normal conversation-history transcript and Hermes/MS-agent tool records.

This design intentionally accepts one current risk: Cloudflare Access credentials may remain compiled into personal credentialed APKs through `BuildConfig`. The APK must be treated as personal-only and not distributed. This pass documents that risk but does not redesign Cloudflare credential storage.

## Scope

In scope:

- Self-managed call-style Voice Agent ownership.
- Background-capable microphone session using Android foreground-service/call semantics.
- Transcript persistence identity fix across multiple Voice Agent sessions in one conversation.
- Debug receiver hardening while preserving ADB testing convenience.
- UI/state changes needed for a service-owned call.
- Focused tests and device smoke for the fixed gaps.

Out of scope:

- Replacing RikkaHub's normal chat architecture.
- Upstreaming the fork.
- Removing compiled Cloudflare credentials.
- Building a system Phone-app UI or showing Voice Agent inside the default dialer.
- Redaction of prompt/answer diagnostics for alpha personal usage.

## Android Platform Constraints

Android restricts background microphone access. A Voice Agent session that needs to keep listening after the app is no longer visible must be started while RikkaHub is foregrounded and then continue through Android-supported long-running work.

The intended path is:

- A foreground service with microphone support and an ongoing notification.
- Self-managed Telecom/ConnectionService semantics for call-style behavior.
- A RikkaHub-owned focused call screen, not integration into the default Phone app UI.

Relevant Android guidance:

- Foreground service types and microphone restrictions: https://developer.android.com/develop/background-work/services/fgs/service-types
- Restrictions on starting foreground services from the background: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Telecom framework overview: https://developer.android.com/develop/connectivity/telecom
- `ConnectionService` API reference: https://developer.android.com/reference/android/telecom/ConnectionService

## Architecture

Voice Agent call ownership moves out of the Compose screen and into a new service-owned runtime under `voiceagent`.

The core new owner is `VoiceAgentCallService`. It starts while RikkaHub is visible, enters foreground mode quickly, exposes an ongoing notification, and owns the live call session. The service owns Gemini Live, Voice Lab session creation, Hermes/MS-agent tool calls, Android audio capture/playback, diagnostics, and persistence.

The current focused Voice Agent screen remains the control surface. It launches or attaches to the service-backed call, renders the current state, and sends commands: start, end, mute, interrupt, reconnect, and return-to-chat. The screen can detach when the app backgrounds without ending the call.

Most new code should live under `me.rerere.rikkahub.voiceagent`. Expected app touch points are limited to:

- Android manifest service, foreground-service permissions, Telecom permissions, and notification metadata.
- DI registration for service/session dependencies.
- The existing chat launch button and route.
- Notification pending intent routing back to `Screen.VoiceAgent`.

## Components

### `VoiceAgentCallService`

Responsibilities:

- Start as a foreground service before background microphone access is needed.
- Own the active `VoiceAgentCallSession`.
- Publish call state for UI attachment.
- Show and update an ongoing notification.
- Handle notification actions such as End and optionally Mute.
- Stop foreground mode and service when the call is explicitly ended or irrecoverably failed.

The service should not contain all business logic directly. It should delegate to focused classes so `VoiceAgentViewModel.kt` does not grow further.

### `VoiceAgentCallSession`

Responsibilities:

- Wrap the existing `VoiceAgentCoordinator`/Gemini/audio/Voice Lab pieces.
- Own the session lifecycle independently from Compose.
- Translate service and UI commands into coordinator operations.
- Preserve current interrupt semantics: suppress audio immediately, but do not cancel Hermes/MS-agent work unless the user explicitly ends or reconnects in a way that requires cancellation.
- Drain persistence on explicit end.

### `VoiceAgentCallController`

Responsibilities:

- Provide a small interface used by the screen and notification actions.
- Expose `StateFlow<VoiceAgentUiState>` or a call-state wrapper that includes session status, audio status, tool status, transcript, persistence, diagnostics, and notification/background capability.
- Hide service binding details from Compose.

### Self-Managed Call Integration

Implement self-managed call support behind a `VoiceAgentTelecomAdapter`. RikkaHub keeps its own UI. The implementation must not use or replace the system Phone app UI.

If Telecom registration fails on a device, the adapter reports the failure to diagnostics and the foreground-service notification path remains active. This keeps the session usable while making the missing call integration visible.

## Data Flow

1. User taps `Start talking` in chat.
2. RikkaHub resolves `VoiceAgentLaunchConfig` from the current assistant/model/provider configuration.
3. The Voice Agent route starts or attaches to `VoiceAgentCallService` with the conversation id and launch config.
4. The service creates a `VoiceAgentCallSession`, enters foreground mode, and posts the ongoing notification.
5. The session creates a Voice Lab mobile session, connects Gemini Live, activates audio capture/playback, and publishes status.
6. The screen observes state from the service-backed controller.
7. If the screen stops or the user switches apps, the service keeps the call alive and the notification remains visible.
8. Hermes/MS-agent tool calls run through the local mobile API path and persist visible tool records.
9. Transcript and tool records persist into normal RikkaHub conversation history.
10. Explicit End closes Gemini/audio, drains or cancels tools according to current session-end rules, persists final transcript/tool state, removes notification, and stops the service.

## Gap Fixes

### Transcript Identity

Current transcript upsert matches only voice transcript role and turn id. Turn ids restart for each new Voice Agent coordinator, so a second call in the same conversation can overwrite an earlier call.

Fix:

- When `voice_session_id` exists, transcript identity is `(voice_session_id, voice_transcript_role, voice_transcript_turn_id)`.
- Legacy transcript messages without `voice_session_id` keep old matching behavior.
- Add a test that writes `session-a/user-1` and `session-b/user-1` into the same conversation and expects two messages.

### Background Behavior

Current `ON_STOP` ends the Voice Agent session. That is no longer correct.

Fix:

- `ON_STOP` must detach the screen only. It must not call `endBecauseBackgrounded()`.
- The call service keeps the session alive in foreground mode with an ongoing notification.
- If Android denies or interrupts microphone capture, record a visible state and diagnostic instead of silently ending.
- Pending Hermes/MS-agent calls must not be canceled just because the UI detached.
- Explicit End remains terminal and may cancel not-yet-sending tool calls while allowing already-started sends to finish when possible.

### Debug Receiver Hardening

Current debug receivers are exported for ADB convenience. The Hermes seed receiver can rewrite provider config, so ordinary apps on the same device must not be able to call it.

Fix:

- Keep ADB broadcast workflows for debug testing.
- Protect exported debug receivers with a debug-only signature permission declared in the debug manifest.
- The audio injection receiver may remain exported if protected and constrained to app-private file reads.
- The Hermes seed receiver must be protected because it can repoint traffic.
- Add tests or manifest checks that verify the receiver protection is present in the debug manifest.

### Cloudflare Credential Risk

Keep current `BuildConfig` Cloudflare default behavior for this pass.

Documented constraint:

- A credentialed APK embeds Cloudflare Access token material and must be considered personal-only.
- Do not distribute credentialed APKs.
- Do not commit real credentials, raw headers, or unredacted logcat output.

## UX And State

The Voice Agent screen should represent a call, not a page-scoped task.

Important visible states:

- Starting
- Active
- Background capable
- Reconnecting
- Ending
- Ended
- Error

The notification should show that RikkaHub Voice Agent is active and provide a route back to the Voice Agent screen. End must be available from the notification. Mute is deferred unless the implementation plan can add it without broadening the service/controller contract.

When the app backgrounds, the user should not see a fake failure on return. The call should either still be active or show a concrete platform/audio reason for degraded operation.

## Error Handling

Foreground-service or Telecom setup failure:

- Show a clear visible error on the Voice Agent screen.
- Record the underlying exception in diagnostics.
- Do not pretend background voice is available.

Microphone denied or capture lost:

- Show a clear audio status such as microphone unavailable.
- Keep Gemini/tool state understandable.
- Let the user reconnect or end.

Hermes/MS-agent calls:

- Continue pending work while the screen is detached.
- Persist tool status transitions as visible tool records.
- On explicit End, preserve the current distinction between cancelable calls and already-started sends.

## Testing

Focused unit tests:

- Transcript cross-session identity does not overwrite earlier voice sessions.
- Screen detach/background event does not end the service-owned call.
- Explicit End drains persistence and applies the intended tool cancellation rules.
- Debug receivers are protected in the debug manifest.
- Notification intent routes back to the active Voice Agent screen.
- Existing Voice Agent coordinator/audio/Gemini/Voice Lab/persistence tests still pass.

Device smoke:

- Install credentialed debug APK on the USB/ADB device.
- Start Voice Agent from chat.
- Confirm ongoing notification appears.
- Switch to another app and verify the call does not end.
- Speak or debug-inject audio while the call remains active, if Android/device policy allows capture.
- Return from notification to Voice Agent screen.
- Confirm transcript and Hermes/MS-agent tool records persist into normal chat history.
- Confirm no Cloudflare HTML/auth failure occurs in the credentialed build.

## Implementation Notes

- Keep the feature localized under `voiceagent` as much as possible.
- Prefer a small service/controller/session split over expanding the existing ViewModel further.
- Keep existing coordinator tests valuable by adapting ownership around them, not rewriting core tool/Gemini logic unnecessarily.
- If Android Telecom setup has device-specific requirements, isolate it behind an adapter and keep the foreground service path working.
- The first implementation should prioritize reliable personal-device behavior and clear diagnostics over broad product polish.
