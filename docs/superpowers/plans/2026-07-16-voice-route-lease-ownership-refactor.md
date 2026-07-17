# Voice Route Lease Ownership Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace inferred Voice Agent audio-route cleanup with exact session and capture leases while preserving current Telecom-first, fallback, reconnect, Bluetooth, diagnostic, capture, and playback behavior.

**Architecture:** A sealed session route lease owns either the exact active Telecom attempt or the immutable direct-fallback decision, and a managed-session decorator retires that lease before session cleanup. Separately, every microphone capture receives a capture lease composed from focused direct-audio capability leases; Telecom receives a no-op capture lease.

**Tech Stack:** Kotlin, Android self-managed `ConnectionService`, Android audio and Bluetooth APIs, Kotlin coroutines and `StateFlow`, JUnit 4, Gradle, wireless ADB, existing Voice Agent/Hermes E2E scripts.

## Global Constraints

- Preserve Telecom-first selection and the exact `3_000L` activation timeout.
- Preserve fallback decisions, diagnostics, reconnects, notifications, capture, playback, and Bluetooth behavior.
- One managed session and all its reconnects keep one immutable route owner.
- Direct focus, mode, SCO, voice recognition, and communication-device selection remain capture-scoped.
- Telecom ownership performs zero direct Android routing mutations.
- Retirement is deterministic, idempotent, concurrency-safe, and executes every cleanup stage.
- The first cleanup failure remains primary; later failures are suppressed in execution order.
- Existing best-effort direct-routing failures remain nonfatal and retain safe logs.
- Leave no compatibility adapters or dual ownership paths after the final task.
- Add no dependencies and do not migrate to Core-Telecom.
- Modify only the standalone repository at `/home/muly/code/rikkahub`.

---

## File Structure

**Create:**

- `app/src/main/java/me/rerere/rikkahub/voiceagent/RetirementBarrier.kt` — shared one-shot cleanup and result replay.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLease.kt` — session route metadata and exact leases.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt` — decorator owning route retirement.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt` — focused direct Android capabilities and leases.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/RetirementBarrierTest.kt`.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt`.

**Modify:**

- `VoiceAgentTelecomCallRegistry.kt`, `VoiceAgentTelecomConnection.kt` — single Telecom attempt state and deterministic retirement.
- `VoiceAgentAudioRouteResolver.kt`, `VoiceAgentCallStartup.kt`, `VoiceAgentCallManager.kt`, `VoiceAgentCallFactory.kt` — lease acquisition and transfer.
- `VoiceAgentCallService.kt`, `AppModule.kt` — remove global Telecom cleanup inference.
- `VoiceAudioRouteController.kt`, `AndroidVoiceAudioEngine.kt` — capture-lease boundary.
- `AndroidDirectAudioRouteController.kt` — small capability-composing controller.
- Corresponding `VoiceAgent*Test.kt` and audio tests listed in each task.

---

### Task 1: Replace Single-Flight Cleanup with a Result-Replaying Barrier

**Files:**
- Rename: `app/src/main/java/me/rerere/rikkahub/voiceagent/SingleFlightRetirement.kt` → `app/src/main/java/me/rerere/rikkahub/voiceagent/RetirementBarrier.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomConnection.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/RetirementBarrierTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomRetirementTest.kt`

**Interfaces:**
- Produces: `internal class RetirementBarrier { fun retire(block: () -> Unit) }`.
- Waiters restore interruption and observe the same stored `Result<Unit>`.

- [ ] **Step 1: Write failing barrier tests**

Create tests for one-shot reentrancy, interrupted waiter blocking, and failure replay:

```kotlin
@Test
fun `failure is replayed to later callers`() {
    val barrier = RetirementBarrier()
    val failure = IllegalStateException("retirement failed")

    val first = runCatching { barrier.retire { throw failure } }.exceptionOrNull()
    val second = runCatching { barrier.retire { error("must not execute") } }.exceptionOrNull()

    assertSame(failure, first)
    assertSame(failure, second)
}
```

For the concurrency test, use `CountDownLatch` to hold the owner, start an interrupted waiter, assert it has not returned, release the owner, and assert its interrupt flag is restored. For reentrancy, call `barrier.retire` inside the owner block and assert the cleanup counter remains `1`.

- [ ] **Step 2: Run the new test and verify it fails to compile**

```bash
./gradlew :app:testDebugUnitTest --tests '*RetirementBarrierTest'
```

Expected: `RetirementBarrier` is unresolved.

- [ ] **Step 3: Implement the barrier**

```kotlin
internal class RetirementBarrier {
    private val lock = Any()
    private val completed = CountDownLatch(1)
    private var ownerThread: Thread? = null
    private var result: Result<Unit>? = null

    fun retire(block: () -> Unit) {
        val current = Thread.currentThread()
        val owns = synchronized(lock) {
            result?.let { it.getOrThrow(); return }
            if (ownerThread === current) return
            if (ownerThread == null) {
                ownerThread = current
                true
            } else {
                false
            }
        }
        if (!owns) {
            awaitCompletionUninterruptibly()
            synchronized(lock) { requireNotNull(result) }.getOrThrow()
            return
        }
        val completedResult = runCatching(block)
        synchronized(lock) { result = completedResult }
        completed.countDown()
        completedResult.getOrThrow()
    }

