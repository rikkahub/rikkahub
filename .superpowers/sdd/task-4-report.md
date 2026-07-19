## Current Result

- Status: DONE
- Commit: `22623daf5cf6e49d76c724591af52b1cd0633886` (`fix(voice): prove direct capture adapter contracts`)
- Summary: Restored executable proof for the Android capture-device boundary without restoring a platform handle or domain operations abstraction. `AndroidDirectCaptureDeviceAdapter` now owns a candidate-local action seam whose production closures retain each private `AudioDeviceInfo`; focused tests execute the adapter and prove permission short-circuiting, exact selected-candidate actions, accepted-only cleanup ownership, exactly-once clearing, and best-effort platform failures.

## Tests

- RED: The exact focused command failed in `:app:compileDebugUnitTestKotlin` after the focused adapter tests were added. After correcting a test-only `AudioRecord` allocator reference, the expected RED was solely the missing adapter seam: `No parameter with name 'hasConnectPermission'`, `No parameter with name 'captureDevices'`, `No parameter with name 'clearCommunicationDevice'`, and unresolved `AndroidDirectCaptureDeviceCandidate`.
- Focused GREEN: The exact Task 4 command passed with `BUILD SUCCESSFUL in 10s`. The generated XML reports 50 selected tests, 0 skipped, 0 failures, and 0 errors: 29 `DirectAudioRouteCapabilitiesTest`, 19 `AndroidDirectAudioRouteControllerTest`, and 2 `VoiceAudioRouteSelectorTest`.

  ```text
  ./gradlew :app:testDebugUnitTest \
    --tests '*DirectAudioRouteCapabilitiesTest' \
    --tests '*AndroidDirectAudioRouteControllerTest' \
    --tests '*VoiceAudioRouteSelectorTest'
  ```

- Prohibited-boundary scans: Both exact Task 4 Step 5 scans exited `0` with no matches. No prohibited sleeps, lifecycle/recorder markers, Bluetooth marker types, capture-device handles/operations, or recovery casts exist in the scanned production and audio-test paths.
- Boundary review: `AudioDeviceInfo` appears only in `AndroidDirectCaptureDeviceAdapter.kt`. `AndroidDirectAudioRouteController.kt`, `VoiceAudioRouteSelector.kt`, `VoiceAudioRouteSelectorTest.kt`, `DirectAudioRouteCapabilities.kt`, and `AndroidDirectAudioRouteControllerTest.kt` are unchanged from the task head.
- Diff hygiene: `git diff --check` exited `0`.
- Full fix evidence: `.superpowers/ce1/voice-concurrency-ce1-20260717/task-8-wave-1-fix-report.md`.

## Files Changed

- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectCaptureDeviceAdapter.kt` — adds the candidate-local action seam while retaining every `AudioDeviceInfo` inside production closures owned by the Android adapter.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilitiesTest.kt` — adds focused adapter behavioral coverage for permission denial/failure, enumeration failure, exact selected-candidate configuration, preferred/communication failure containment, accepted-only lease ownership, and exactly-once cleanup.
- `.superpowers/sdd/task-4-report.md` — records the CE1 fix result and archives the superseded original result below.
- `.superpowers/ce1/voice-concurrency-ce1-20260717/task-8-wave-1-fix-report.md` — records complete CE1-T8-001 fix evidence.

## Concerns

- None. The focused build retained the repository's pre-existing unresolved `ExperimentalNavigation3Api` opt-in warning; it did not affect compilation or tests.

## Attempt Appendix

### Attempt 1 — Original Task 4 implementation (superseded by CE1-T8-001 fix)

#### Prior Current Result

- Status: DONE
- Commit: `36bc327e` (`refactor(voice): type direct capture adapters`)
- Summary: Added `AndroidDirectCaptureDeviceAdapter` as the complete Android capture-device boundary. Android `AudioDeviceInfo` values remain private to the adapter; the shared capability file now exposes only `DirectCaptureDeviceCapability`. Controller tests fake that capability directly, while route-selection policy remains in the pure selector tests.

#### Prior Tests

- RED: After removing the obsolete operation/handle fake and its contract-only tests, the exact focused command failed in `:app:compileDebugUnitTestKotlin` because `AndroidDirectAudioRouteControllerTest` still referenced the removed `FakeCaptureDeviceOperations`. The compiler reported `Unresolved reference 'FakeCaptureDeviceOperations'` plus the dependent obsolete fake fields. This was the expected old-contract compile shape before production changed.
- Focused GREEN: The exact Task 4 command passed with `BUILD SUCCESSFUL in 11s`; `:app:testDebugUnitTest` executed and all selected tests passed.
- Prohibited-boundary scans: Both exact Step 5 scans exited `0` with no matches.
- Diff hygiene: `git diff --check` exited `0` before commit.

#### Prior Files Changed

- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectCaptureDeviceAdapter.kt` — owned permission gating, Android input enumeration, domain mapping and selection, recorder preference, communication-device selection, and idempotent cleanup.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt` — wired the Android adapter and removed opaque capture-device operations, handles, and recovery casts.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilitiesTest.kt` — removed contract-only capture-device operation/handle tests and fakes.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt` — tested capture-device best-effort failure through a direct `DirectCaptureDeviceCapability` fake.

#### Prior Concerns

- None recorded. The later stable review identified the residual executable coverage gap now fixed by CE1-T8-001.
