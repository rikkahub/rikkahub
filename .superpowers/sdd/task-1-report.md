## Current Result
- Status: DONE
- Commits:
  - 55ebb7c4 refactor(voice): reserve call startup outside monitor
  - 44286c0c fix(voice): make stale cleanup exact once
  - c2ffe06c fix(voice): preserve waiter cancellation identity
  - 5d818a16 fix(voice): keep failed route matching retryable
- Summary: Added suspending call-start reservations and pre-route matching so duplicate callers reuse one published session without blocking the manager monitor. Stable-review cleanup records each stale lease/session cleanup attempt before invoking it, preventing catch-side retries and preserving the original cleanup failure. Matching-waiter cancellation ignores an identical retirement throwable so self-suppression cannot replace the canonical cancellation. `matchingRoute` now preserves the complete slot snapshot so a failed matching chain ending in `Idle` remains retryable `NoMatch`, while genuinely newer nonmatching ownership remains `Superseded`.
## Tests
- RED: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest'` failed during test compilation as expected because `VoiceAgentManagerStartResult`, `VoiceAgentRouteMatchResult`, and `matchingRoute` did not exist.
- GREEN: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest'` passed with 20 selected tests and `BUILD SUCCESSFUL`.
- Stable-review RED: With the cleanup-attempt fix reverted and the failure-injecting regressions present, the focused command ran 23 tests and failed only the stale unconsumed-lease, created-session, and started-session exact-once tests.
- Stable-review GREEN: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest'` passed with 23 selected tests (16 manager, 7 startup), zero failures/errors, and `BUILD SUCCESSFUL`.
- Stable re-review RED: The focused command ran 24 tests and failed only `cancelled matching waiter ignores self suppression from exact retirement failure`; the canonical cancellation was replaced by `IllegalArgumentException: Self-suppression not permitted`.
- Stable re-review GREEN: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest'` passed with 24 selected tests (17 manager, 7 startup), zero failures/errors, and `BUILD SUCCESSFUL`.
- Third stable-review RED: The focused command ran 25 tests and failed only `matching route failure chain ending idle remains retryable`; on failure-chain attempt 9, `matchingRoute` returned `Superseded` instead of retryable `NoMatch` after all matching reservations retired to `Idle`.
- Third stable-review GREEN: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest'` passed with 25 selected tests (18 manager, 7 startup), zero failures/errors, and `BUILD SUCCESSFUL`.
## Files Changed
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt`: owns sealed call-slot reservations, deferred publication results, suspending start/match APIs, full-slot matching snapshots, exact lease cleanup, monitor-safe session snapshots, exact-once stale cleanup-attempt bookkeeping, and cancellation-safe suppression identity guards.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartup.kt`: maps route-match and manager-start results directly to service-level Started or Stale results.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`: verifies suspended matching starts, non-blocking status updates, exact published-route reuse, failure-chain retryability with exact lease retirement, waiter cancellation identity including identical retirement failures, and single-invocation cleanup failure semantics for stale unconsumed, created, and started resources.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt`: verifies startup mapping through the new suspending route-match contract.
## Concerns
- none

## CE1 Wave 2 Repair

### Result

- Status: DONE.
- Review baseline: `746a458d34a3c62ff83046312ebe7755a615a8d9`.
- Commit: this report is included in the single wave-2 fix commit; its full SHA is supplied in the completion handoff because a Git commit cannot contain its own final hash.

### Summary

- Added a non-publishable `CleanupFence` slot that preserves the exact inherited predecessor-cleanup deferred across terminal invalidation and cancellation while it is incomplete.
- Fresh reservations replace the fence by identity and await the same result before factory transfer; failed cleanup is rethrown as the exact same throwable.
- Added direct `matchingRoute` coverage for Failed -> matching Published and directly awaited Superseded, including immutable route reuse, zero redundant resolver calls, and zero redundant session starts.
- Split barrier behavior and reusable deterministic fixtures out of the former 1,228-line manager test file. All touched voice-call test files are now below 1,000 lines.

