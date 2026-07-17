# Fail-Closed Voice Sentry and Telecom Audio Ownership Design

**Date:** 2026-07-15
**Status:** Approved design

## Problem

The installed Voice Agent debug APK has two independent configuration and ownership defects.

First, Voice Agent Sentry credentials exist in `~/.config/voice-lab/local.env`, but the Android build reads only `local.properties` and the Gradle process environment. A normal `assembleDebug` invocation therefore compiled empty Sentry values into `BuildConfig`, selected `NoOpVoiceObservability`, and produced a working but unobservable APK.

Second, the self-managed Telecom connection successfully selected the Bluetooth call endpoint, after which `AndroidVoiceAudioEngine` independently requested audio focus and repeated Bluetooth routing through `setCommunicationDevice`, `startBluetoothSco`, headset voice-recognition activation, and `AudioRecord.setPreferredDevice`. Android rejected some of those duplicate requests even though capture and playback worked through the Telecom route. The current order also starts the managed voice session before requesting the Telecom call, so capture can race Telecom connection creation.

## Goals

- Make every locally assembled or installed debug APK initialize Voice Agent Sentry.
- Keep Sentry credentials outside Git and out of build output and logs.
- Make exactly one component own audio focus and communication routing for a voice session.
- Use the existing self-managed Telecom connection as the primary owner.
- Preserve direct AudioManager routing only when Telecom cannot be established before session startup.
- Keep ownership immutable for the lifetime of a managed voice session.
- Produce diagnostics that describe the selected owner and real failures without reporting expected duplicate-routing rejection.

## Non-goals

- Deterministic Gemini-to-Hermes delegation. That work is deferred in https://github.com/mulyoved/rikkahub/issues/46.
- Migrating the existing ConnectionService integration to Core-Telecom.
- Changing Gemini, Hermes, announcement, playback-drain, or transcript behavior.
- Enabling Sentry for release builds that are not supplied release Sentry configuration.
- Committing a DSN or other local credentials.
- Switching from Telecom to direct routing in the middle of an active session.

## Selected Approach

Use fail-closed debug packaging and explicit, immutable audio-route ownership.

For Sentry, Gradle resolves the existing Voice Agent Sentry settings from secure sources and attaches a validation task to debug APK production and installation. A debug APK cannot be produced when its DSN is blank or its configured trace rate is invalid.

For audio, the foreground call service establishes or rejects Telecom before creating the managed voice session. That result becomes a typed route owner passed into the audio engine. The engine executes only the operations belonging to that owner and releases only resources that owner acquired.

## Sentry Build Contract

### Secure source precedence

Resolve each Voice Agent Sentry setting independently in this order:

1. Existing Gradle/local property, such as `voiceAgentSentryDsn`.
2. Existing process environment variable, such as `VOICE_AGENT_SENTRY_DSN`.
3. The matching key in `~/.config/voice-lab/local.env`.

The supported settings remain:

- `VOICE_AGENT_SENTRY_DSN`
- `VOICE_AGENT_SENTRY_ENVIRONMENT`
- `VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE`

The local environment-file parser accepts blank lines, comments, and plain `KEY=VALUE` assignments. It does not execute shell syntax, expand variables, or log parsed values. Explicit properties and process environment always override the shared local file.

### Validation boundary

Validation applies to tasks that produce, package, install, or otherwise deploy a debug APK. It does not make `gradle help`, source inspection, release-only tasks, or ordinary local unit-test compilation depend on a personal secret.

Before a debug APK task runs:

- the DSN must be nonblank and syntactically usable by the existing Sentry integration;
- the environment must be nonblank; and
- the trace sample rate must parse as a finite number in the inclusive range `0.0..1.0`.

Failure stops the build before APK packaging with a message naming the missing or invalid setting and the supported secure sources. The message never includes the DSN or any other resolved value.

The generated `BuildConfig` fields continue to use the existing names, so runtime observability construction does not gain a second configuration path.

### Secret handling

- Do not copy Sentry values into tracked files.
- Do not print resolved values at normal, info, debug, or failure log levels.
- Do not persist the shared environment file into Gradle build artifacts.
- Tests assert presence and validation behavior with synthetic values only.

## Audio Ownership Model

Introduce an internal route-ownership value with two states:

- `Telecom`: an active self-managed Telecom connection owns focus, mode, communication endpoint, and Bluetooth signaling.
- `DirectFallback`: Telecom establishment failed or timed out before the managed voice session started, so the audio engine owns the direct Android audio operations.

The selected owner is immutable for the managed session. It is supplied when the call factory creates the audio engine and is included in session-start diagnostics.

### Startup sequencing

For a new Voice Agent session:

1. `VoiceAgentCallService` enters microphone foreground-service mode.
2. It resolves launch configuration and begins Telecom setup.
3. `VoiceAgentTelecomAdapter` registers the self-managed account and requests the outgoing call.
4. `VoiceAgentTelecomCallRegistry` exposes an awaitable connection outcome rather than only a synchronous `hasActiveConnection` snapshot.
5. The service waits for an active connection, an explicit rejection, or a bounded timeout.
6. An active connection selects `Telecom`; failure or timeout selects `DirectFallback` and records the concrete reason.
7. Only then does the manager create and start the managed voice session with the selected owner.

The service must not place a Telecom call after a direct-fallback session has begun. Reconnects within the same managed session retain the original owner. Moving from fallback to Telecom requires ending that managed session and starting a new one through the normal ownership handshake.

An existing session reattached after service recreation retains its stored owner. The service does not infer a new owner from a late registry snapshot.

### Telecom-owned engine behavior

When ownership is `Telecom`, `AndroidVoiceAudioEngine`:

- creates `AudioRecord` with the existing voice-communication source;
- creates `AudioTrack` with the existing voice-call/communication attributes;
- starts and stops those streams normally; and
- observes route state for diagnostics without mutating it.

It does not:

- call `requestAudioFocus` or abandon focus;
- set or restore `AudioManager.mode`;
- call `setCommunicationDevice` or `clearCommunicationDevice`;
- call `startBluetoothSco` or `stopBluetoothSco`;
- call Bluetooth headset voice-recognition start or stop; or
- force `AudioRecord.setPreferredDevice`.

Telecom endpoint callbacks remain the authoritative route report.

### Direct-fallback engine behavior

When ownership is `DirectFallback`, the engine retains the existing best-effort direct setup needed to make a non-Telecom session usable. Those operations are isolated behind a focused direct-route controller so they cannot run from the Telecom path.

The direct controller owns:

- audio focus request and release;
- communication mode setup and restoration;
- communication-device selection and clearing;
- compatibility Bluetooth SCO and headset signaling where the platform still requires them; and
- preferred capture-device selection.

The controller records which operations actually succeeded. Cleanup releases only successfully acquired state and remains idempotent.

## State and Ownership Boundaries

`VoiceAgentCallService` owns the pre-session Telecom handshake and the decision to use fallback.

`VoiceAgentCallManager` and `ManagedVoiceCallSession` retain the selected owner so reconnect and service reattachment cannot silently change it.

`DefaultVoiceAgentCallFactory` passes that owner to `AndroidVoiceAudioEngine` rather than letting the engine infer ownership from global Android state.

`AndroidVoiceAudioEngine` owns PCM capture and playback for both modes. Route mutation is delegated only to the direct-route controller and only for `DirectFallback`.

`VoiceAgentTelecomConnection` owns endpoint selection and route callbacks for `Telecom`.

## Failure Handling

### Sentry configuration

- Missing or invalid debug Sentry configuration fails before packaging.
- The error explains how to configure the build without revealing any secret.
- Runtime NoOp observability remains possible for non-debug or test contexts that intentionally do not package a debug APK.

### Telecom establishment

- Registration failure, outgoing-call rejection, and connection timeout are distinct diagnostics.
- Each failure selects direct fallback before the session starts.
- A late Telecom callback after fallback selection is rejected or disconnected; it never gains ownership of the active fallback session.
- Telecom loss after a Telecom-owned session starts degrades or ends the call according to existing call lifecycle behavior. It does not start direct routing underneath a running audio stream.

### Direct routing

- Best-effort failures remain visible as direct-route diagnostics.
- Failed acquisition is not released as though it succeeded.
- Direct-route failure does not mutate Telecom state because a Telecom connection is absent by contract.

## Diagnostics and Observability

