# Task 1 CE1 Wave 2 Fix Report

## Result

- Status: all three blocking wave-2 root causes fixed; focused verification passed.
- Review baseline: `746a458d34a3c62ff83046312ebe7755a615a8d9`.
- Fix commit: this report is part of the single coherent wave-2 fix commit. The completion handoff supplies the final full SHA because a commit cannot self-contain its own hash.

## Root Causes and Changes

### CE1-T1-005: terminal retirement discarded an inherited cleanup barrier

`detachTerminalLocked()` replaced every `Starting` slot with `Idle`, even when that reservation carried an incomplete predecessor-cleanup deferred. The owner catch path made the same transition when a reservation was canceled while awaiting a barrier owned by another reservation. A fresh start could therefore take the barrier-free `Idle` branch and enter `factory.create()` before the detached active session finished ending.

The manager now has a private, non-publishable `CleanupFence` slot containing only the exact shared `CompletableDeferred<Result<Unit>>`:

- Terminal invalidation replaces a barrier-carrying `Starting` with the fence and completes the invalidated reservation `Superseded` outside the monitor.
- Owner cancellation preserves the fence only while its inherited barrier is incomplete, after exact current-lease/session cleanup and before completing that reservation `Failed` outside the monitor.
- The next reservation atomically replaces the fence with a new `Starting` that references the same deferred by identity.
- `matchingRoute` treats a cleanup fence as no installed matching route, but the subsequent `start` still inherits and awaits the fence before factory transfer.
- A second terminal action leaves an existing fence intact.
- No await, deferred completion, route retirement, factory call, session lifecycle call, job launch, or job cancellation was added under the manager monitor.

The invalidated or canceled reservation cannot publish because it no longer owns the slot. Successful cleanup permits only the fresh reservation to enter the factory; failed cleanup reaches every inheritor as the same non-copyable throwable instance and prevents all factory/start/publication work.

### CE1-T1-006: manager test file exceeded 1,000 lines

The test suite was split by responsibility:

- `VoiceAgentCallManagerTest.kt` retains manager startup, matching, cancellation, supersession, cleanup-order, and lifecycle behavior.
- `VoiceAgentCallManagerBarrierTest.kt` owns predecessor serialization and the new terminal/cancellation fence regressions.
- `VoiceAgentCallManagerTestFixtures.kt` owns package-internal deterministic sessions, factories, dispatcher gates, launch config, exceptions, and Telecom lease instrumentation shared by the focused classes and startup tests.

No compatibility wrappers were introduced. All 43 tests remain discovered after the split.

Final touched-test line counts:

- `VoiceAgentCallManagerTest.kt`: 981
- `VoiceAgentCallManagerBarrierTest.kt`: 220
- `VoiceAgentCallManagerTestFixtures.kt`: 353
- `VoiceAgentCallStartupTest.kt`: 328

### CE1-T1-007: direct matching-route transitions lacked proof

Added deterministic coverage for both unproved branches:

- A pre-route matcher awaits a failed owner, follows a matching replacement while its second factory call is blocked, then returns `Existing` with the replacement lease's exact immutable metadata after publication.
- A pre-route matcher directly observes its currently awaited reservation become `Superseded` and returns the original reservation route. A concurrent real `VoiceAgentCallStartup` maps that same transition to `Stale` with zero resolver calls, while the stale factory-created session has zero start calls and closes exactly once after release.

## TDD Evidence

### RED

Command:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallManager*Test' \
  --tests '*VoiceAgentCallStartupTest' \
  --rerun-tasks
```

Result before the production change:

- 43 tests executed.
- Exactly four failed:
  - `terminal invalidation preserves inherited predecessor cleanup until success`
  - `terminal invalidation replays inherited predecessor cleanup failure to fresh start`
  - `inheriting owner cancellation preserves predecessor cleanup until success`
  - `inheriting owner cancellation replays predecessor cleanup failure to fresh start`
- Each failure occurred at the assertion that the fresh reservation must remain incomplete with only the original factory admission before predecessor-end release.
- The two new `matchingRoute` tests passed before the production change; those findings were coverage gaps over already-correct branches.
- Gradle result: `BUILD FAILED` as expected.

### GREEN

Final command after the production fix and structural split:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallManager*Test' \
  --tests '*VoiceAgentCallStartupTest' \
  --rerun-tasks
```

Result:

