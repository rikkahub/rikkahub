# Voice Call Manager Reservations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace monitor-held call startup with suspending `Starting` reservations so lifecycle work cannot block unrelated manager operations or publish stale sessions.

**Architecture:** `VoiceAgentCallManager` owns one sealed `Idle`/`Starting`/`Active` slot. Matching callers await a reservation deferred outside the monitor, supersession is terminal for those waiters, and a shared predecessor-cleanup barrier prevents newer reservations from overlapping a detached active session. All factory, session, collector, cleanup, retirement, and deferred-completion work runs outside the monitor.

**Tech Stack:** Kotlin, kotlinx.coroutines `CompletableDeferred`, `CoroutineScope`, `Job`, `StateFlow`, JUnit 4, Gradle.

## Global Constraints

- Preserve Telecom-first selection, immutable route ownership, reconnect behavior, notification behavior, and exact lease retirement.
- The first lifecycle or cleanup failure remains primary; later failures are suppressed in execution order.
- Manager locks protect state inspection and ownership transfer only.
- Add no dependencies and no compatibility manager state path.
- Modify only the standalone repository at `/home/muly/code/rikkahub`.
- This plan implements review finding 2 and is independently executable before or after the other plans in this design set.

## Binding Plan Contract

### File ownership

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt` owns call-slot state, reservation completion, publication, and command snapshots.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartup.kt` maps manager start results to service-level `Started` or `Stale` results.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt` owns reservation, supersession, failure-order, collector, and no-monitor-blocking tests.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt` owns manager-result mapping and stale-start route ownership tests.

### Exact interfaces

Add these internal result contracts in `VoiceAgentCallManager.kt`:

```kotlin
internal sealed interface VoiceAgentManagerStartResult {
    data class Started(val route: VoiceAgentRouteMetadata) : VoiceAgentManagerStartResult
    data class Existing(val route: VoiceAgentRouteMetadata) : VoiceAgentManagerStartResult
    data object Superseded : VoiceAgentManagerStartResult
}

internal enum class VoiceAgentStartupResolution {
    Published,
    Failed,
    Superseded,
}

internal sealed interface VoiceAgentRouteMatchResult {
    data object NoMatch : VoiceAgentRouteMatchResult
    data class Existing(val route: VoiceAgentRouteMetadata) : VoiceAgentRouteMatchResult
    data class Superseded(val route: VoiceAgentRouteMetadata) : VoiceAgentRouteMatchResult
}
```

Change manager entry points to:

```kotlin
internal suspend fun start(
    conversationId: Uuid,
    config: VoiceAgentLaunchConfig,
    routeLease: VoiceAgentRouteLease,
    scope: CoroutineScope,
): VoiceAgentManagerStartResult

internal suspend fun matchingRoute(
    conversationId: Uuid,
    config: VoiceAgentLaunchConfig,
): VoiceAgentRouteMatchResult
```

`VoiceAgentCallStartup.start()` remains suspending and maps results exactly:

- `matchingRoute().Existing(route)` -> `VoiceAgentCallStartupResult.Started(route, startedNewSession = false)` without resolving another route.
- `matchingRoute().Superseded(route)` -> `VoiceAgentCallStartupResult.Stale(route)` without resolving or starting another route.
- `matchingRoute().NoMatch` -> resolve the route and call manager `start`.
- `Started(route)` -> `VoiceAgentCallStartupResult.Started(route, startedNewSession = true)`.
- `Existing(route)` -> `VoiceAgentCallStartupResult.Started(route, startedNewSession = false)`.
- `Superseded` -> `VoiceAgentCallStartupResult.Stale(routeLease.metadata)`; the manager has already retired or transferred the lease.

### State contract

The manager owns one sealed slot:

- `Idle` owns nothing.
- `Starting` owns an identity token, conversation ID, launch config, immutable route metadata, `CompletableDeferred<VoiceAgentStartupResolution>`, and an optional shared `CompletableDeferred<Result<Unit>>` predecessor-cleanup barrier.
- `Active` owns one route-owned session and an optional state-collector job.

Every `Starting` deferred reaches exactly one terminal resolution. `complete(...)` is never called while holding the manager monitor because completion handlers may resume external coroutines.