    private fun awaitCompletionUninterruptibly() {
        var interrupted = false
        while (true) {
            try {
                completed.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
```

- [ ] **Step 4: Replace both production uses**

Change `VoiceAgentTelecomRetirement` and `VoiceAudioCaptureRouteLease` to construct `RetirementBarrier`. Delete the old shared-single-flight test from `VoiceAgentTelecomRetirementTest`; its replacement lives in `RetirementBarrierTest`.

```kotlin
private val retirement = RetirementBarrier()

fun retire(block: () -> Unit) {
    retirement.retire(block)
}
```

- [ ] **Step 5: Run focused tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*RetirementBarrierTest' \
  --tests '*VoiceAgentTelecomRetirementTest' \
  --tests '*VoiceAudioRouteControllerTest'
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/RetirementBarrier.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomConnection.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/RetirementBarrierTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomRetirementTest.kt
git add -u app/src/main/java/me/rerere/rikkahub/voiceagent/SingleFlightRetirement.kt
git commit -m "refactor(voice): replay retirement results"
```

---

### Task 2: Make Each Telecom Attempt the Only Connection State Owner

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistry.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistryTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomRetirementTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolverTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt`

**Interfaces:**
- Preserves temporarily: `beginAttempt`, `activate`, `fail`, `observeOutcome`, `acknowledgeOutcome`, `awaitOutcome`, `retireAttempt`, `hasActiveConnection`, and `disconnectActive`.
- Produces: `fun retireOwnedAttempt(id: VoiceAgentTelecomAttemptId)` and `fun isOwnedAttemptActive(id): Boolean`.
- Invariant: `AttemptPhase.Active(connection)` is the only active connection store.

- [ ] **Step 1: Add failing behavior tests**

```kotlin
@Test
fun `acknowledged active outcome still retires exact connection`() = runBlocking {
    val registry = VoiceAgentTelecomCallRegistry()
    val attempt = registry.beginAttempt()
    val call = FakeTelecomCall()
    assertTrue(registry.activate(attempt, call))
    assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))

    registry.retireOwnedAttempt(attempt)

    assertEquals(1, call.disconnectCalls)
    assertFalse(registry.hasActiveConnection())
}
```

Add a second test that activates old and new attempts, retires the old ID, and asserts only the old call disconnects.

- [ ] **Step 2: Verify the new API is missing**

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentTelecomCallRegistryTest'
```

Expected: `retireOwnedAttempt` is unresolved.

- [ ] **Step 3: Replace the phase model**

```kotlin
private sealed interface AttemptPhase {
    data object Pending : AttemptPhase
    data class Activating(val connection: VoiceAgentTelecomCall) : AttemptPhase
    data class Active(val connection: VoiceAgentTelecomCall) : AttemptPhase
    data class Retiring(
        val connection: VoiceAgentTelecomCall,
        val failure: VoiceAgentTelecomFailure,
    ) : AttemptPhase
    data class Failed(val failure: VoiceAgentTelecomFailure) : AttemptPhase
}

private class AttemptRecord {
    val completion = CompletableDeferred<VoiceAgentTelecomOutcome>()
    var phase: AttemptPhase = AttemptPhase.Pending
}
```

Delete `ActiveConnection` and `activeConnection`. Every branch obtains connection identity from the phase.

- [ ] **Step 4: Keep active records after acknowledgement**

```kotlin
fun acknowledgeOutcome(id: VoiceAgentTelecomAttemptId) {
    synchronized(lock) {
        val record = attempts[id] ?: return
        if (record.completion.isCompleted && record.phase is AttemptPhase.Failed) {
            attempts.remove(id)
            if (currentAttemptId == id) currentAttemptId = null
        }
    }
}
```

- [ ] **Step 5: Implement exact retirement without reconstructing records**

```kotlin
fun retireOwnedAttempt(id: VoiceAgentTelecomAttemptId) {
    retireAttempt(id, cancelledFailure(id))
}

fun isOwnedAttemptActive(id: VoiceAgentTelecomAttemptId): Boolean = synchronized(lock) {
    attempts[id]?.phase is AttemptPhase.Active
}
```

An unknown ID returns without creating a record. Under the lock, move pending to failed and activating/active to retiring. Disconnect outside the lock. In `finally`, complete any pending terminal outcome, remove the resource-free record, and clear `currentAttemptId` only if it still equals the retired ID.

Connection callbacks must find their record by connection identity rather than `currentAttemptId`:

```kotlin
private fun attemptForConnectionLocked(
    connection: VoiceAgentTelecomCall,
): Pair<VoiceAgentTelecomAttemptId, AttemptRecord>? = attempts.entries.firstNotNullOfOrNull { entry ->
    val phaseConnection = when (val phase = entry.value.phase) {
        is AttemptPhase.Activating -> phase.connection
        is AttemptPhase.Active -> phase.connection
        is AttemptPhase.Retiring -> phase.connection
        AttemptPhase.Pending,
        is AttemptPhase.Failed,
        -> null
    }
    (entry.key to entry.value).takeIf { phaseConnection === connection }
}
```

Use this helper in both `retiring(connection)` and `clear(connection)`. `clear` removes only the matching record and clears `currentAttemptId` only when its ID matches. Add a test where an old connection clears after a newer attempt becomes active and assert the newer outcome and usability remain unchanged.

- [ ] **Step 6: Derive temporary service queries from records**

```kotlin
fun hasActiveConnection(): Boolean = synchronized(lock) {
    attempts.values.any { it.phase is AttemptPhase.Active }
}

fun disconnectActive() {
    val id = synchronized(lock) { currentAttemptId } ?: return
    retireOwnedAttempt(id)
}
```

- [ ] **Step 7: Remove registry reflection from tests**

Delete `getDeclaredField("attempts")`, `getDeclaredField("phase")`, and registry-lock reflection. Coordinate races with fake timeout, activation, and disconnect latches; assert outcomes and connection call counts.

```kotlin
val activationEntered = CountDownLatch(1)
val releaseActivation = CountDownLatch(1)
val activation = thread {
    registry.activate(attempt, call) {
        activationEntered.countDown()
        check(releaseActivation.await(1, TimeUnit.SECONDS))
    }
}
assertTrue(activationEntered.await(1, TimeUnit.SECONDS))
registry.fail(attempt, failure)
assertFalse(outcome.isCompleted)
releaseActivation.countDown()
activation.join()
assertEquals(VoiceAgentTelecomOutcome.Failed(failure), outcome.await())
```

- [ ] **Step 8: Run Telecom boundary tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentTelecomCallRegistryTest' \
  --tests '*VoiceAgentTelecomRetirementTest' \
  --tests '*VoiceAgentTelecomBoundaryTest' \
  --tests '*VoiceAgentAudioRouteResolverTest' \
  --tests '*VoiceAgentCallStartupTest'
```

Expected: all pass and registry private-field reflection has no matches.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistry.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistryTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomRetirementTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolverTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt
git commit -m "refactor(voice): unify Telecom attempt state"
```

---

### Task 3: Add Session Route Leases and the Owned-Session Decorator

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLease.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt`

**Interfaces:**
- Produces: `VoiceAgentRouteMetadata(owner, failure)` and sealed `VoiceAgentRouteLease` with exact `isUsable` state.
- Produces: `RouteOwnedManagedVoiceCallSession` and `RouteOwnedVoiceCallSession(delegate, routeLease)`.
- Consumes: exact registry retirement and cleanup-stage aggregators.

- [ ] **Step 1: Write failing ownership tests**

Test exact Telecom retirement, exact usability before/after external connection retirement, direct no-op retirement, and `end`, `endAndDrain`, and `closeNow` route-before-session ordering. For two failures assert the route failure is primary and the delegate failure is suppressed.

```kotlin
val thrown = runCatching { owned.endAndDrain() }.exceptionOrNull()
assertSame(routeFailure, thrown)
assertEquals(listOf(sessionFailure), thrown?.suppressed?.toList())
assertEquals(listOf("route-retire", "session-end-and-drain"), events)
```

- [ ] **Step 2: Verify the types are missing**

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentRouteLeaseTest'
```

Expected: lease and decorator types are unresolved.

- [ ] **Step 3: Implement route metadata and leases**

```kotlin
data class VoiceAgentRouteMetadata(
    val owner: VoiceAudioRouteOwner,
    val failure: VoiceAgentTelecomFailure? = null,
)

sealed interface VoiceAgentRouteLease {
    val metadata: VoiceAgentRouteMetadata
    val isUsable: Boolean
    fun retire()
}

internal class TelecomVoiceAgentRouteLease(
    private val attemptId: VoiceAgentTelecomAttemptId,
    private val registry: VoiceAgentTelecomCallRegistry,
) : VoiceAgentRouteLease {
    private val retirement = RetirementBarrier()
    override val metadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom)
    override val isUsable: Boolean
        get() = registry.isOwnedAttemptActive(attemptId)
    override fun retire() = retirement.retire { registry.retireOwnedAttempt(attemptId) }
}

internal class DirectFallbackVoiceAgentRouteLease(
    failure: VoiceAgentTelecomFailure,
) : VoiceAgentRouteLease {
    override val metadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback, failure)
    override val isUsable = true
    override fun retire() = Unit
}
```

- [ ] **Step 4: Implement the managed-session decorator**

Define the owned interface and delegate nonterminal methods directly:

```kotlin
interface RouteOwnedManagedVoiceCallSession : ManagedVoiceCallSession {
    val routeMetadata: VoiceAgentRouteMetadata
    val isRouteUsable: Boolean
}
```

The decorator properties are `routeMetadata = routeLease.metadata` and `isRouteUsable get() = routeLease.isUsable`. Terminal methods are:

```kotlin
override fun end() = runVoiceAgentCleanupStages(routeLease::retire, delegate::end)

override suspend fun endAndDrain() = runVoiceAgentSuspendCleanupStages(
    { routeLease.retire() },
    delegate::endAndDrain,
)

override fun closeNow() = runVoiceAgentCleanupStages(routeLease::retire, delegate::closeNow)
```

Expose `val routeMetadata = routeLease.metadata` for manager snapshots.

- [ ] **Step 5: Run focused tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentRouteLeaseTest' \
  --tests '*VoiceAgentTelecomCallRegistryTest'
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLease.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt
git commit -m "feat(voice): add owned route leases"
```

---

### Task 4: Transfer Leases Through Resolver, Startup, Factory, and Manager

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolver.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartup.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolverTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`

**Interfaces:**
- Produces: `VoiceAgentAudioRouteResolver.resolve(): VoiceAgentRouteLease`.
- Produces: `VoiceAgentCallFactory.create(conversationId, config, routeLease, scope): RouteOwnedManagedVoiceCallSession`.
- Produces: manager `matchingRouteMetadata` and lease-consuming `start`.
- Startup results contain metadata, never an owning lease.

- [ ] **Step 1: Rewrite resolver tests around leases**

For Telecom success, retire the returned lease and assert its exact fake connection disconnects once:

```kotlin
val lease = resolver.resolve()
assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
lease.retire()
assertEquals(1, telecomCall.disconnectCalls)
```

For fallback, assert `lease.metadata.failure` is the existing failure and retirement does not affect any newer attempt.

- [ ] **Step 2: Rewrite startup and manager ownership tests**

Use counting fake leases and assert:

```kotlin
assertEquals(0, resolveCalls)
assertEquals(1, staleLease.retireCalls)
assertEquals(1, raceRejectedLease.retireCalls)
assertEquals(1, factoryFailureLease.retireCalls)
assertEquals(1, previousSessionLease.retireCalls)
assertEquals(0, installedLiveLease.retireCalls)
```

Delete constructions of `VoiceAgentAudioRouteResolution` and assertions against `activeRouteOwner`.

- [ ] **Step 3: Verify the rewritten tests fail**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentAudioRouteResolverTest' \
  --tests '*VoiceAgentCallStartupTest' \
  --tests '*VoiceAgentCallManagerTest'
```

Expected: old enum-only signatures do not compile.

- [ ] **Step 4: Make the resolver return exact leases**

Delete `VoiceAgentAudioRouteResolution`. Map outcomes exactly:

```kotlin
VoiceAgentTelecomOutcome.Active -> {
    registry.acknowledgeOutcome(attempt)
    TelecomVoiceAgentRouteLease(attempt, registry)
}

is VoiceAgentTelecomOutcome.Failed -> {
    registry.acknowledgeOutcome(attempt)
    DirectFallbackVoiceAgentRouteLease(outcome.failure)
}
```

The timeout and gateway-failure paths return the same two lease types after their existing retirement barrier. Cancellation still retires and awaits in `NonCancellable` before rethrowing.

- [ ] **Step 5: Make the factory consume and wrap the lease**

Change its signature to:

```kotlin
fun create(
    conversationId: Uuid,
    config: VoiceAgentLaunchConfig,
    routeLease: VoiceAgentRouteLease,
    scope: CoroutineScope,
): RouteOwnedManagedVoiceCallSession
```

Read `val route = routeLease.metadata`, pass `route.owner` to the audio factory and E2E metadata, create the core session, and return `RouteOwnedVoiceCallSession(coreSession, routeLease)`. Preserve factory failure ordering with:

```kotlin
catch (creationError: Throwable) {
    runCatching(routeLease::retire)
        .exceptionOrNull()
        ?.let(creationError::addSuppressed)
    throw creationError
}
```

- [ ] **Step 6: Replace manager parallel fields with one aggregate**

```kotlin
private data class ActiveVoiceCall(
    val conversationId: Uuid,
    val launchConfig: VoiceAgentLaunchConfig,
    val route: VoiceAgentRouteMetadata,
    val session: RouteOwnedManagedVoiceCallSession,
)

private var activeCall: ActiveVoiceCall? = null
```

Retain `_activeConversationId` as the existing UI flow. Delete `_activeRouteOwner`, `activeSession`, and `activeLaunchConfig`.

- [ ] **Step 7: Implement matching and lease-consuming installation**

```kotlin
fun matchingRouteMetadata(
    conversationId: Uuid,
    config: VoiceAgentLaunchConfig,
): VoiceAgentRouteMetadata? = synchronized(lock) {
    activeCall?.takeIf {
        it.conversationId == conversationId && it.launchConfig == config
    }?.route
}
```

Change `start` to accept a lease. Recheck matching under the lock. On a duplicate race, retire the unused incoming lease and return `false`. If previous-session end fails before calling the factory, retire the incoming lease before rethrowing. Once the factory is called it consumes the lease on success or failure; the manager must not perform a second ownership action. After the factory returns the route-owned session, install one `ActiveVoiceCall`; that session owns all later retirement.

- [ ] **Step 8: Migrate startup to reuse before resolution**

```kotlin
sealed interface VoiceAgentCallStartupResult {
    val route: VoiceAgentRouteMetadata

    data class Started(
        override val route: VoiceAgentRouteMetadata,
        val startedNewSession: Boolean,
    ) : VoiceAgentCallStartupResult

    data class Stale(
        override val route: VoiceAgentRouteMetadata,
    ) : VoiceAgentCallStartupResult
}
```

Return `Started(existing, false)` before resolution when manager metadata matches. Otherwise resolve one lease. If stale, retire it and return `Stale(metadata)`. If current, call manager `start`; the manager consumes or retires the lease on every return path.

- [ ] **Step 9: Update DI and runtime fakes**

Remove the registry constructor argument from `VoiceAgentCallStartup`. Update `AppModule`, test factories, and `VoiceAgentRuntimeTest` to accept `VoiceAgentRouteLease` and record `routeLease.metadata.owner` for assertions.

Add manager exact-route preservation:

```kotlin
fun canPreserveActiveSession(conversationId: Uuid): Boolean = synchronized(lock) {
    activeCall?.takeIf { it.conversationId == conversationId }
        ?.session
        ?.isRouteUsable == true
}
```

- [ ] **Step 10: Run ownership suites**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentRouteLeaseTest' \
  --tests '*VoiceAgentAudioRouteResolverTest' \
  --tests '*VoiceAgentCallStartupTest' \
  --tests '*VoiceAgentCallManagerTest' \
  --tests '*VoiceAgentRuntimeTest'
```

Expected: all pass. `rg 'VoiceAgentAudioRouteResolution|telecomAttemptId|_activeRouteOwner|routeOwnerForActiveSession' app/src` returns no matches.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolver.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartup.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt \
  app/src/main/java/me/rerere/rikkahub/di/AppModule.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolverTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt
git commit -m "refactor(voice): transfer exact route leases"
```

---

### Task 5: Remove Service-Level Telecom Ownership Inference

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistry.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceCleanupTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServicePolicyTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistryTest.kt`

**Interfaces:**
- Consumes: route-owned managed sessions.
- Deletes: service registry injection, `retireTelecomCall`, cleanup-plan inference, and the seven-lambda helper.
- Preserves: generation guards for foreground and service termination.

- [ ] **Step 1: Move cleanup failure assertions to the owned-session tests**

Add route-retirement failure tests to `VoiceAgentRouteLeaseTest`. In the service cleanup test, retain generation behavior through:

```kotlin
internal suspend fun completeVoiceAgentEndForGeneration(
    isCurrent: () -> Boolean,
    endAndDrain: suspend () -> Unit,
    onCompleted: suspend () -> Unit,
) {
    if (!isCurrent()) return
    endAndDrain()
    if (isCurrent()) onCompleted()
}
```

Assert a generation change during drain prevents `onCompleted`.

- [ ] **Step 2: Verify service tests fail against old helpers**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallServiceCleanupTest' \
  --tests '*VoiceAgentCallServicePolicyTest' \
  --tests '*VoiceAgentRouteLeaseTest'
```

Expected: rewritten tests do not compile until the service boundary changes.

- [ ] **Step 3: Remove registry cleanup from the service**

Delete registry injection and every `disconnectActive`, `hasActiveConnection`, and `retireTelecomCall` call. Then delete `disconnectActive()` and `hasActiveConnection()` from the registry and migrate their remaining tests to exact attempt IDs and `isOwnedAttemptActive(id)`. Explicit end detaches the route-owned session, calls `endAndDrain`, then performs completion/foreground/self cleanup only while its generation is current.

```kotlin
val session = manager.detachForEndAndDrain()
endJob = serviceScope.launch {
    completeVoiceAgentEndForGeneration(
        isCurrent = { endGeneration == callGeneration },
        endAndDrain = { session?.endAndDrain() },
        onCompleted = {
            VoiceAgentLog.d(TAG, "end completed conversationId=${endingConversationId ?: "none"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            endJob = null
        },
    )
}
```

- [ ] **Step 4: Simplify failed-start cleanup around actual session presence**

```kotlin
val preserveSession = preserveSessionRequested &&
    manager.canPreserveActiveSession(Uuid.parse(conversationId))
```

If preserved, record diagnostics and degraded state. Otherwise call `manager.closeNow()`. Add tests proving an active exact Telecom lease is preserved, a retired Telecom lease is closed, and direct fallback remains preservable. Continue all notification/status stages through `runVoiceAgentCleanupStages`.

- [ ] **Step 5: Delete obsolete policy and dead reconnect state**

Delete `VoiceAgentFailedStartCleanupPlan`, `voiceAgentFailedStartCleanupPlan`, `runVoiceAgentEndCleanupForGeneration`, and `observedReconnectAttempt`. Keep all three existing `shouldPublishVoiceCallBackgroundCapable` tests in `VoiceAgentCallServicePolicyTest`. The new-session startup predicate directly accepts connected, error, ended, or generation change.

```kotlin
val startupState = manager.state.first { state ->
    startGeneration != callGeneration ||
        state.session == VoiceSessionStatus.Connected ||
        state.session is VoiceSessionStatus.Error ||
        state.session == VoiceSessionStatus.Ended
}
```

- [ ] **Step 6: Run service and ownership tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallServiceCleanupTest' \
  --tests '*VoiceAgentCallServicePolicyTest' \
  --tests '*VoiceAgentCallStartupTest' \
  --tests '*VoiceAgentCallManagerTest' \
  --tests '*VoiceAgentRouteLeaseTest'
```

Expected: all pass and service search finds no deleted ownership APIs.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistry.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceCleanupTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServicePolicyTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistryTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt
git commit -m "refactor(voice): retire routes through sessions"
```

---

### Task 6: Replace Paired Capture Hooks with a Capture Lease

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteController.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteControllerTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt`

**Interfaces:**
- Produces: `VoiceAudioRouteController.acquireCapture(): VoiceAudioCaptureRouteLease`.
- Produces: capture lease `configureRecorder(AudioRecord)` and `retire()`.
- Deletes: controller `beforeCapture`, `configureRecorder`, and `afterCapture` pairing.

- [ ] **Step 1: Rewrite controller tests around acquisition**

```kotlin
val lease = controller.acquireCapture()
lease.configureRecorder(recorder)
lease.retire()
lease.retire()
assertEquals(listOf("acquire", "configure", "retire"), events)
```

Assert Telecom's lease performs no direct mutation.

- [ ] **Step 2: Update engine failure tests**

Make the fake controller return a counting lease. Assert recorder creation, initialization, publication, start, stop, and engine release each retire the exact acquired lease once.

```kotlin
private class FakeCaptureRouteLease : VoiceAudioCaptureRouteLease {
    var configureCalls = 0
    var retireCalls = 0

    override fun configureRecorder(recorder: AudioRecord) {
        configureCalls += 1
    }

    override fun retire() {
        retireCalls += 1
    }
}
```

- [ ] **Step 3: Verify audio tests fail**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*AndroidDirectAudioRouteControllerTest'
```

Expected: `acquireCapture` is unresolved.

- [ ] **Step 4: Define the new route boundary**

```kotlin
internal interface VoiceAudioCaptureRouteLease {
    fun configureRecorder(recorder: AudioRecord)
    fun retire()
}

internal interface VoiceAudioRouteController {
    fun acquireCapture(): VoiceAudioCaptureRouteLease
    fun close()
}
```

The Telecom controller returns one private no-op lease.

Delete the old concrete `VoiceAudioCaptureRouteLease` class from `AndroidVoiceAudioEngine.kt`; the engine must depend only on this interface.

- [ ] **Step 5: Adapt direct routing without policy changes**

Make existing setup/configure/teardown methods private. `acquireCapture` calls setup and returns an object whose configure method invokes existing recorder routing and whose `RetirementBarrier` calls teardown once.

```kotlin
override fun acquireCapture(): VoiceAudioCaptureRouteLease {
    prepareForCapture()
    val retirement = RetirementBarrier()
    return object : VoiceAudioCaptureRouteLease {
        override fun configureRecorder(recorder: AudioRecord) {
            configureCaptureRecorder(recorder)
        }

        override fun retire() {
            retirement.retire(::clearAfterCapture)
        }
    }
}
```

- [ ] **Step 6: Migrate the engine**

Replace setup and wrapper construction with:

```kotlin
val routeLease = routeController.acquireCapture()
```

Replace controller recorder configuration with `routeLease.configureRecorder(recorder)`. Keep every existing failure and termination branch retiring the same lease.

- [ ] **Step 7: Run audio lifecycle tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*AndroidDirectAudioRouteControllerTest' \
  --tests '*VoiceAgentRuntimeTest'
```

Expected: all pass and Voice Agent route-controller search finds no `beforeCapture` or `afterCapture`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteController.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteControllerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt
git commit -m "refactor(voice): acquire capture route leases"
```

---

### Task 7: Extract Focused Android Direct-Audio Capabilities

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt`

**Interfaces:**
- Produces: focus, communication-mode, Bluetooth-capture, and capture-device capabilities.
- Produces: `DirectAudioResourceLease { fun retire() }`.
- Prepares deletion of `DirectAudioRoutePlatform` in Task 8.

- [ ] **Step 1: Replace the broad fake with focused failing fakes**

Construct four independent fake capabilities. Assert acquisition order:

```kotlin
assertEquals(
    listOf("focus-acquire", "mode-acquire", "bluetooth-acquire"),
    events,
)
```

After recorder configuration and retirement, assert device, Bluetooth, mode, then focus retirement.

- [ ] **Step 2: Verify capability types are missing**

```bash
./gradlew :app:testDebugUnitTest --tests '*AndroidDirectAudioRouteControllerTest'
```

Expected: focused capability types are unresolved.

- [ ] **Step 3: Define focused contracts**

```kotlin
internal fun interface DirectAudioResourceLease {
    fun retire()
}

internal fun interface DirectAudioFocusCapability {
    fun acquire(onFocusChange: (Int) -> Unit): DirectAudioResourceLease?
}

internal fun interface DirectCommunicationModeCapability {
    fun acquire(): DirectAudioResourceLease?
}

internal fun interface DirectBluetoothCaptureCapability {
    fun acquire(): DirectAudioResourceLease?
}

internal fun interface DirectCaptureDeviceCapability {
    fun configure(recorder: AudioRecord): DirectAudioResourceLease?
}

internal data class DirectAudioRouteCapabilities(
    val focus: DirectAudioFocusCapability,
    val communicationMode: DirectCommunicationModeCapability,
    val bluetoothCapture: DirectBluetoothCaptureCapability,
    val captureDevice: DirectCaptureDeviceCapability,
    val close: () -> Unit,
)
```

- [ ] **Step 4: Move Android-only integration into the capability file**

Move the system platform implementation, Android handles, audio attributes, permission checks, device mapping, and Bluetooth profile integration out of the controller file. Initially preserve behavior behind private system capability classes; this step changes boundaries, not policy.

```kotlin
private class SystemDirectAudioFocusCapability(
    private val audioManager: AudioManager?,
) : DirectAudioFocusCapability {
    override fun acquire(onFocusChange: (Int) -> Unit): DirectAudioResourceLease? =
        acquireSystemAudioFocus(audioManager, onFocusChange)
}

private class SystemDirectCommunicationModeCapability(
    private val audioManager: AudioManager?,
) : DirectCommunicationModeCapability {
    override fun acquire(): DirectAudioResourceLease? =
        acquireSystemCommunicationMode(audioManager)
}
```

Define `acquireSystemAudioFocus` and `acquireSystemCommunicationMode` in this file by moving the existing request/abandon and enter/restore bodies unchanged; each returned lease wraps its release with `RetirementBarrier`.

- [ ] **Step 5: Inject the capability aggregate into the controller**

```kotlin
internal class AndroidDirectAudioRouteController(
    private val capabilities: DirectAudioRouteCapabilities,
    private val onAudioError: (String) -> Unit,
) : VoiceAudioRouteController
```

The context constructor calls `systemDirectAudioRouteCapabilities(context.applicationContext)`.

Create the aggregate with one shared Android `AudioManager` and independently constructed implementations:

```kotlin
internal fun systemDirectAudioRouteCapabilities(context: Context): DirectAudioRouteCapabilities {
    val audioManager = context.getSystemService(AudioManager::class.java)
    return DirectAudioRouteCapabilities(
        focus = SystemDirectAudioFocusCapability(audioManager),
        communicationMode = SystemDirectCommunicationModeCapability(audioManager),
        bluetoothCapture = SystemDirectBluetoothCaptureCapability(context, audioManager),
        captureDevice = SystemDirectCaptureDeviceCapability(context, audioManager),
        close = {},
    )
}
```

If a capability owns a coroutine scope or Bluetooth listener, its own lease closes that resource; the aggregate `close` cancels only factory-level resources that outlive a capture.

- [ ] **Step 6: Run direct routing tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*AndroidDirectAudioRouteControllerTest' \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*VoiceAudioRouteSelectorTest'
```

Expected: all pass. The controller file contains no `AudioManager`, `BluetoothHeadset`, `BluetoothProfile`, Android handle casts, or `Thread.sleep`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt
git commit -m "refactor(voice): split direct audio capabilities"
```

---

### Task 8: Make Direct Android Resources Capture-Scoped and Composable

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteControllerTest.kt`

**Interfaces:**
- Consumes: focused capabilities from Task 7.
- Produces: one capture lease that appends successful resource leases and retires them in reverse order.
- Deletes: controller-level focus, SCO, profile, recognition, mode, and device flags.

- [ ] **Step 1: Add failing rollback and repeat-cycle tests**

For Bluetooth acquisition failure, assert:

```kotlin
assertEquals(
    listOf("focus-acquire", "mode-acquire", "mode-retire", "focus-retire"),
    events,
)
```

Run two capture cycles and assert all four capabilities acquire and retire twice without shared state.

- [ ] **Step 2: Add late-callback and policy tests**

After retiring a capture before Bluetooth connection, deliver the profile callback and assert the proxy closes once while voice recognition and SCO do not start. Assert denied nonfatal focus, missing Bluetooth permission, unavailable profile, rejected recognition, and device-selection failure remain nonfatal. Assert the existing fatal focus-policy result still throws after rollback.

```kotlin
val lease = controller.acquireCapture()
lease.retire()
bluetooth.deliverConnectedHeadset()
assertEquals(1, bluetooth.closedProxyCalls)
assertEquals(0, bluetooth.voiceRecognitionStartCalls)
assertEquals(0, bluetooth.scoStartCalls)

focus.result = AudioManager.AUDIOFOCUS_REQUEST_FAILED
assertNotNull(controller.acquireCapture())
focus.result = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
assertThrows(IllegalStateException::class.java) { controller.acquireCapture() }
```

- [ ] **Step 3: Verify the new tests fail**

```bash
./gradlew :app:testDebugUnitTest --tests '*AndroidDirectAudioRouteControllerTest'
```

Expected: rollback, independent-cycle, or late-callback assertions fail against the controller ledger.

- [ ] **Step 4: Implement the per-capture composite**

Use this synchronized add-or-retire structure:

```kotlin
private class DirectVoiceAudioCaptureRouteLease(
    private val captureDevice: DirectCaptureDeviceCapability,
    initialLeases: List<DirectAudioResourceLease>,
    private val logWarning: (String, Throwable?) -> Unit,
) : VoiceAudioCaptureRouteLease {
    private val lock = Any()
    private val retirement = RetirementBarrier()
    private val leases = initialLeases.toMutableList()
    private var retired = false

    override fun configureRecorder(recorder: AudioRecord) {
        val lease = runCatching { captureDevice.configure(recorder) }
            .onFailure { logWarning("Direct capture device configuration failed", it) }
            .getOrNull() ?: return
        val retireImmediately = synchronized(lock) {
            if (retired) true else {
                leases += lease
                false
            }
        }
        if (retireImmediately) retireBestEffort(lease)
    }

    override fun retire() = retirement.retire {
        val owned = synchronized(lock) {
            retired = true
            leases.toList().asReversed().also { leases.clear() }
        }
        owned.forEach(::retireBestEffort)
    }

    private fun retireBestEffort(lease: DirectAudioResourceLease) {
        runCatching(lease::retire)
            .onFailure { logWarning("Direct audio resource retirement failed", it) }
    }
}
```

- [ ] **Step 5: Acquire capabilities with immediate rollback**

In `acquireCapture`, build a local list in focus, mode, Bluetooth order and append each success immediately:

```kotlin
override fun acquireCapture(): VoiceAudioCaptureRouteLease {
    check(!closed) { "Direct audio route controller is closed" }
    val acquired = mutableListOf<DirectAudioResourceLease>()
    try {
        capabilities.focus.acquire { focusChange ->
            if (VoiceAudioFocusPolicy.isFocusChangeFatal(focusChange)) {
                onAudioError("Audio focus lost: $focusChange")
            } else if (focusChange < 0) {
                logWarning("Recoverable direct audio focus change: $focusChange")
            }
        }?.let(acquired::add)
        runCatching(capabilities.communicationMode::acquire)
            .onFailure { logWarning("Direct communication mode setup failed", it) }
            .getOrNull()
            ?.let(acquired::add)
        runCatching(capabilities.bluetoothCapture::acquire)
            .onFailure { logWarning("Direct Bluetooth capture setup failed", it) }
            .getOrNull()
            ?.let(acquired::add)
        return DirectVoiceAudioCaptureRouteLease(
            captureDevice = capabilities.captureDevice,
            initialLeases = acquired,
            logWarning = ::logWarning,
        )
    } catch (fatal: Throwable) {
        acquired.asReversed().forEach { lease ->
            runCatching(lease::retire)
                .onFailure { logWarning("Direct audio rollback failed", it) }
        }
        throw fatal
    }
}
```

The focus capability returns `null` for current nonfatal request results and throws only when `VoiceAudioFocusPolicy.isRequestFailureFatal(result)` is true. Other capability acquisition errors remain best effort as shown.

- [ ] **Step 6: Encapsulate Bluetooth callbacks in one acquisition**

The system Bluetooth capability creates one object per capture. That object alone stores proxy, headset, recognition device, SCO state, listener, and closed state. Retirement marks closed before Android calls. Late `onServiceConnected` closes its proxy and returns without routing mutations.

```kotlin
private class SystemBluetoothCaptureLease(
    private val closeProxy: (BluetoothHeadset) -> Unit,
    private val stopRouting: (BluetoothHeadset?, BluetoothDevice?) -> Unit,
) : DirectAudioResourceLease {
    private val lock = Any()
    private val retirement = RetirementBarrier()
    private var closed = false
    private var headset: BluetoothHeadset? = null
    private var recognitionDevice: BluetoothDevice? = null

    fun onConnected(connected: BluetoothHeadset) {
        val reject = synchronized(lock) {
            if (closed) true else false.also { headset = connected }
        }
        if (reject) closeProxy(connected)
    }

    override fun retire() = retirement.retire {
        val owned = synchronized(lock) {
            closed = true
            (headset to recognitionDevice).also {
                headset = null
                recognitionDevice = null
            }
        }
        stopRouting(owned.first, owned.second)
    }
}
```

- [ ] **Step 7: Delete the broad platform and controller ledger**

Remove `DirectAudioRoutePlatform`, marker handles, and the broad fake. Remove controller fields for focus handle/state, selected communication device, SCO, profile request, recognition intent/device, headset, and previous mode. The controller retains only immutable capabilities, error callback, closed state, and logging.

```kotlin
internal class AndroidDirectAudioRouteController(
    private val capabilities: DirectAudioRouteCapabilities,
    private val onAudioError: (String) -> Unit,
) : VoiceAudioRouteController {
    private val lock = Any()
    private var closed = false
}
```

Verify deletion immediately:

```bash
if rg -n 'DirectAudioRoutePlatform|audioFocusHandle|hasStartedBluetoothSco|bluetoothProfileProxyRequested|wantsBluetoothHeadsetVoiceRecognition|previousAudioMode' \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt; then
  exit 1
fi
```

- [ ] **Step 8: Run focused and runtime audio tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*AndroidDirectAudioRouteControllerTest' \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*VoiceAgentRuntimeTest'
```

Expected: all pass; repeated captures are independent; Telecom performs zero direct mutations.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteControllerTest.kt
git commit -m "refactor(voice): scope direct audio resources to capture"
```

---

### Task 9: Deletion Audit, Full Verification, APK Install, and Voice E2E

**Files:**
- Verify: all files changed in Tasks 1–8.
- Verify: `app/build/outputs/apk/debug/app-universal-debug.apk`.
- Verify: `build/voice-agent-e2e/report.txt` and generated trace artifacts.

**Interfaces:**
- Consumes: completed lease architecture.
- Produces: exact-head unit, build, device, and trace evidence.

- [ ] **Step 1: Run the obsolete-path audit**

```bash
if rg -n \
  'SingleFlightRetirement|VoiceAgentAudioRouteResolution|telecomAttemptId|_activeRouteOwner|routeOwnerForActiveSession|VoiceAgentFailedStartCleanupPlan|runVoiceAgentEndCleanupForGeneration|observedReconnectAttempt|activeConnection|disconnectActive|hasActiveConnection|DirectAudioRoutePlatform|beforeCapture|afterCapture|getDeclaredField\("(attempts|phase)"' \
  app/src/main app/src/test; then
  exit 1
fi
```

Expected: no matches.

- [ ] **Step 2: Run focused ownership suites**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*RetirementBarrierTest' \
  --tests '*VoiceAgentTelecomCallRegistryTest' \
  --tests '*VoiceAgentTelecomRetirementTest' \
  --tests '*VoiceAgentAudioRouteResolverTest' \
  --tests '*VoiceAgentRouteLeaseTest' \
  --tests '*VoiceAgentCallStartupTest' \
  --tests '*VoiceAgentCallManagerTest' \
  --tests '*VoiceAgentCallServiceCleanupTest' \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*AndroidDirectAudioRouteControllerTest'
```

Expected: all selected tests pass.

- [ ] **Step 3: Run fresh full verification**

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain
scripts/test-voice-agent-sentry-build.sh
git diff --check
```

Expected: full tests pass, the Sentry harness prints `voice-agent-sentry-build tests passed.`, and diff check is silent.

- [ ] **Step 4: Build the universal APK**

```bash
./gradlew :app:assembleDebug --console=plain
test -f app/build/outputs/apk/debug/app-universal-debug.apk
```

Expected: build succeeds and the universal debug APK exists.

- [ ] **Step 5: Install on the current wireless ADB phone**

```bash
: "${VOICE_AGENT_E2E_SERIAL:?Set VOICE_AGENT_E2E_SERIAL to the current 100.97.115.82 wireless ADB serial}"
adb connect "$VOICE_AGENT_E2E_SERIAL"
scripts/adb-device-ready.sh "$VOICE_AGENT_E2E_SERIAL"
adb -s "$VOICE_AGENT_E2E_SERIAL" install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

Expected: readiness identifies one authorized booted phone and install reports `Success`.

- [ ] **Step 6: Run installed Telecom Voice Agent/Hermes E2E**

```bash
: "${VOICE_AGENT_E2E_CONVERSATION_ID:?Set VOICE_AGENT_E2E_CONVERSATION_ID to the configured debug conversation UUID}"
VOICE_AGENT_E2E_SERIAL="$VOICE_AGENT_E2E_SERIAL" \
VOICE_AGENT_E2E_CONVERSATION_ID="$VOICE_AGENT_E2E_CONVERSATION_ID" \
scripts/voice-agent-hermes-gbrain-e2e.sh
```

Expected: the harness passes or reaches its documented manual gate after all automated assertions pass.

- [ ] **Step 7: Inspect trace invariants**

```bash
grep -E \
  'Voice audio route owner=|available call endpoints=|call endpoint changed|Voice capture level|Voice playback (active|drained)|hermes_queue_event|end completed' \
  build/voice-agent-e2e/logcat.txt
sed -n '1,240p' build/voice-agent-e2e/report.txt
```

Expected: one immutable owner; Telecom has no direct focus/SCO/recognition logs; capture and playback complete; queued Hermes announcements do not interrupt physical playback; end completes once.

- [ ] **Step 8: Run direct-fallback smoke and always restore Telecom**

```bash
COMPONENT='me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentConnectionService'
restore_telecom() {
  adb -s "$VOICE_AGENT_E2E_SERIAL" shell pm enable "$COMPONENT" >/dev/null
}
trap restore_telecom EXIT INT TERM
adb -s "$VOICE_AGENT_E2E_SERIAL" shell pm disable-user --user 0 "$COMPONENT"
VOICE_AGENT_E2E_LOG_DIR=build/voice-agent-e2e-direct \
VOICE_AGENT_E2E_SERIAL="$VOICE_AGENT_E2E_SERIAL" \
VOICE_AGENT_E2E_CONVERSATION_ID="$VOICE_AGENT_E2E_CONVERSATION_ID" \
scripts/voice-agent-hermes-gbrain-e2e.sh
restore_telecom
trap - EXIT INT TERM
```

Expected: owner is `DirectFallback`; existing failure diagnostics remain; direct focus/mode/Bluetooth routing, capture, playback, and cleanup succeed; Telecom is re-enabled even on failure.

- [ ] **Step 9: Confirm clean status**

```bash
git status --short
git diff --check
```

Expected: no uncommitted changes. Do not commit APKs, logs, reports, or traces. If verification required a correction, rerun its focused suite and Step 3 before committing it as `fix(voice): preserve route lease invariants`.

---

## Completion Checklist

- [ ] Every installed session owns exactly one session route lease.
- [ ] Matching-session reuse performs no route resolution.
- [ ] Stale, rejected, and failed-transfer leases retire once.
- [ ] Telecom phase and connection identity have one source of truth.
- [ ] Service cleanup never queries or disconnects a global active Telecom call.
- [ ] Every direct capture owns independent capability leases.
- [ ] Direct cleanup is reverse-order and preserves best-effort policy.
- [ ] Telecom capture performs zero direct Android routing mutations.
- [ ] Registry reflection and the broad direct platform fake are removed.
- [ ] Full unit, Sentry harness, universal APK, wireless install, Telecom E2E, and fallback smoke verification pass.
