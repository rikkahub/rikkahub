# Final Lifecycle Fix Report

Date: 2026-07-15
Scope: final-review findings I-1 through I-4
Implementation commit: `3cb95a00` (`fix(voice): close lifecycle retirement gaps`)

## Phase 1–3 Root-Cause Analysis

### I-1 — Preserved Telecom session lost its call

Trace: a newly created session can publish `VoiceSessionStatus.Error`; the service then requested failed-start teardown with `preserveSession = true`. Telecom retirement had later been added unconditionally to that teardown, while `VoiceAgentCallStartup` correctly reused the matching managed session's immutable owner and skipped route resolution. The retry therefore retained `Telecom` identity after its only live Telecom call had been disconnected.

Working pattern: the committed ownership design says reconnect and reattachment retain the original owner and do not start a new Telecom attempt. The startup barrier already implements that policy.

Confirmed minimal hypothesis: preserve both objects when the matching session is Telecom-owned and the singleton registry still owns its live call. If the Telecom call is already absent, do not preserve the session; close it so retry must resolve ownership again. DirectFallback preservation remains unchanged apart from cleaning any impossible stray Telecom state.

### I-2 — Throwing supersession orphaned the replacement attempt

Trace: `beginAttempt()` installed the replacement pending record and made it current under the registry lock, then invoked the previous active call's external `disconnectFromApp()` outside the lock. A framework retirement exception escaped before `beginAttempt()` returned. The resolver's `try` began only after that call, so it had neither the replacement ID nor a cleanup opportunity. The replacement record remained pending and unacknowledged.

Working pattern: resolver cancellation and stale-start cleanup already carry the exact attempt ID, publish a typed terminal failure, then await/acknowledge that exact record in `NonCancellable` context.

Confirmed minimal hypothesis: if previous-call supersession throws, terminalize the exact replacement as `telecom_supersession_cleanup_failed` and throw a typed internal exception carrying its ID and failure. The resolver catches only that type before registration/placement, awaits and acknowledges the terminal record, and returns contained DirectFallback. This leaves no new connection, no live unowned call, and no retained attempt.

### I-3 — Telecom retirement aborted independent service cleanup

Trace: explicit end, failed-start teardown, and `onDestroy` were linear sequences. `disconnectActive()` intentionally propagates a framework retirement exception after registry state is cleared. That first exception skipped `endAndDrain`, manager close/status/foreground work, scope cancellation, or `super.onDestroy`, depending on the caller.

Working pattern: stale-start cleanup already executes independent retirement and acknowledgement stages, preserving the first error and attaching later errors as suppressed.

Confirmed minimal hypothesis: use shared synchronous and suspending ordered cleanup runners. Every stage executes exactly once; the first throwable is primary; distinct later throwables are suppressed in stage order; the primary is rethrown only after all stages. The service now uses the runner for explicit end (including playback drain), failed start, empty end, and destruction, with superclass destruction as an independent final stage.

### I-4 — Autonomous capture termination skipped route cleanup

Trace: read exception, invalid/negative read, and PCM callback failure all ended the capture coroutine. Its `finally` stopped/released the recorder and cleared engine state, but `routeController.afterCapture()` existed only in explicit `stopCapture()`. A DirectFallback controller could therefore retain acquired mode/device/SCO/headset state until a later reconnect/end/release. The callback path invalidated the generation but did not own a route-retirement action.

Working pattern: Telecom connection retirement uses a one-shot owner and makes competing callers wait for the winning retirement to complete. Engine capture already has immutable generations and serialized callback/transition boundaries.

Confirmed minimal hypothesis: give each capture generation one route lease shared by the autonomous loop, `stopCapture`, and `release`. The winning caller invokes `afterCapture()` once; competitors wait for that retirement to finish, so controller close or later mutation cannot overtake it. A production-used pure capture-loop seam owns all three autonomous terminal branches and always invokes generation retirement in `finally`.

## TDD Evidence

### I-1

RED:

```text
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallStartupTest'
```

Result: `BUILD FAILED in 2s`; compilation failed on absent `voiceAgentFailedStartCleanupPlan`. The new integration cases covered live-call preservation and missing-call forced re-resolution.

GREEN:

```text
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallStartupTest' \
  --tests '*VoiceAgentCallServicePolicyTest'
```

Result: `BUILD SUCCESSFUL in 15s`.

### I-2

RED:

```text
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentTelecomCallRegistryTest' \
  --tests '*VoiceAgentAudioRouteResolverTest'
```

Result: `BUILD FAILED in 6s`; compilation failed on absent `VoiceAgentTelecomAttemptStartException` and its exact attempt/failure fields.

GREEN: same command.

Result: `BUILD SUCCESSFUL in 9s`. The resolver regression asserts exact typed failure, zero gateway registration calls, zero placement calls, no active call, and an empty attempt map.

### I-3

RED:

```text
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallServiceCleanupTest'
```

Result: `BUILD FAILED in 4s`; compilation failed on absent synchronous and suspending cleanup runners.

GREEN:

```text
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallServiceCleanupTest' \
  --tests '*VoiceAgentTelecomRetirementTest' \
  --tests '*VoiceAgentCallStartupTest'
```

Result: `BUILD SUCCESSFUL in 10s`. Tests use a throwing `VoiceAgentTelecomCall` and assert exact stage order, exactly-once drain/manager/status/foreground/scope/super work, primary identity, and suppressed error order.

### I-4

Initial RED:

```text
./gradlew :app:testDebugUnitTest --tests '*VoiceAudioRouteControllerTest'
```

Result: `BUILD FAILED in 2s`; compilation failed on absent `VoiceAudioCaptureRouteLease` and `runVoiceAudioCaptureLoop` production seams.

Initial GREEN:

```text
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*AndroidDirectAudioRouteControllerTest'
```

Result: `BUILD SUCCESSFUL in 10s` for read throw, negative read, PCM callback throw, exact-once retirement, and no later read.

Race RED:

```text
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAudioRouteControllerTest.stop or release waits*'
```

Result: `BUILD FAILED in 5s`; the explicit competitor returned before the autonomous `afterCapture` callback completed.

Final GREEN:

```text
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*AndroidDirectAudioRouteControllerTest'
```

Result: `BUILD SUCCESSFUL in 7s`. Competing stop/release retirement now waits uninterruptibly for the one autonomous retirement owner to finish.

## Code Decisions

- I-1 preserves a matching Telecom session only while the registry still owns its live call. This follows the existing reconnect intent and avoids silently changing owner mid-session. Missing Telecom state closes the managed session so retry performs the normal pre-session handshake.
- I-2 uses a typed internal exception only as the synchronous handoff from registry to resolver. The user-visible startup result remains a contained typed DirectFallback resolution; no new Telecom call is requested after supersession cleanup failure.
- I-3 centralizes only error precedence and stage continuation. It does not change drain implementation, status values, foreground policy, or session methods.
- I-4 keeps PCM capture and playback shared. It moves no routing operation into the Telecom path and does not change playback, transcript, announcement, Gemini/Hermes, Sentry, or debug-injection contracts.

## Focused Verification

Combined covering suite:

```text
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallStartupTest' \
  --tests '*VoiceAgentCallServicePolicyTest' \
  --tests '*VoiceAgentCallServiceCleanupTest' \
  --tests '*VoiceAgentTelecomCallRegistryTest' \
  --tests '*VoiceAgentTelecomBoundaryTest' \
  --tests '*VoiceAgentTelecomRetirementTest' \
  --tests '*VoiceAgentAudioRouteResolverTest' \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*AndroidDirectAudioRouteControllerTest' \
  --tests '*VoiceAudioFocusPolicyTest' \
  --tests '*VoiceAudioRouteSelectorTest' \
  --tests '*VoicePlaybackWriterTest' \
  --tests '*PlaybackEventDispatcherTest'
```

Result: `BUILD SUCCESSFUL in 5s`; 110 tests, zero skipped, failures, or errors.

Static ownership checks:

- No direct focus, mode/device, SCO, headset voice-recognition, or preferred-recorder mutation name occurs in `AndroidVoiceAudioEngine.kt`.
- No `manager.start(` occurs in `VoiceAgentCallService.kt`.
- No no-argument `startCall()` occurs in Voice Agent production or tests.
- `git diff --check` passed before the implementation commit.

## Deferred Verification and Concerns

- Per controller instruction, the full uncached app unit suite, Sentry contract, APK assembly/package checks, and device verification were not run in this wave.
- Gradle continued to print the pre-existing unresolved `ExperimentalNavigation3Api` opt-in warning during compilation; it did not fail focused verification.
- Real-device Telecom/Bluetooth ordering remains the committed Task 7 follow-up after controller post-review verification.