When a reservation replaces `Active`, it creates the predecessor-cleanup barrier, detaches the exact session/collector, and owns completing that barrier after collector cancellation and `session.end()`. Any reservation that supersedes this `Starting` inherits the same barrier by identity. No reservation may call `factory.create`, `session.start`, or publish `Active` until the inherited barrier completes successfully.

### Startup rules

- Matching `Active`: retire the incoming lease outside the monitor and return `Existing` with the installed route.
- Matching `Starting`: await its deferred outside the monitor. `Published` retires the incoming lease and returns `Existing`. `Failed` alone enters retry mode with the still-owned incoming lease. In retry mode, `Idle` may accept a replacement reservation and another matching slot may be awaited/reused, but any different non-idle slot is newer and makes the retry terminal `Superseded`; the older waiter may not displace it. `Superseded` is always terminal: retire the incoming lease and return `Superseded`; never install a replacement reservation from that waiter.
- If a matching waiter's coroutine is canceled during `await`, retire its still-owned incoming lease, suppress any retirement failure onto the canonical `CancellationException`, and rethrow cancellation. It must not mutate or complete the reservation it was awaiting.
- `matchingRoute` returns matching `Active` metadata as `Existing`. For matching `Starting`, `Published` returns `Existing`; after `Failed`, it returns `NoMatch` only if the slot is `Idle`, follows another matching slot, or returns terminal `Superseded` if a different newer slot exists. `Superseded` returns terminal `Superseded` with the awaited reservation's immutable route metadata. A pre-route waiter never converts supersession into `NoMatch`.
- Different `Starting` or `Active`: install a new reservation and complete the displaced reservation as `Superseded` outside the monitor. The latest reservation owns publication.
- A reservation replacing `Starting` inherits its predecessor-cleanup barrier. A reservation replacing `Active` creates a new barrier before detaching that active session. A reservation starting from `Idle` has no barrier.
- Every reservation awaits its predecessor barrier outside the monitor before factory consumption. Barrier failure is primary; the reservation retires its unconsumed incoming lease and suppresses retirement failure before throwing.
- Recheck token ownership after previous-session end, after factory creation, before session start, after session start, and before collector attachment.
- Before `factory.create()`, the manager owns the incoming lease. After `factory.create()` begins, the factory or returned route-owned session owns it.
- A stale unconsumed lease is retired; a stale created session is closed without publication; a stale started session is closed exactly once.
- Owner cancellation invalidates its token, cleans its current ownership, completes `Failed`, and rethrows cancellation. Cancellation never abandons a predecessor cleanup that this owner already admitted: the exact cleanup action completes its shared barrier in `finally`, so an inheriting reservation cannot wait forever.

### Failure rules

- Previous-session end failure is primary; incoming-lease retirement failure is suppressed.
- Predecessor cleanup success or failure is replayed identically to every reservation that inherited its barrier; no successor bypasses it.
- Factory failure is propagated unchanged because the factory consumes and retires the lease.
- Session-start failure is primary; `closeNow()` failure is suppressed.
- A failed or canceled old reservation cannot clear or mutate a newer slot.

### Lock rules

No monitor region may call `routeLease.retire`, `session.end`, `factory.create`, `session.start`, `session.closeNow`, `scope.launch`, `Job.cancel`, `CompletableDeferred.await`, `CompletableDeferred.complete`, or session command methods. StateFlow value assignment and immutable state snapshots may occur under the monitor.

## Illustrative Implementation Guidance

Use a small private action/result value returned by each synchronized transition, then execute its external actions after leaving `synchronized(lock)`. Keep the binding state and result names above; helper names are not binding.

---

### Task 1: Introduce Suspending Matching Reservations

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt:14-104`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartup.kt:28-57`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt:28-171`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt`

**Interfaces:**
- Produces: `VoiceAgentManagerStartResult`, `VoiceAgentStartupResolution`, `VoiceAgentRouteMatchResult`, suspending `start`, and suspending `matchingRoute` exactly as declared in the binding contract.
- Consumes: existing `VoiceAgentRouteLease`, `VoiceAgentRouteMetadata`, `RouteOwnedManagedVoiceCallSession`, and `VoiceAgentCallFactory.create(...)`.