### TDD and Verification

- RED: the fresh focused `--rerun-tasks` command executed 43 tests and failed exactly four new regressions: terminal invalidation and inheriting-owner cancellation, each with successful and failed predecessor cleanup. Both failures admitted the fresh reservation before the blocked predecessor end was released.
- Coverage additions for the two `matchingRoute` branches passed before the production change, confirming missing proof rather than a second production defect.
- GREEN: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest' --rerun-tasks` executed all 179 Gradle tasks and passed 43 tests (30 manager, 5 barrier, 8 startup), zero failures/errors, `BUILD SUCCESSFUL in 1m 21s`.
- `git diff --check`: clean.

### Files

- `VoiceAgentCallManager.kt`: cleanup-fence state and exact barrier inheritance.
- `VoiceAgentCallManagerTest.kt`: matching-route regressions and retained manager behavior tests (981 lines).
- `VoiceAgentCallManagerBarrierTest.kt`: focused predecessor-barrier tests (220 lines).
- `VoiceAgentCallManagerTestFixtures.kt`: package-internal deterministic fixtures (353 lines).
- `VoiceAgentCallStartupTest.kt`: shared Telecom lease fixture removed; behavior unchanged (328 lines).

### Concerns

- None known.

## Attempt Appendix

### Stable review fix: exact-once stale cleanup
- Reviewer issue: Explicit stale-path `retire()`/`closeNow()` calls could throw into the shared catch, which then invoked the same cleanup a second time. When both attempts threw the same object, `addSuppressed` attempted self-suppression and replaced/corrupted the primary failure.
- Fix: Mark the unconsumed lease or created session cleanup as attempted immediately before every explicit stale cleanup call. The shared catch cleans only resources whose cleanup has not already been attempted and never suppresses a throwable onto itself.
- Tests: Added failure-injecting concurrency regressions for stale unconsumed leases, stale created sessions, and stale started sessions. Each asserts the exact thrown instance stays primary, has no suppressed self-reference, and cleanup is invoked once.
- Commit: `44286c0c fix(voice): make stale cleanup exact once`.

### Stable re-review fix: matching-waiter cancellation identity
- Reviewer issue: Matching-waiter cancellation unconditionally suppressed a duplicate-lease retirement failure. If retirement threw the exact canonical `CancellationException`, `addSuppressed` threw `IllegalArgumentException` and replaced the cancellation identity.
- Fix: Filter out a retirement throwable identical to the caught cancellation before calling `addSuppressed`, matching the stale-cleanup identity guard.
- Tests: Added a blocked-owner matching-waiter regression whose duplicate lease retirement throws the exact cancellation instance. It asserts one retirement call, the same cancellation instance with no suppressed self-reference, and an unchanged pending owner/reservation that publishes normally after release.
- Commit: `c2ffe06c fix(voice): preserve waiter cancellation identity`.

### Third stable-review fix: failed matching chain remains retryable
- Reviewer issue: The top of `matchingRoute` projected both `Idle` and nonmatching `Active`/`Starting` slots to `null`. Once `awaitedRoute` was populated, a matching failure chain that reached `Idle` could therefore return terminal `Superseded` instead of retryable `NoMatch`.
- Fix: Snapshot the complete `CallSlot` on every loop. Branch `Idle` directly to `NoMatch`, follow matching `Starting`/`Active` ownership, and return `Superseded` only when a prior matching reservation was awaited and the current complete slot is genuinely newer and nonmatching.
- Tests: Added 50 concurrent failure-chain attempts with one blocked owner and eight matching retry owners. The regression asserts `NoMatch`, exact primary factory failures, one retirement per route lease, the expected factory-consumption count, and no surviving active conversation or UI state.
- Commit: `5d818a16 fix(voice): keep failed route matching retryable`.