- All 179 Gradle tasks executed.
- `VoiceAgentCallManagerTest`: 30 tests, 0 failures, 0 errors.
- `VoiceAgentCallManagerBarrierTest`: 5 tests, 0 failures, 0 errors.
- `VoiceAgentCallStartupTest`: 8 tests, 0 failures, 0 errors.
- Total: 43 tests, 0 failures, 0 errors.
- Gradle result: `BUILD SUCCESSFUL in 1m 21s`.
- `git diff --check`: clean.

## Files Changed

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerBarrierTest.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTestFixtures.kt`
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt`
- `.superpowers/sdd/task-1-report.md`
- `.superpowers/ce1/voice-concurrency-ce1-20260717/task-1-wave-1-fix-report.md`
- `.superpowers/ce1/voice-concurrency-ce1-20260717/task-1-wave-2-fix-report.md`

## Contract Audit

- Exact cleanup and cancellation identity rules remain unchanged.
- A newer slot cannot be cleared by an older owner; fence replacement and owner retirement remain identity-guarded.
- The first failure remains primary; identical cleanup failure replay uses the original `Result` and throwable instance.
- Terminal/canceled reservations are non-publishable and their deferred completion remains outside the monitor.
- No new dependency, compatibility state path, route resolution, or public API was added.

## Concerns

None known.

## Post-Fix Stable Review Repair

### Finding

The initial cleanup-fence catch path checked `predecessorCleanup.isCompleted` only after exact lease/session cleanup. That cleanup may block. If cancellation started while the inherited barrier was incomplete and the predecessor completed it with failure during blocked cleanup, the later check discarded the barrier and published `Idle`. A matching waiter then woke from the canceled reservation's `Failed` resolution and entered a barrier-free retry.

### Narrow Repair

The catch path now performs a read-only pre-cleanup snapshot under the monitor:

- Verify the exact reservation or its active token still owns the current slot.
- Capture the exact inherited deferred only if it is incomplete at that point.
- Perform the existing exact lease/session cleanup outside the monitor.
- In the existing post-cleanup identity-guarded transition, install `CleanupFence` from the captured deferred regardless of whether it completed during cleanup.
- If any newer slot replaced the owner, leave it unchanged.
- Complete `Failed` only after cleanup and outside the monitor, preserving canonical cancellation and primary/suppressed failure identity.

No external work, deferred completion, or state mutation was added to the pre-cleanup monitor section.

### Deterministic Regression

`cancellation preserves barrier that fails during blocked lease retirement` establishes this ordering:

1. Active A is replaced by cleanup owner B, which blocks in `A.end()`.
2. Inheriting owner C is canceled while awaiting B's shared barrier.
3. C's exact Telecom lease retirement enters and blocks.
4. A's end is released and completes the shared barrier with a non-copyable failure while C cleanup remains blocked.
5. A matching fresh caller awaits C's still-uncompleted reservation resolution.
6. Retirement is released.

The assertions prove C rethrows the exact canonical cancellation, the fresh caller receives the same predecessor failure instance, the original factory admission count stays at one, and every involved lease retires exactly once.

### RED/GREEN Evidence

Fresh RED command:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallManager*Test' \
  --tests '*VoiceAgentCallStartupTest' \
  --rerun-tasks
```

- 44 tests executed; exactly the new regression failed.
- Failure: the fresh waiter returned a started result instead of the exact predecessor failure after cleanup release.
- `BUILD FAILED in 2m 27s`.

Fresh GREEN command: identical to RED.

- All 179 Gradle tasks executed.
- `VoiceAgentCallManagerTest`: 30 tests, 0 failures, 0 errors.
- `VoiceAgentCallManagerBarrierTest`: 6 tests, 0 failures, 0 errors.
- `VoiceAgentCallStartupTest`: 8 tests, 0 failures, 0 errors.
- Total: 44 tests, 0 failures, 0 errors.
- `BUILD SUCCESSFUL in 1m 33s`.
- `git diff --check`: clean.

Final `VoiceAgentCallManagerBarrierTest.kt` size: 276 lines.

### Commit and Concerns

- This report is included in the stable-review repair commit; the completion handoff supplies its full SHA.
- No known residual concerns.

## Wave 3 Follow-Up

Wave 3 preserved the cleanup-fence design and added direct proof for both early exits introduced here. While a successful or failed predecessor cleanup is blocked, `matchingRoute` returns `NoMatch` without consuming the fence, a second terminal API leaves the fence installed, and the next start remains gated before receiving the exact result or throwable. The authoritative Task 1 filter is now `*VoiceAgentCallManager*Test`, so the split barrier suite is included in every focused run. Full evidence is in `task-1-wave-3-fix-report.md`.