**Invariants:**
- Matching waiters never own or mutate the reservation they await.
- Incoming leases remain owned by waiters until `Published`, `Failed`, `Superseded`, cancellation, or factory transfer decides their exact cleanup.
- Deferred completion occurs outside the manager monitor.

**Acceptance:**
- A matching waiter remains suspended while the owner factory is blocked.
- `updateCallStatus` completes while that factory is blocked.
- After owner publication, owner returns `Started`, waiter returns `Existing`, and only the waiter's lease retires.
- A canceled matching waiter retires its exact incoming lease once and remains cancellation even when retirement fails.
- A pre-route `matchingRoute` waiter returns the installed route after publication without resolving a second route.

- [ ] **Step 1: Replace the monitor-blocking test with a suspending-reservation test**

Replace `concurrent matching starts install one session and retire rejected exact lease` with a coroutine test using the existing blocking factory and this assertion structure:

```kotlin
@Test
fun `matching start suspends without blocking manager then reuses published call`() = runTest {
    val releaseFactory = CountDownLatch(1)
    val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
    val manager = VoiceAgentCallManager(factory)
    val conversationId = Uuid.random()
    val config = fakeLaunchConfig()
    val installed = CountingTelecomLease()
    val duplicate = CountingTelecomLease()

    val owner = async(Dispatchers.Default) {
        manager.start(conversationId, config, installed.lease, this@runTest)
    }
    assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
    val waiter = async(Dispatchers.Default) {
        manager.start(conversationId, config, duplicate.lease, this@runTest)
    }

    manager.updateCallStatus(VoiceCallStatus.ForegroundStarting)
    assertFalse(waiter.isCompleted)
    releaseFactory.countDown()

    assertTrue(owner.await() is VoiceAgentManagerStartResult.Started)
    assertTrue(waiter.await() is VoiceAgentManagerStartResult.Existing)
    assertEquals(1, factory.createdCalls.get())
    assertEquals(0, installed.retireCalls)
    assertEquals(1, duplicate.retireCalls)
}
```

Add a `matchingRoute` test that blocks the owner factory, starts `matchingRoute` in `async(Dispatchers.Default)`, asserts it is suspended, releases the factory, and asserts `VoiceAgentRouteMatchResult.Existing` contains the exact installed metadata.

Add a cancellation test that cancels a matching `manager.start` waiter while the owner remains blocked. Assert the waiter's lease retires exactly once, the thrown value remains the canonical cancellation, retirement failure is suppressed when injected, and the owner reservation is still pending and unchanged.

- [ ] **Step 2: Run the focused test and verify the new contract is absent**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test' --tests '*VoiceAgentCallStartupTest'
```

Expected: compilation fails because manager start still returns `Boolean`, `matchingRoute` and the new result types do not exist, and waiter cancellation does not own retirement.

- [ ] **Step 3: Add the sealed slot, deferred completion, and suspending result APIs**

Implement the exact state and interfaces from the binding contract. For this task, support `Idle`, matching `Starting`, matching `Active`, successful publication, and cancellation-safe waiter retirement. Execute duplicate/canceled-waiter retirement and deferred completion after leaving the monitor.

- [ ] **Step 4: Map manager results in `VoiceAgentCallStartup`**

Remove the Boolean/result re-read branch. Replace the nullable metadata shortcut with the exact `VoiceAgentRouteMatchResult` mapping, including terminal pre-route supersession, then map the three manager start results exactly as specified in the binding contract.

- [ ] **Step 5: Run focused tests**

Run the command from Step 2.

Expected: all selected tests pass; no assertion inspects `Thread.State.BLOCKED` for manager startup.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartup.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt
git commit -m "refactor(voice): reserve call startup outside monitor"
```