### CE1 wave 1 fix: terminal invalidation and concurrency contract coverage
- Synthesized root causes: terminal APIs ignored `Starting`; reservation-owner cancellation checkpoints were unproved; terminal supersession against newer `Starting`/`Active` was unproved; inherited predecessor-cleanup serialization and failure replay were insufficiently asserted.
- Production fix: `end()`, `detachForEndAndDrain()`, and `closeNow()` now use one exhaustive locked terminal transition over `Idle`, `Starting`, and `Active`. A detached `Starting` moves to `Idle` atomically and completes `Superseded` outside the monitor. Each detached `Active` retains its prior collector/session behavior.
- Terminal tests: Every terminal API is exercised while factory creation is blocked and while `session.start()` is blocked. Owners and matching waiters return terminal `Superseded`; exact owner/waiter leases retire once; created sessions close once; no call publishes afterward.
- Cancellation tests: Deterministic cancellation before factory transfer, after factory creation, and after supersession proves canonical cancellation identity, `Failed` wake-up/retry for a matching waiter, exact lease/session cleanup, idle state, and preservation of a newer active slot.
- Supersession/startup tests: Delayed retry continuations prove terminal manager and pre-route supersession for newer `Starting` and `Active` slots. Startup maps the immutable awaited route to `Stale` without invoking route resolution again.
- Barrier tests: The successful inherited-barrier test now proves no successor factory admission before predecessor cleanup release. A failure variant passes one shared cleanup failure through three inheritors, with the exact failure replayed, one retirement per lease, and no successor factory admission.
- RED: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest'` ran 35 tests and failed exactly the three new terminal regressions because owners returned `Started` instead of `Superseded` after `end`, detach, or close.
- GREEN: The same focused command passed 35 tests (27 manager, 8 startup), zero failures/errors, with `BUILD SUCCESSFUL`.
- Fix commit: `470f087fc1447e1a7f934cceb88fe6c2da1f38d2 fix(voice): terminate in-flight call reservations`.
- Detailed report: `.superpowers/ce1/voice-concurrency-ce1-20260717/task-1-wave-1-fix-report.md`.
- Residual concerns: none known.

### Post-fix stable review repair: cleanup before retry publication
- Stable finding: The shared reservation-owner catch path moved the current slot to `Idle` and completed its deferred `Failed` before attempting cleanup of the exact owned lease or session. Existing and fresh matching callers could therefore retry and consume the factory while failed-owner cleanup was still blocked.
- Production repair: The catch path now attempts exact current-resource cleanup and applies any distinct cleanup failure as suppressed first, outside the monitor. Only afterward does it atomically clear the slot if the reservation/token is still current, followed by `Failed` completion outside the monitor.
- Regression coverage: Added a failed pre-factory owner with blocked Telecom lease retirement and a canceled post-factory owner with blocked session close. In each case, one existing matching waiter and one fresh matching caller remain suspended, and the factory remains at its original admission, until cleanup release. After release, exactly one retry starts and the other reuses it.
- Preserved contracts: canonical cancellation identity, exact-once lease/session cleanup, primary/suppressed identity and order, newer-slot protection, and the prohibition on external work or deferred completion under the manager monitor.
- RED: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest'` ran 37 tests and failed exactly the two new blocked-cleanup regressions because a second factory admission occurred before cleanup release.
- GREEN: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest' --rerun-tasks` executed all 179 Gradle tasks and passed 37 tests (29 manager, 8 startup), zero failures/errors, with `BUILD SUCCESSFUL`.
- Commit: `746a458d34a3c62ff83046312ebe7755a615a8d9 fix(voice): finish owner cleanup before retry`.
- Residual concerns: none known.

### Wave 2 stable-review repair: snapshot barrier preservation before cleanup

