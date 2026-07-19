# Task 1 CE1 Wave 3 Fix Report

## Result

- Status: all three blocking wave-3 causes fixed or directly proved; fresh focused verification passed.
- Review baseline: `59caa85ca65e55516f9b8e3d4550177ac550b1e3`.
- Fix commit: this report is part of the single coherent wave-3 fix commit. The completion handoff supplies the final full SHA because a commit cannot contain its own hash.

## Root Causes and Changes

### CE1-T1-008: detached pending `Active` publication reported success

The reservation deferred stopped being slot-owned as soon as `Starting` installed `Active`. During the remaining collector launch/attachment window, matching callers immediately reused that `Active`, while terminal or nonmatching-replacement code could detach it. The owner observed failed attachment but still completed `Published` and returned `Started`.

`Active` now owns the same `PendingPublication` identity until final selection. The manager monitor selects exactly one outcome:

- `Published` only while the exact token-owned `Active` remains installed after collector attachment.
- `Superseded` when a terminal or nonmatching replacement detaches pending `Active` ownership.
- `Failed` when the exact owner catch path retires its current slot.

Selection is state-only work under the monitor. The associated `CompletableDeferred.complete(...)` remains outside the monitor, so resumed waiters and completion handlers cannot run inside the critical section. Matching manager starts and `matchingRoute` now await pending `Active` publication. If detach wins, the owner performs no session cleanup; the terminal or replacement path owns collector cancellation and exact session end/close. Only a collector that was never attached is canceled by the owner.

Two deterministic tests use a dispatcher that blocks the collector `scope.launch` call after `Active` installation and before attachment/final selection:

- Terminal detach: owner and matching manager waiter return `Superseded`; production startup returns `Stale` with zero route-resolution calls; stale session state cannot replace `Ending`; the session ends and its lease retires exactly once.
- Nonmatching replacement: old owner/waiter return `Superseded`; production startup returns the old immutable route as `Stale`; the old session ends exactly once; the replacement remains active and its route remains owned.

### CE1-T1-009: authoritative filter skipped the split barrier suite

Every authoritative Task 1 focused filter was changed from `*VoiceAgentCallManagerTest` to `*VoiceAgentCallManager*Test` in:

- `docs/superpowers/plans/2026-07-17-voice-call-manager-reservations.md`
- `.superpowers/sdd/task-1-brief.md` (ignored local binding brief)
- `.superpowers/sdd/task-1-report.md`

Separate historical plans from 2026-07-15 and 2026-07-16 were intentionally left unchanged because they are not authoritative Task 1 reservation-plan commands.

### CE1-T1-010: cleanup-fence route and repeated-terminal exits lacked proof

The existing success/failure terminal-fence helper now leaves `CleanupFence` installed, asserts `matchingRoute` returns `NoMatch`, invokes `closeNow()` as a second terminal API, and only then starts the fresh reservation. Before predecessor release, the fresh call remains incomplete and the factory remains at one admission. After release, successful cleanup admits exactly one fresh session; failed cleanup replays the same non-copyable throwable and admits no factory work.

No production fence change was required; the existing early exits already preserve the exact deferred.

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

- All 179 Gradle tasks executed.
- 46 tests executed.
- Exactly two failed:
  - `terminal detach before final publication supersedes owner and matching callers`
  - `replacement detach before final publication supersedes old callers and preserves new active`
- Both failed before terminal/replacement selection because matching callers completed against pending `Active` as live.
- The six barrier tests passed, including the new matching-route and repeated-terminal assertions.
- Gradle result: `BUILD FAILED in 1m 57s` as expected.

### GREEN

The identical fresh command after the production repair:

- All 179 Gradle tasks executed.
- `VoiceAgentCallManagerTest`: 30 tests, 0 failures, 0 errors.
- `VoiceAgentCallManagerBarrierTest`: 6 tests, 0 failures, 0 errors.
- `VoiceAgentCallManagerPublicationTest`: 2 tests, 0 failures, 0 errors.
- `VoiceAgentCallStartupTest`: 8 tests, 0 failures, 0 errors.
- Total: 46 tests, 0 failures, 0 errors.
- Gradle result: `BUILD SUCCESSFUL in 1m 27s`.
- `git diff --check`: clean.

