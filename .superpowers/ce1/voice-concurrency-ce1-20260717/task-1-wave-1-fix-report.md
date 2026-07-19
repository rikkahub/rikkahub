# Task 1 CE1 Wave 1 Fix Report

## Result

- Status: fixed and focused verification passed.
- Fix commit: `470f087fc1447e1a7f934cceb88fe6c2da1f38d2` (`fix(voice): terminate in-flight call reservations`).
- Scope: all four blocking root causes from `task-1-wave-1-synthesis.json`.

## Root Causes and Fixes

### CE1-T1-001: terminal transition ignored `Starting`

`end()`, `detachForEndAndDrain()`, and `closeNow()` delegated to an `Active`-only detach helper. A reservation blocked in factory creation or session startup therefore remained installed and could publish after the terminal call returned.

`VoiceAgentCallManager` now uses an exhaustive `detachTerminalLocked()` transition. Under the monitor it moves either `Starting` or `Active` to `Idle` and clears the active conversation. Outside the monitor, a detached `Starting` completes `Superseded`; a detached `Active` keeps the existing per-API behavior:

- `end()`: cancel collector, then `session.end()`.
- `detachForEndAndDrain()`: cancel collector, then return the exact session.
- `closeNow()`: cancel collector, then `session.closeNow()`.

No route retirement, session lifecycle call, job cancellation, or deferred completion was added under the monitor.

### CE1-T1-002: reservation-owner cancellation contract was unproved

Added deterministic owner cancellation coverage at three ownership conditions:

- Before factory transfer while inherited predecessor cleanup is blocked: cancellation remains the exact canonical instance, predecessor and incoming leases retire once, factory admission never occurs, and the manager returns to `Idle`.
- After factory creation while `session.start()` is blocked: the exact created session closes once, the owner lease retires once, the reservation completes `Failed`, and a matching waiter wakes and successfully becomes the retry owner.
- After a newer reservation has superseded the canceled owner: the stale exact session closes once without clearing or mutating the newer active slot.

### CE1-T1-003: terminal supersession contract was unproved

Added delayed-continuation races so a matching waiter observes `Failed` first but cannot resume retry logic until a different newer slot is installed:

- A newer `Active` makes the failed matching start return `VoiceAgentManagerStartResult.Superseded` and retire its exact incoming lease once.
- A newer `Starting` makes both the failed matching start and pre-route matcher return terminal supersession; the pre-route result carries the immutable route metadata from the originally awaited reservation.
- `VoiceAgentCallStartup` maps the terminal pre-route result to `Stale` without calling its route resolver and without disturbing the newer active lease.

### CE1-T1-004: inherited predecessor-cleanup barrier contract was under-asserted

The existing blocked predecessor-end test now asserts that the successor is incomplete and the factory has still consumed only the original active call before cleanup release.

A new failure-replay test installs three successive inheriting reservations while predecessor end is blocked. After release, all three receive the same non-copyable primary failure instance, every inherited incoming lease retires exactly once, the predecessor lease retires once, and the factory remains at one admission. This proves no inheritor bypasses the shared failed barrier.

## Files Changed in Fix Commit

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt`
  - Replaced the `Active`-only terminal detach with an exhaustive `Starting`/`Active` transition.
  - Completed detached startup reservations outside the monitor.
  - Preserved the existing `Active` cleanup behavior of all three terminal APIs.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`
  - Added blocked-factory and blocked-session-start terminal regressions for all three APIs.
  - Added reservation-owner cancellation tests at pre-transfer, created-session, and superseded-owner checkpoints.
  - Added newer-`Starting` and newer-`Active` terminal supersession tests.
  - Strengthened successful inherited-barrier serialization and added multi-inheritor failure replay.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt`
  - Added terminal pre-route supersession mapping with zero redundant route resolutions and exact newer-lease preservation.

`VoiceAgentCallStartup.kt` required no production change because its existing `Superseded` mapping already matched the binding contract; the new regression now proves that mapping through a real manager race.

## TDD Evidence

### RED

Command:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest'
```

Result before the production change:

- 35 selected tests executed.
- Exactly 3 failed:
  - `end invalidates starting before and after factory transfer`
  - `detach for drain invalidates starting before and after factory transfer`
  - `close now invalidates starting before and after factory transfer`
- Each failure observed `Started(...)` where terminal `Superseded` was required.
- The new cancellation, terminal-supersession, startup-mapping, and barrier tests passed before the production change, confirming those additions were deterministic contract coverage rather than unrelated failures.
- Gradle result: `BUILD FAILED` as expected.

### GREEN