- Stable finding: The cancellation catch decided whether to preserve an inherited predecessor barrier only after potentially blocking exact lease/session cleanup. If the barrier completed with failure during that cleanup, the later `isCompleted` check selected `Idle`, allowing a matching waiter to retry without the exact failed barrier.
- Production repair: Before external cleanup begins, the catch path snapshots the exact incomplete inherited barrier only while the reservation/token still owns the slot. After cleanup, the existing identity-guarded transition installs a fence from that captured identity even if the barrier completed during cleanup. A newer slot still wins and is never mutated.
- Regression: Cancel an inheriting owner, block its exact Telecom lease retirement, complete its predecessor barrier with a non-copyable failure during retirement, and attach a matching fresh waiter. After retirement release, the canceled owner retains its canonical cancellation, the waiter receives the exact predecessor failure, all leases retire once, and the factory remains at one admission.
- RED: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest' --rerun-tasks` executed 44 tests and failed only `cancellation preserves barrier that fails during blocked lease retirement`; the fresh waiter returned a started result instead of the exact predecessor failure. `BUILD FAILED in 2m 27s`.
- GREEN: The same fresh command executed all 179 Gradle tasks and passed 44 tests (30 manager, 6 barrier, 8 startup), zero failures/errors, with `BUILD SUCCESSFUL in 1m 33s`.
- Final focused barrier test file size: 276 lines. `git diff --check` is clean.
- Commit: this report is included in the stable-review repair commit; its full SHA is supplied in the completion handoff.
- Residual concerns: none known.

## CE1 Wave 3 Repair

### Result

- Status: DONE; CE1-T1-008, CE1-T1-009, and CE1-T1-010 are fixed or directly proved.
- Commit: this report is included in the single wave-3 fix commit; the completion handoff supplies its full SHA.

### Summary

- `Active` now retains the exact pending startup-publication identity until one locked ownership transition selects `Published`, `Failed`, or `Superseded`. Deferred completion remains outside the monitor.
- Matching manager starts and pre-route startup callers await a pending `Active` publication instead of treating the just-installed session as live.
- Terminal and nonmatching-replacement detach paths select `Superseded` while they still own the exact slot, complete it outside the monitor, and retain sole responsibility for session/collector cleanup.
- Added deterministic terminal and replacement races paused after `Active` installation but before collector attachment. Both prove manager owners/waiters return `Superseded`, startup waiters return `Stale` without route resolution, stale session state cannot overwrite the winning manager state, and cleanup remains exact once.
- Cleanup-fence success and failure coverage now calls `matchingRoute`, invokes a second terminal API while still fenced, and proves the subsequent fresh start remains gated and receives the exact cleanup result.
- Every authoritative Task 1 filter now uses `*VoiceAgentCallManager*Test`, including the tracked reservation plan and the ignored Task 1 brief.

### TDD and Verification

- RED: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest' --rerun-tasks` executed all 179 tasks and 46 tests; only the two new publication-race tests failed. The six barrier tests, including the new fence-exit assertions, passed.
- GREEN: the identical fresh command executed all 179 tasks and passed 46 tests: 30 manager, 6 barrier, 2 publication-race, and 8 startup; zero failures/errors; `BUILD SUCCESSFUL in 1m 27s`.
- XML: `VoiceAgentCallManagerTest` 30, `VoiceAgentCallManagerBarrierTest` 6, `VoiceAgentCallManagerPublicationTest` 2, `VoiceAgentCallStartupTest` 8.
- `git diff --check`: clean.
- Test line counts: manager 981, barrier 281, publication 152, fixtures 376, startup 328.

### Concerns

- None known. Existing unrelated compiler and web sourcemap warnings remain unchanged.

## CE1 Wave 3 Stable-Review Repair

### Result

- Status: DONE; cancellation between pending `Active` installation and collector attachment can no longer publish a stale session.
- Commit: this report is included in the stable-review repair commit; the completion handoff supplies its full SHA.

### Summary

