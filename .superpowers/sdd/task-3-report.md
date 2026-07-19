## Result

- Status: DONE. Task 1 already implemented the Task 3 production protocol; Task 3 adds only two missing deterministic binding tests.
- Base: `f5f722b71633d46ccef9c4a30caf907fac714410`.
- Head: the single Task 3 report/test commit; its final SHA is supplied in the completion handoff because a commit cannot contain its own hash.
- Commit: `test(voice): bind manager monitor independence` (final SHA in the completion handoff).
- Production changes: none.

## Exact Acceptance and Invariant Mapping

- Collector launch/cancellation outside the monitor: `VoiceAgentCallManager.runReservationOwner` first installs the exact
  `Active` identity under the monitor, launches the collector afterward, then attaches its `Job` only when `current === active`.
  Cancellation of rejected, detached, or owner-cancelled jobs occurs after the relevant monitor region. Existing
  `VoiceAgentCallManagerPublicationTest` cases block collector dispatch and prove both terminal and replacement paths can
  proceed, select `Superseded`, and reject later attachment.
- Immediate collector emission and stale-publication exclusion: every manager fixture exposes `MutableStateFlow`, whose
  subscription emits its current value immediately. The manager copies `session.state.value` during atomic `Active`
  installation, then the collector republishes only when the current slot is `Active` with the exact reservation token.
  The terminal and replacement publication tests mutate the stale session after supersession and prove it cannot overwrite
  the terminal or replacement state.
- Blocked command independence: new
  `blocked set muted does not block status update or another command snapshot` blocks `setMuted` on a worker, then calls
  `updateCallStatus` and `reconnect` on the test thread before releasing it. Both return and publish/invoke while the first
  command remains blocked.
- Reentrant startup: new
  `reentrant session start callback can update manager and join before publication` makes `session.start()` launch a worker
  that calls `manager.updateCallStatus`, awaits it with a one-second bound, and joins it. Startup returns `Started`, retains
  the lease, and publishes the exact conversation and callback status.
- End during blocked factory: existing `end invalidates starting before and after factory transfer`, plus its detach and
  close variants, invokes the terminal API before releasing `BlockingFirstVoiceAgentCallFactory`. The matching waiter is
  already `Superseded`; after release the owner is `Superseded`, the created session never starts, closes once, and the exact
  lease retires once.
- Command snapshots: `interrupt`, `setMuted`, `reconnect`, and `recordDiagnostic` call `activeSessionSnapshot()`, whose
  monitor region returns only the immutable session reference; each session method is invoked after that function returns.
  `end`, `detachForEndAndDrain`, and `closeNow` similarly detach state under the monitor and perform deferred completion,
  collector cancellation, and session lifecycle methods afterward.
- Starting invalidation and deferred completion: `detachTerminalLocked` selects `Superseded` and changes slot ownership
  atomically, while all `CompletableDeferred.complete` calls occur in callers after leaving the monitor.
- Admitted predecessor fences: terminal detachment of `Starting` installs `CleanupFence(current.predecessorCleanup)` rather
  than discarding the barrier. Existing barrier success/failure tests call both `end()` and `closeNow()` while predecessor
  cleanup is blocked, prove fresh factory admission remains fenced, and replay the exact cleanup result afterward.

## Step 1 Scenario Audit

- The obsolete `ManagerLockBlockingStateFlow` and `BlockingCollectorFailingStartSession` fixtures are not present at the
  base. Task 1's `BlockingCollectorDispatcher` publication tests provide the stronger deterministic launch-blocked and
  stale-state proof, so no redundant collector fixture was added.
- Blocked `setMuted` plus concurrent status/different-command behavior was not directly bound; one focused test was added.
- Blocked factory plus terminal return and later stale cleanup was already directly bound for all three terminal APIs; no
  duplicate test was added.
- Reentrant `session.start()` callback/join was not directly bound; one focused test was added.

## Verification

- Focused new coverage: `./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManagerMonitorTest'` passed 2 tests with
  `BUILD SUCCESSFUL`.
- Authoritative fresh command:

  ```text
  ./gradlew :app:testDebugUnitTest \
    --tests '*VoiceAgentCallManager*Test' \
    --tests '*VoiceAgentCallStartupTest' \
    --tests '*VoiceAgentCallServiceLifecycleTest' \
    --tests '*VoiceAgentCallServicePolicyTest' \
    --rerun-tasks
  ```

  Result: `BUILD SUCCESSFUL in 1m 16s`; all 179 Gradle tasks executed. XML reports 67 tests, zero skipped,
  failures, or errors: manager 30, barrier 6, publication 4, monitor 2, startup 8, lifecycle 9, policy 8.
- Required forbidden-operation scan was run exactly. Manual inspection of every match confirms factory, session lifecycle,
  collector launch/cancel, deferred await/complete, and command calls are outside `synchronized(lock)` regions; monitor
  regions contain slot/state selection, identity checks, and StateFlow assignment only.
- `git diff --check`: clean.

## Files

- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerMonitorTest.kt`: two focused monitor-independence regressions and private deterministic sessions.
- `.superpowers/sdd/task-3-report.md`: acceptance mapping and fresh verification evidence.

## Concerns

- No known Task 3 correctness concerns.
- Existing unrelated Kotlin deprecation/opt-in and web sourcemap/chunk-size warnings remain unchanged.