Command:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest'
```

Result after the production change:

- `VoiceAgentCallManagerTest`: 27 tests, 0 failures, 0 errors.
- `VoiceAgentCallStartupTest`: 8 tests, 0 failures, 0 errors.
- Total: 35 tests, 0 failures, 0 errors.
- Gradle result: `BUILD SUCCESSFUL`.
- `git diff --check`: clean before the fix commit.

## Contract Audit

- First failure remains primary; later cleanup failures retain the existing suppression order.
- Route metadata used by terminal supersession remains the immutable metadata owned by the awaited reservation.
- Exact route cleanup remains once-only across unconsumed leases and created/started sessions.
- Deferred completion and all external lifecycle work occur outside the manager monitor.
- No new dependencies or compatibility state paths were introduced.

## Review Accounting

- Core correctness/API/maintainability/tests: manual pass completed against the binding plan, synthesis, reviewer artifacts, production callers, and final diff.
- Deep cross-file review: completed for manager, startup mapper, lifecycle callers, and route-owned cleanup semantics.
- External review adapter: not available in this repository; subagent review was also explicitly disallowed for this fixer.
- Simplify/AI-slop adapters: not available; manual bounded simplicity pass found no actionable item.
- UI, React/Next, security, and data-integrity adapters: not applicable to the touched concurrency state-machine files.
- CI-equivalent focused verification: passed.

## Residual Concerns

None known. The fix intentionally does not broaden the manager architecture: terminal invalidation is limited to the existing sealed slot, while cancellation, supersession, barrier, cleanup-order, and startup-route contracts are protected by deterministic regressions.

## Post-Fix Stable Review Repair

### Finding

At fix head `470f087fc1447e1a7f934cceb88fe6c2da1f38d2`, the owner catch path published `Idle` and completed the reservation `Failed` before attempting cleanup of the exact current lease/session. This allowed an existing matching waiter or a fresh matching caller to enter retry and consume the factory while the failed or canceled owner still held cleanup responsibility.

### Repair

The shared catch path now performs the required sequence:

1. Attempt cleanup of the exact current unconsumed lease or created session outside the monitor.
2. Preserve the original failure as primary and suppress a distinct cleanup failure in the existing order.
3. Under the monitor, move to `Idle` only if the same reservation or active token is still current.
4. Complete that reservation `Failed` outside the monitor.
5. Rethrow the unchanged primary failure or canonical cancellation.

If a newer slot displaced the failing owner during cleanup, the identity check leaves that newer slot untouched and does not overwrite its already-terminal reservation resolution.

### Regression Tests

- `failed owner keeps matching callers behind blocked lease cleanup`
  - Fails predecessor cleanup before factory transfer.
  - Blocks exact Telecom lease retirement inside the owner catch.
  - Proves an already-waiting matching start and a fresh matching start remain incomplete and no second factory admission occurs before retirement release.
  - After release, proves exact cleanup, unchanged primary failure identity, one retry `Started`, one `Existing`, and one duplicate-lease retirement.
- `cancelled owner keeps matching callers behind blocked session cleanup`
  - Cancels the reservation owner after factory transfer while session start is blocked.
  - Blocks exact created-session close inside the owner catch.
  - Proves the existing waiter and fresh caller remain incomplete and no retry factory admission occurs before close release.
  - After release, proves the canonical cancellation instance is rethrown, the created session closes once, the owner lease retires once, one retry starts, and the other caller reuses it.

The test Telecom call gained optional deterministic retirement-entry/release latches, and the blocking-start test session gained an optional deterministic close barrier. These are test-only controls over real route/session cleanup behavior.

### TDD Evidence

RED command:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest'
```

RED result:

- 37 tests executed.
- Exactly 2 failed: the blocked lease-cleanup and blocked session-cleanup retry-admission regressions.
- Both failed at the assertion that the second factory call must not enter before cleanup release.
- Gradle result: `BUILD FAILED` as expected.

Fresh GREEN command:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest' --rerun-tasks
```

GREEN result:

- All 179 Gradle tasks executed.
- `VoiceAgentCallManagerTest`: 29 tests, 0 failures, 0 errors.
- `VoiceAgentCallStartupTest`: 8 tests, 0 failures, 0 errors.
- Total: 37 tests, 0 failures, 0 errors.
- Gradle result: `BUILD SUCCESSFUL in 1m 21s`.

### Files and Commit

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt`: reordered exact cleanup, identity-guarded slot retirement, and `Failed` completion.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`: added blocked-cleanup admission regressions and deterministic session/factory controls.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt`: extended the shared test Telecom lease with deterministic retirement latches.
- Repair commit: `746a458d34a3c62ff83046312ebe7755a615a8d9` (`fix(voice): finish owner cleanup before retry`).

### Residual Concerns

None known. The repair is a catch-path ordering change only; it adds no manager state, dependency, compatibility branch, or monitor-held external work.

## Wave 2 Follow-Up

Wave 2 found a narrower transition not covered by the wave-1 repair: terminal invalidation or cancellation of a reservation that only inherited an incomplete predecessor-cleanup barrier still exposed barrier-free `Idle`. The wave-2 fix adds a non-publishable cleanup fence that carries the exact deferred to the next reservation, preserving admission ordering and identical failure replay without monitor-held external work.

Four deterministic regressions now cover terminal invalidation and inheriting-owner cancellation against both successful and failed predecessor cleanup. The manager test suite was also split below 1,000 lines, and direct `matchingRoute` replacement/supersession branches now have immutable-route and zero-redundant-work coverage. Full evidence is in `task-1-wave-2-fix-report.md`; the final fix SHA is supplied in the completion handoff.

## Wave 3 Follow-Up

Wave 3 closed the post-`Active` publication window left beyond the wave-1 `Starting` terminal fix. Pending publication now remains part of exact `Active` ownership: terminal or replacement detachment selects `Superseded` under the manager monitor, completes the reservation outside it, and exclusively owns session/collector cleanup. Deterministic terminal and replacement races prove manager owners/waiters cannot report success and production startup maps the same outcomes to `Stale`. The broadened authoritative filter and direct cleanup-fence exit coverage are recorded in `task-1-wave-3-fix-report.md`.