- Immediately after collector launch returns, the reservation owner now checks its own coroutine context before attaching the collector or selecting `Published`.
- If cancellation won while launch was synchronously blocked, the still-unattached collector is canceled before the exact cancellation is rethrown. The existing shared catch then closes the exact route-owned session before atomically selecting `Failed`; deferred completion remains outside the monitor.
- Added a deterministic `BlockingCollectorDispatcher` regression that cancels the owner while dispatch is blocked. It proves exact cancellation identity, one session close and one lease retirement, a matching waiter's `Failed` wake-up and retry, and no stale publication or state overwrite.

### TDD and Verification

- RED: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest' --rerun-tasks` executed all 179 tasks and 47 tests; only `cancelled owner after active install fails publication and wakes matching retry` failed because the waiter observed `Existing` instead of becoming the retry owner. `BUILD FAILED in 1m 23s`.
- Targeted GREEN: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManagerPublicationTest' --rerun-tasks` executed all 179 tasks and passed all 3 publication tests; `BUILD SUCCESSFUL in 1m 27s`.
- Authoritative GREEN: the broadened fresh command executed all 179 tasks and passed 47 tests: 30 manager, 6 barrier, 3 publication, and 8 startup; zero failures/errors; `BUILD SUCCESSFUL in 1m 20s`.
- XML confirms the same 30/6/3/8 split with zero skipped, failures, or errors. `git diff --check` is clean.
- Final line counts: production manager 577, manager tests 981, barrier tests 281, publication tests 210, fixtures 376, startup tests 328.

### Concerns

- None known. Existing unrelated compiler and web sourcemap warnings remain unchanged.

## CE1 Wave 3 Cleanup-Ownership Follow-Up

### Result

- Status: DONE; a canceled pending-`Active` owner now claims sole session-cleanup ownership before external close begins.
- Review baseline: `84615fd6cebbe595985f3d94e24ceafd7da71c57`.
- Commit: this report is included in the follow-up repair commit; the completion handoff supplies its full SHA.

### Summary

- Added an unpublishable, un-detachable `CleanupClaim` slot containing only a completion gate. The catch path installs it and selects `Failed` atomically while the exact token/publication still owns pending `Active`.
- Terminal APIs cannot recover or clean the claimed session. Manager starts and `matchingRoute` await the claim gate; no factory, replacement, or retry admission occurs until the owner finishes exact external cleanup.
- After cleanup, the owner transitions the exact claim to `Idle` under the monitor, then completes `Failed` publication and the cleanup gate outside it. If another path already detached pending `Active` before the claim, that path retains cleanup ownership and the canceled owner does not close the session.
- Incoming callers canceled while awaiting the claim retire their exact route lease with the existing canonical cancellation and suppression rules.

### TDD and Verification

- RED: the authoritative broadened `--rerun-tasks` command executed all 179 tasks and 48 tests. Only `cancelled owner claims cleanup before blocked close and gates terminal retry` failed: a matching waiter completed during the blocked owner close after concurrent terminal detach. `BUILD FAILED in 1m 15s`.
- Targeted GREEN: the publication-class `--rerun-tasks` command executed all 179 tasks and passed all 4 publication tests; `BUILD SUCCESSFUL in 1m 17s`.
- Strengthened targeted GREEN: the regression additionally injects an exact close failure and proves the canonical cancellation remains primary with that failure suppressed; it passed after the test-only strengthening.
- Authoritative GREEN: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest' --rerun-tasks` executed all 179 tasks and passed 48 tests: 30 manager, 6 barrier, 4 publication, and 8 startup; zero failures/errors; `BUILD SUCCESSFUL in 1m 16s`.
- XML confirms the same 30/6/4/8 split with zero skipped, failures, or errors. `git diff --check` is clean.
- Final line counts: production manager 634, manager tests 981, barrier tests 281, publication tests 293, fixtures 422, startup tests 328.

### Concerns

- None known. Existing unrelated compiler and web sourcemap warnings remain unchanged.