## Files and Line Counts

- `VoiceAgentCallManager.kt`: 571 lines.
- `VoiceAgentCallManagerTest.kt`: 981 lines.
- `VoiceAgentCallManagerBarrierTest.kt`: 281 lines.
- `VoiceAgentCallManagerPublicationTest.kt`: 152 lines.
- `VoiceAgentCallManagerTestFixtures.kt`: 376 lines.
- `VoiceAgentCallStartupTest.kt`: 328 lines.

Every test file is below 1,000 lines.

## Contract Audit

- Pending publication outcome selection is atomic with exact slot ownership; deferred completion remains outside the monitor.
- No route retirement, job launch/cancellation, factory work, session lifecycle work, or waiter suspension occurs under the monitor.
- Detach paths retain sole cleanup ownership; the stale owner neither closes nor ends the detached session again.
- Incoming matching leases retire exactly once on supersession; installed/replacement route ownership remains exact.
- Immutable awaited route metadata is preserved through startup `Stale` mapping with no redundant resolver or notification-start boundary.
- Failure/cancellation primary and suppression ordering is unchanged.
- Cleanup-fence identity, success gating, and exact failure replay are preserved across route matching and repeated terminal calls.
- No dependency, public API, compatibility manager path, or route-selection behavior was added.

## Review Accounting

- Manual core correctness, API-contract, reliability, maintainability, testing, and cross-file service-boundary review: completed.
- External Codex, simplify, and AI-slop adapters: not present in this repository.
- UI, React/Next, security, and data-integrity adapters: not applicable to the concurrency state-machine/test/doc diff.
- CI-equivalent focused verification: passed.

## Concerns

None known. The fresh build retains pre-existing unrelated Kotlin opt-in/deprecation and web sourcemap warnings; no warning originates from the touched voice concurrency files.

## Post-Fix Stable Review Repair

Stable review found one remaining cancellation window: after the pending `Active` slot was installed, `scope.launch` could synchronously block in its dispatcher. If the reservation owner was canceled during that dispatch and launch later returned, the owner had no cancellation checkpoint before collector attachment and `Published` selection, so the canceled session could become observable as live.

The manager now calls `currentCoroutineContext().ensureActive()` immediately after collector launch returns and before either attachment or publication. On cancellation, it first cancels the newly created, still-unattached collector, then rethrows the same `CancellationException`. The shared owner catch retains cleanup ownership: it closes the exact route-owned session once, preserves the cancellation as primary, changes only the still-token-owned slot to `Idle`, selects `Failed`, and completes the publication deferred outside the monitor. A newer slot remains protected by the existing identity checks.

The new deterministic publication regression uses `BlockingCollectorDispatcher` to pause launch after pending `Active` installation, starts a matching waiter, cancels the owner with a canonical cancellation instance, and releases dispatch. It proves:

- the owner receives the same cancellation instance;
- the unattached collector cannot publish stale session state;
- the canceled session closes exactly once and its lease retires exactly once;
- the matching waiter wakes from `Failed`, retries, and becomes the sole published owner;
- the retry session remains active with its own state and route metadata.

TDD evidence:

- RED: the authoritative broadened `--rerun-tasks` command executed all 179 tasks and 47 tests. Only `cancelled owner after active install fails publication and wakes matching retry` failed: the waiter observed `Existing` instead of the expected retry-owner `Started`. Gradle reported `BUILD FAILED in 1m 23s`.
- Targeted GREEN: the publication-class `--rerun-tasks` command executed all 179 tasks and passed its 3 tests, with `BUILD SUCCESSFUL in 1m 27s`.
- Authoritative GREEN: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest' --rerun-tasks` executed all 179 tasks and passed 47 tests: manager 30, barrier 6, publication 3, startup 8; zero failures/errors; `BUILD SUCCESSFUL in 1m 20s`.
- XML independently confirms the 30/6/3/8 test split with zero skipped, failures, or errors. `git diff --check` is clean.