Session metadata and local diagnostics include a non-secret owner label: `telecom` or `direct_fallback`.

The Telecom path reports endpoint availability and selected endpoint through existing Telecom events. It no longer emits direct audio-focus, SCO, preferred-device, or Bluetooth voice-recognition acceptance fields because those calls are not made.

The direct fallback path records:

- the reason Telecom was unavailable;
- direct focus acquisition result;
- communication-device and compatibility Bluetooth results; and
- cleanup results for acquired resources.

The installed debug APK must report:

- `sentryDsnConfigured=true`;
- tracing state derived from the validated configured sample rate; and
- successful Sentry propagation creation when the runtime integration is healthy.

## Expected Production Changes

### `app/build.gradle.kts`

- Parse the secure shared environment file without executing it.
- Extend Sentry value resolution with the selected precedence.
- Add fail-closed validation to debug APK packaging and installation tasks.
- Preserve existing `BuildConfig` field names and escaping.

### Telecom lifecycle

- Add an awaitable active/failure outcome to `VoiceAgentTelecomCallRegistry` and the adapter/service boundary.
- Resolve route ownership before `VoiceAgentCallManager.start` creates a session.
- Reject or disconnect late Telecom connections after fallback selection.
- Preserve ownership for reconnect and reattachment.

### Call factory and audio contracts

- Add the typed route owner to managed-session creation.
- Construct `AndroidVoiceAudioEngine` with the resolved owner.
- Extract direct route acquisition and cleanup from PCM stream management.
- Remove every direct focus and routing mutation from the Telecom-owned path.

## Testing

### Gradle configuration tests

- Explicit local/Gradle property overrides process environment and the shared file.
- Process environment overrides the shared file.
- The shared file supplies all three settings for a normal debug APK build.
- Missing DSN fails debug packaging without exposing secret material.
- Blank environment and invalid, non-finite, or out-of-range trace rates fail validation.
- Non-packaging tasks and release-only tasks do not require local debug credentials.
- Synthetic values are escaped safely into `BuildConfig`.

### Ownership and service tests

- Active Telecom connection selects `Telecom` before manager startup.
- Registration failure, rejection, and timeout each select `DirectFallback` once.
- Manager startup cannot occur before ownership resolution.
- A late Telecom connection cannot replace an active fallback owner.
- Reconnect and service reattachment retain the session owner.
- Ending the session disconnects the Telecom call or releases direct resources, but never both.

### Audio engine tests

- Telecom ownership performs zero direct focus, mode, communication-device, SCO, headset voice-recognition, or preferred-device mutations.
- Telecom ownership still creates, starts, stops, and releases PCM streams.
- Direct fallback retains required routing acquisition behavior.
- Direct cleanup releases only resources acquired by that controller and is idempotent.
- No transition or callback can execute both ownership paths for one engine instance.

### Device verification

1. Assemble the universal debug APK without exporting Sentry variables manually.
2. Confirm the build loads the secure shared configuration and does not print it.
3. Install the universal APK on the wireless ADB phone.
4. Start a Bluetooth Voice Agent session.
5. Confirm Telecom becomes active before capture starts.
6. Confirm the trace reports `routeOwner=telecom` and the selected Bluetooth endpoint.
7. Confirm there are no direct audio-focus, SCO, communication-device, preferred-device, or headset voice-recognition calls in the Telecom session.
8. Confirm microphone capture and physical playback work through Bluetooth.
9. Confirm session metadata reports Sentry configured and propagation active.
10. Exercise a controlled Telecom-unavailable test and confirm direct fallback is selected before capture without a late Telecom takeover.

## Success Criteria

- A debug APK cannot be assembled or installed with Voice Agent Sentry disabled accidentally.
- The normal local build consumes the existing secure Voice Lab environment file without manual export steps.
- Telecom is active before a Telecom-owned audio stream starts.
- No active session requests the same focus or Bluetooth route from both Telecom and AudioManager.
- The Bluetooth device continues to capture and play voice audio without the misleading `AUDIOFOCUS_REQUEST_FAILED` or `startVoiceRecognition accepted=false` sequence.
- Direct fallback remains available only when Telecom is conclusively unavailable before session creation.
- Gemini-to-Hermes enforcement remains unchanged and tracked separately.