### Task 2: Add Failure Retry, Terminal Supersession, and Predecessor Serialization

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`

**Interfaces:**
- Consumes and preserves all exact interfaces from Task 1.
- Produces complete `Published`/`Failed`/terminal-`Superseded` behavior and shared predecessor-cleanup barriers.

**Invariants:**
- A matching waiter retries with its incoming lease only after owner failure; supersession and cancellation retire it terminally.
- Only the latest reservation publishes.
- All reservations descended from one active predecessor await the same cleanup result before factory consumption or publication.
- Every stale local resource is cleaned exactly once outside the monitor.
- Old failure cleanup never resets a newer `Starting` or `Active` slot.

**Acceptance:**
- First factory failure wakes a matching waiter, which creates and publishes the replacement using its own lease.
- A different second start publishes while the first factory is blocked; the first returned session is later closed without starting or publishing.
- A matching waiter superseded by a different start or `end()` retires its lease and cannot resurrect its request.
- A failed matching waiter may replace `Idle` but cannot displace a different reservation installed before its retry resumes.
- If B supersedes active A and blocks in `A.end()`, a later C may supersede B but cannot enter its factory until A's cleanup barrier completes.
- Previous-end and incoming-retirement failures retain current primary/suppressed order.
- Session-start and close failures retain current primary/suppressed order.

- [ ] **Step 1: Add a failing matching-owner-failure retry test**

Add a factory fixture whose first `create` blocks, retires the consumed first lease exactly once, and then throws `firstFailure`, while its second `create` returns `secondSession`. This fixture must model the binding factory ownership rule; the manager must not perform a second retirement after `create` begins. Start matching owner and waiter calls on `Dispatchers.Default`, release the first factory, and assert:

```kotlin
assertSame(firstFailure, runCatching { owner.await() }.exceptionOrNull())
assertTrue(waiter.await() is VoiceAgentManagerStartResult.Started)
assertEquals(2, factory.createdCalls.get())
assertEquals(1, firstLease.retireCalls)
assertEquals(0, secondLease.retireCalls)
assertEquals(1, secondSession.startCalls)
```

- [ ] **Step 2: Add a failing different-start supersession test**

Block the first factory after it has consumed the first lease. Start a different conversation with a second factory result, assert the second publishes, release the first, then assert the first returns `Superseded`, its created session has `startCalls == 0`, `closeNowCalls == 1`, and the second session and lease remain active.

Add two terminal-waiter cases. While owner B and matching waiter W are blocked, (1) start different C and (2) call `manager.end()`. In both cases assert W returns `Superseded`, W's incoming lease retires once, the factory is never called for W, and releasing B cannot let W displace C or resurrect the ended call. Add the equivalent pre-route `matchingRoute`/startup assertion: it returns `Stale` without invoking route resolution.

Add a failed-retry ordering case: owner B fails, but install different C before matching waiter W resumes retry selection. Assert W retires its lease and returns `Superseded` rather than displacing C. Keep the existing failure-to-`Idle` case proving W may still retry and publish when no newer slot exists.

- [ ] **Step 3: Add a failing predecessor-cleanup serialization test**

Install active session A. Make `A.end()` signal entry and block. Start B so it detaches A and enters the blocked end, then start different C so C supersedes B. Assert C's factory and session start have zero calls while A remains blocked. Release A, then assert its end runs once, C alone creates/starts/publishes, B returns `Superseded` and retires its unconsumed lease once, and C observed the same predecessor cleanup result. Repeat the result assertion with `A.end()` failure and verify C receives that failure as primary with C lease-retirement failure suppressed.

- [ ] **Step 4: Run the new tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test'
```

Expected: retry, terminal-supersession, or predecessor-serialization assertions fail because the complete resolution paths are not implemented.

- [ ] **Step 5: Implement all resolution, barrier, and stale-cleanup branches**

Complete deferreds and predecessor barriers exactly once and outside the monitor. Transfer the same barrier identity across superseding `Starting` slots, await it before factory consumption, make `Superseded` terminal for matching waiters, and retire waiters on cancellation before rethrowing. Recheck ownership at every binding checkpoint. Preserve the existing previous-end and start-failure aggregation tests, adapting only expected result types.

- [ ] **Step 6: Run manager tests**

Run the command from Step 4.

Expected: all `VoiceAgentCallManagerTest` tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt
git commit -m "fix(voice): retire superseded call startups"
```

### Task 3: Move Collectors and Commands Outside the Monitor

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`

**Interfaces:**
- Preserves Task 1 result and manager signatures.
- Produces `Active` with optional exact collector job and command snapshot helpers that invoke sessions after releasing the monitor.