Final line counts are 577 for `VoiceAgentCallManager.kt`, 981 for the manager tests, 281 for barrier tests, 210 for publication tests, 376 for fixtures, and 328 for startup tests. Every test file remains below 1,000 lines. No residual concern is known beyond the unchanged unrelated compiler and web sourcemap warnings.

## Cleanup-Ownership Stable Review Follow-Up

Stable review identified a second-order race in the cancellation repair. Although the owner checked cancellation before attachment, its outer catch left the exact pending `Active` installed while calling `session.closeNow()`. If close blocked, a terminal API could detach that same `Active` and invoke `end()` or `closeNow()` concurrently; a fresh start could also bypass the still-running owner cleanup.

The catch path now performs one atomic ownership transition before external cleanup. While holding the manager monitor, it verifies the exact active token and pending-publication identity, selects `Failed`, and replaces `Active` with an unpublishable `CleanupClaim`. The claim contains only a completion gate, not the session:

- terminal and detach APIs treat it as already cleanup-owned and perform no lifecycle call;
- `start` waits on the gate without retiring or replacing its route lease, while cancellation of that wait retains the existing exact retirement and suppression rules;
- `matchingRoute` waits on the same gate and re-evaluates the complete slot afterward;
- active-session commands cannot snapshot the claimed session.

The owner then closes the exact session outside the monitor. Cleanup failure remains suppressed onto the original canonical cancellation in the same order. After cleanup, it changes only the identical claim to `Idle` under the monitor, then completes the selected `Failed` publication and cleanup gate outside the monitor. Existing matching waiters retry, fresh callers compete normally only after release, and terminal call status is preserved. Conversely, if terminal or replacement detached the pending `Active` before the catch could claim it, an `activeInstalled` ownership marker prevents the canceled owner from cleaning a session now owned by that detach path.

The deterministic regression combines `BlockingCollectorDispatcher` with a session whose `closeNow()` blocks and records overlapping lifecycle calls. It installs an existing matching waiter, cancels the owner, releases collector dispatch until owner cleanup blocks, invokes `end()`, and adds a fresh matching caller. Before close release it proves both callers remain pending, the factory remains at one admission, exactly one close is running, and no `end()` or overlapping cleanup occurred. After release it proves:

- the owner receives the exact canonical cancellation with the injected close failure as its sole suppressed throwable;
- the session is closed once, never ended, and never cleaned concurrently;
- exactly one matching retry starts and the other caller reuses its route, with exact loser-lease retirement;
- the replacement state is live while terminal `Ending` call status remains intact.

TDD evidence:

- RED: the authoritative broadened `--rerun-tasks` command executed all 179 tasks and 48 tests. Only `cancelled owner claims cleanup before blocked close and gates terminal retry` failed because the matching waiter completed before the blocked close was released. Gradle reported `BUILD FAILED in 1m 15s`.
- Targeted GREEN: the publication-class `--rerun-tasks` command executed all 179 tasks and passed all 4 publication tests, with `BUILD SUCCESSFUL in 1m 17s`.
- Authoritative GREEN: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest' --rerun-tasks` executed all 179 tasks and passed 48 tests: manager 30, barrier 6, publication 4, startup 8; zero failures/errors; `BUILD SUCCESSFUL in 1m 16s`.
- XML independently confirms the 30/6/4/8 split with zero skipped, failures, or errors. `git diff --check` is clean.

Final line counts are 634 for `VoiceAgentCallManager.kt`, 981 for manager tests, 281 for barrier tests, 293 for publication tests, 422 for fixtures, and 328 for startup tests. Every test file remains below 1,000 lines. No residual concern is known beyond unchanged unrelated compiler and web sourcemap warnings.
