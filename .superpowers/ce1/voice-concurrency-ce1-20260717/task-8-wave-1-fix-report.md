# CE1 Task 8 Wave 1 Fix Report

## Result

- Status: DONE
- Queue item: `CE1-T8-001` / `android-capture-adapter-contracts-unproved`
- Scope: Only the admitted Android capture-adapter executable coverage gap was fixed. `CE1-T7-007` device-label logging was not changed.
- Commit: `22623daf5cf6e49d76c724591af52b1cd0633886` (`fix(voice): prove direct capture adapter contracts`)

## Root Cause

Task 4 moved permission gating, Android input enumeration, selected-device configuration, communication-device lease ownership, and cleanup into `AndroidDirectCaptureDeviceAdapter`, then removed the only fake operations seam that executed those behaviors. The focused tests substituted `DirectCaptureDeviceCapability` at the controller boundary and tested route preference separately, so every adapter branch could regress while the suite remained green.

The fix introduces a candidate-local action seam in the adapter file. Production creates one `AndroidDirectCaptureDeviceCandidate` per enumerated `AudioDeviceInfo`; the preferred-device and communication-device closures capture that same private platform object. Tests provide action closures instead of platform handles. This executes the adapter's real ordering, selection, failure, ownership, and retirement logic without exposing `AudioDeviceInfo` or recreating `DirectCaptureDeviceOperations`.

## TDD Evidence

### RED

Tests were added before production changes to construct `AndroidDirectCaptureDeviceAdapter` through the intended action seam. The first run also revealed a test-only reference to another file's private `AudioRecord` allocator; that fixture error was corrected without touching production. The repeated exact focused command then failed in `:app:compileDebugUnitTestKotlin` only because the production seam did not exist:

```text
No parameter with name 'hasConnectPermission' found.
No parameter with name 'captureDevices' found.
No parameter with name 'clearCommunicationDevice' found.
Unresolved reference 'AndroidDirectCaptureDeviceCandidate'.
BUILD FAILED
```

### GREEN

After the minimal adapter refactor, the exact focused command exited `0` with `BUILD SUCCESSFUL in 10s`:

```text
./gradlew :app:testDebugUnitTest \
  --tests '*DirectAudioRouteCapabilitiesTest' \
  --tests '*AndroidDirectAudioRouteControllerTest' \
  --tests '*VoiceAudioRouteSelectorTest'
```

JUnit XML evidence:

- `DirectAudioRouteCapabilitiesTest`: 29 tests, 0 skipped, 0 failures, 0 errors.
- `AndroidDirectAudioRouteControllerTest`: 19 tests, 0 skipped, 0 failures, 0 errors.
- `VoiceAudioRouteSelectorTest`: 2 tests, 0 skipped, 0 failures, 0 errors.
- Total: 50 tests, 0 skipped, 0 failures, 0 errors.

## Contract Evidence

| Required behavior | Executable proof |
|---|---|
| Permission denial causes no enumeration or mutation | `capture device permission denial performs no enumeration or mutation` asserts zero enumeration and an empty mutation log. |
| Permission/security and enumeration failures are best-effort | `capture adapter platform failures remain best effort` makes permission and enumeration actions throw and observes `null` without downstream mutation or an escaped failure. |
| The exact selected candidate is configured | `capture adapter configures the exact selected candidate` supplies built-in and Bluetooth candidates and observes only `preferred:7` and `communication:7`. Production binds both closures to the same private `AudioDeviceInfo`. |
| Preferred-device failure remains best-effort | The platform-failure test throws from the preferred action, then proves the same candidate's communication action still succeeds and publishes a lease. |
| Rejected/failed communication selection owns no clear action | `rejected communication device owns no clear action` and the communication-failure branch both observe `null` and no `clear`. |
| Accepted communication selection clears exactly once | `accepted communication device clears exactly once` retires the lease twice and observes one `clear`. |

## Boundary and Regression Evidence

- `AndroidDirectCaptureDeviceAdapter` still implements `DirectCaptureDeviceCapability` directly.
- `AudioDeviceInfo` occurs only in `AndroidDirectCaptureDeviceAdapter.kt`; it is captured only by production candidate closures and never appears in the capability or test boundary.
- No `DirectAudioCaptureDevice`, `DirectAudioCaptureDeviceHandle`, `DirectCaptureDeviceOperations`, recovery cast, or marker-handle path was restored.
- `selectPreferredCaptureRoute` and `VoiceAudioRouteSelectorTest` are unchanged.
- `AndroidDirectAudioRouteController.kt` is unchanged, preserving Task 2 concurrency.
- `DirectAudioRouteCapabilities.kt` and `AndroidDirectAudioRouteControllerTest.kt` are unchanged.
- No dependency was added.
- Device-label logging was not changed.

## Required Scans and Hygiene

The exact Task 4 sleep scan exited `0` with no output:

```text
if rg -n "Thread\.sleep" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectBluetoothCaptureAdapter.kt; then
  exit 1
fi
```

The exact prohibited-boundary scan exited `0` with no output:

```text
if rg -n "VoiceAudioCaptureLifecycle|recorderLock|^internal interface DirectBluetoothHeadset$|^internal interface DirectBluetoothDevice$|\bDirectAudioCaptureDevice\b|\bDirectAudioCaptureDeviceHandle\b|\bDirectCaptureDeviceOperations\b|requireAndroidHeadset|requireBluetoothDevice|requireAudioDeviceInfo" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio; then
  exit 1
fi
```

`git diff --check` exited `0` with no output.

## Self-Review

- Verdict: clean; no in-scope findings remained.
- Core correctness/API/tests/maintainability: manually reviewed against the binding brief and CE1 synthesis.
- Permission/security: manually reviewed; explicit gating remains before enumeration and mutation, and permission/action exceptions remain contained.
- External Codex, simplify, and AI-slop adapters: skipped because this repository has no bundled adapter files at the documented paths; a manual deep pass found no issue.
- UI/React/data adapters: not applicable to the two Kotlin audio files.
- CI source: the exact focused Gradle command passed.

## Files Changed

- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectCaptureDeviceAdapter.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilitiesTest.kt`
- `.superpowers/sdd/task-4-report.md`
- `.superpowers/ce1/voice-concurrency-ce1-20260717/task-8-wave-1-fix-report.md`

## Concerns

- None. The focused build emitted the repository's pre-existing unresolved `ExperimentalNavigation3Api` opt-in warning; it did not affect compilation or test execution.