**Invariants:**
- Collector launch and cancellation occur outside the monitor.
- A collector publishes only while its exact `Active` identity owns the slot.
- `interrupt`, `setMuted`, `reconnect`, `recordDiagnostic`, `end`, `detachForEndAndDrain`, and `closeNow` invoke session code outside the monitor.
- End/close invalidates `Starting` and completes its deferred as `Superseded` outside the monitor.
- End/close do not cancel or discard an admitted predecessor-cleanup barrier; its owner completes it for any inheritor or observer.

**Acceptance:**
- A collector that emits during session start cannot deadlock and cannot publish after supersession.
- A session command blocked on a latch does not prevent `updateCallStatus` or a different command snapshot.
- A reentrant `session.start()` implementation can hand work to another thread that calls back into the manager and join it without lock inversion.
- End during blocked factory work returns without waiting; the later factory result self-cleans.

- [ ] **Step 1: Add collector and command no-monitor tests**

Replace `ManagerLockBlockingStateFlow` and `BlockingCollectorFailingStartSession`. Add a state flow that emits immediately and a session whose `setMuted` blocks on a latch. While `setMuted` is blocked on a worker thread, call `updateCallStatus` on the test thread and assert it returns and publishes the new status. Release the latch and assert the command completes.

Add an end-during-start test: block the factory, call `manager.end()`, assert the manager reports no active conversation without releasing the factory, then release it and assert the stale created session closes once.

Add a reentrancy test whose `session.start()` launches a worker that calls `manager.updateCallStatus`, waits for that worker with a one-second test bound, and records completion. Assert startup publishes normally and the worker completes; the current monitor-held implementation must time out or fail this assertion before the two-phase change.

- [ ] **Step 2: Run manager tests and verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallManager*Test'
```

Expected: the new collector or lifecycle test fails while collector launch/cancellation or stale completion still occurs under the monitor.

- [ ] **Step 3: Implement two-phase collector attachment and command snapshots**

Publish `Active` first, launch its collector outside the monitor, and attach the returned `Job` only if exact identity still matches. Cancel rejected and detached jobs outside the monitor. Snapshot sessions under the monitor and invoke every command afterward.

- [ ] **Step 4: Run all manager and startup tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallManager*Test' \
  --tests '*VoiceAgentCallStartupTest' \
  --tests '*VoiceAgentCallServiceLifecycleTest' \
  --tests '*VoiceAgentCallServicePolicyTest'
```

Expected: all selected tests pass.

- [ ] **Step 5: Scan the manager for forbidden monitor contents**

Run:

```bash
rg -n "synchronized\(lock\)|factory\.create|session\.start|session\.end|session\.closeNow|scope\.launch|\.cancel\(|\.complete\(" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt
```

Expected: each external-operation match is outside a `synchronized(lock)` block on inspection; no `synchronized(lock) { externalCall(...) }` expression remains.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt
git commit -m "refactor(voice): publish call lifecycle outside lock"
```

### Task 4: Verify the Manager Reservation Boundary

**Files:**
- Verify only: files changed in Tasks 1-3

**Interfaces:**
- Verifies all binding interfaces and invariants in this plan.

**Invariants:**
- No production or test compatibility path retains the Boolean manager-start contract.
- No test requires a manager worker to enter `Thread.State.BLOCKED`.

**Acceptance:**
- Focused and full app JVM suites pass.
- Repository scans find no stale manager contract.

- [ ] **Step 1: Run focused verification**

Run the Task 3 Step 4 command.

Expected: `BUILD SUCCESSFUL` and all selected tests pass.

- [ ] **Step 2: Run the app JVM suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run direct contract scans**

```bash
rg -n "fun start\(|VoiceAgentManagerStartResult|VoiceAgentStartupResolution|VoiceAgentRouteMatchResult|predecessorCleanup" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt

if rg -n "Thread.State.BLOCKED" \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt; then
  exit 1
fi
```

Expected: the manager signature is suspending, both result contracts exist, and the prohibited test-state scan exits `0` with no matches.

- [ ] **Step 4: Commit verification-only fixes if required**

If Steps 1-3 required changes, commit only those scoped changes:

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartup.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt
git commit -m "test(voice): verify call startup reservations"
```

Expected: skip this commit when verification requires no changes.
