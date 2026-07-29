# Current Result

- Status: DONE
- Current code commit: `25443f2c4e99cbe43b065dc1a553ca3e83691773`
  (`fix(voice): keep reducer session state immutable`)
- Foundation code commit: `e1eccab0f0eb27bf538289d1f0ccf3e1b0072741`
  (`refactor(voice): define call orchestration states`)
- Summary: The pure call reducer now owns an immutable `VoiceAgentUiState` snapshot in both startup-ready outcomes and
  active state. Matching active starts consult only that snapshot. Exact `SessionStateChanged` events replace the snapshot,
  while stale events remain inert. The reducer no longer reads `call.session.state.value` or any other mutable resource.
- Review closure: The deterministic regression binds identical state/event inputs to identical effects despite an
  out-of-band session `StateFlow` mutation. The idle-event table now asserts exact effects, and current `FailedClean` and
  `Cancelled` startup completions have direct exact state/result/effect assertions.

# Tests

- RED: After adding the deterministic regression before production changes, the focused command ran 20 tests and failed
  exactly `matching active reduction is unchanged by mutable session flow`. The same reducer state/event emitted a
  `Reconnect` only after the backing session flow changed, proving the reducer depended on mutable resource state.
- Focused GREEN: After adding immutable snapshots and removing the session-flow read, the same command completed with
  `BUILD SUCCESSFUL in 14s`. The generated XML reports 20 tests, 0 skipped, 0 failures, and 0 errors.

  ```text
  ./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallStateMachineTest'
  ```

- `git diff --check` passed, neither changed Kotlin file contains a line longer than 120 characters, and
  `rg -n "call\\.session\\.state|session\\.state\\.value" VoiceAgentCallStateMachine.kt` returned no matches.

# Files Changed

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStateMachine.kt` — adds immutable session snapshots to
  `VoiceAgentStartOutcome.Ready` and `VoiceAgentCallState.Active`, uses the snapshot for matching-start reconnect policy,
  and updates it only from an exact `SessionStateChanged` event.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStateMachineTest.kt` — adds the deterministic purity
  regression and strengthens idle, clean-failure, and cancellation transition assertions.

# Concerns

- No known Task 3 correctness concerns.
- The focused build retains the repository's pre-existing unresolved `ExperimentalNavigation3Api` opt-in warning. This
  task does not touch navigation; the warning did not affect compilation or tests and remains recorded for final triage.

# Attempt Appendix

## Initial Task 3 result

- Code commit `e1eccab0f0eb27bf538289d1f0ccf3e1b0072741` introduced the complete state/effect protocol and original reducer.
- Report commit `9533cf2368b8d7a1a74edf439acca7103509bbec` recorded the initial RED compilation failure and a 19-test focused GREEN
  run. That result was superseded when review found the matching-active branch read `call.session.state.value`, so identical
  reducer inputs could produce different effects after mutable session state changed.
