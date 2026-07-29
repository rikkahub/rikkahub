# Voice Call Orchestrator State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the split voice call manager/startup/service ownership model with one typed orchestrator that makes startup, active publication, replacement, stopping, cleanup failure, and retry legal only through explicit states.

**Architecture:** A pure reducer owns transition rules while `VoiceAgentCallOrchestrator` executes route, factory, session, collector, and cleanup effects outside its lock. The orchestrator materializes an admitted unstarted operation during the locked transition so it is owned before unlock. Typed start and cleanup operations carry exact resources; a complete active-call bundle is published atomically, and cleanup failure retains one retryable cleanup owner rather than fences, claims, nullable publication state, or local ownership flags.

**Tech Stack:** Kotlin, Android foreground `Service`, Koin, kotlinx.coroutines (`CompletableDeferred`, `CoroutineScope`, `Job`, `StateFlow`), JUnit 4, kotlinx-coroutines-test, Gradle.

## Global Constraints

- Work only in the standalone repository at `/home/muly/code/rikkahub`; do not create or edit `/home/muly/code/agora2/external/rikkahub`.
- The newest different call request wins. Matching means equal conversation ID and equal resolved `VoiceAgentLaunchConfig`.
- Matching starting, active, or pending-replacement requests share one call and do not resolve another route.
- Replacement never begins route preparation until every resource owned by the old active call or superseded startup reaches successful cleanup.
- Cleanup failure fails the pending start and publishes `CleanupFailed`; one later start triggers one new attempt of the exact unfinished cleanup.
- Keep Telecom-first routing, direct fallback, mute, reconnect, playback, persistence, notification appearance, and voice protocol behavior unchanged.
- No route, factory, session, collector, deferred wait/completion, job cancellation/join, or cleanup call runs while holding the orchestrator lock.
- The first failure remains primary; later cleanup failures are suppressed in execution order; canonical coroutine cancellation remains cancellation.
- Keep logs credential-safe through existing `toVoiceAgentLogDetail()` and `redactForVoiceAgentLog()`; never log `VoiceAgentLaunchConfig.credentials` or raw settings/conversation payloads.
- Add no dependency and no final compatibility wrapper for `VoiceAgentCallManager`, `VoiceAgentCallStartup`, manager result types, detached sessions, cleanup fences, cleanup claims, or pending publication.
- Preserve Kotlin formatting: four-space indentation and 120-character maximum line length.

## Binding Plan Contract

### File Ownership

- `app/src/main/java/me/rerere/rikkahub/voiceagent/RetryableRetirement.kt` owns synchronous exact-attempt joining, success finality, and failure retry admission for route retirement only. Do not alter the generic `RetirementBarrier`, which remains used by audio and connection owners outside this scope.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCleanupOperation.kt` owns cleanup modes, results, per-stage progress, concurrent attempt joining, active-call cleanup, and retry behavior.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStateMachine.kt` owns immutable call states, events, effects, transition rules, request/result contracts, and active lifecycle projections.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartOperation.kt` owns the sealed route/factory/session startup phases and the startup cleanup handle.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestrator.kt` is the sole mutable call authority and effect runner; it owns public state flows and active commands.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLease.kt` and `VoiceAgentTelecomCallRegistry.kt` own retryable exact Telecom retirement without changing route-selection policy.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt` owns route-plus-delegate cleanup composition.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt` owns the typed lease-consumption result.
- `VoiceAgentCallService.kt` and `VoiceAgentCallServiceLifecycle.kt` own Android intent/configuration/foreground ordering only; neither may own or detach call resources.
- `AppModule.kt` and `VoiceAgentRoute.kt` consume the singleton orchestrator.

### Exact Public and Internal Interfaces

Add these contracts in `VoiceAgentCallStateMachine.kt`:

```kotlin
internal data class VoiceAgentCallRequest(
    val conversationId: Uuid,
    val config: VoiceAgentLaunchConfig,
)

internal sealed interface VoiceAgentCallStartResult {
    data class Active(val route: VoiceAgentRouteMetadata) : VoiceAgentCallStartResult
    data object Superseded : VoiceAgentCallStartResult
    data class Failed(val error: Throwable) : VoiceAgentCallStartResult
}

internal sealed interface VoiceAgentCallEndResult {
    data object Completed : VoiceAgentCallEndResult
    data class Failed(val error: Throwable) : VoiceAgentCallEndResult
}

internal sealed interface VoiceAgentCallLifecycle {
    data object Idle : VoiceAgentCallLifecycle
    data class Starting(val conversationId: Uuid) : VoiceAgentCallLifecycle
    data class Active(val conversationId: Uuid) : VoiceAgentCallLifecycle
    data class Stopping(val conversationId: Uuid?) : VoiceAgentCallLifecycle
    data class CleanupFailed(val error: Throwable) : VoiceAgentCallLifecycle
}
```

Add these cleanup contracts in `VoiceAgentCleanupOperation.kt`:

```kotlin
internal enum class VoiceAgentCleanupMode {
    Replacement,
    GracefulEnd,
    Immediate,
}

internal sealed interface VoiceAgentCleanupResult {
    data object Completed : VoiceAgentCleanupResult
    data class Failed(val error: Throwable) : VoiceAgentCleanupResult
}

internal interface VoiceAgentCleanupOperation {
    val token: Any
    suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult
}

internal fun voiceAgentRouteCleanupOperation(
    routeLease: VoiceAgentRouteLease,
): VoiceAgentCleanupOperation

internal fun voiceAgentSessionCleanupOperation(
    delegate: ManagedVoiceCallSession,
    routeLease: VoiceAgentRouteLease,
    endDrainTimeoutMillis: Long,
): VoiceAgentCleanupOperation

internal fun activeVoiceAgentCallCleanupOperation(
    collector: Job,
    callJob: Job,
    sessionCleanup: VoiceAgentCleanupOperation,
): VoiceAgentCleanupOperation
```

`run` admits at most one external cleanup attempt at a time. Concurrent callers join the same attempt and receive the same result identity. Success is terminal. Failure publishes after all stages run, retains only unfinished stages, and permits a later explicit call to make one new attempt. If all stages finished but an attempt still reported timeout or another canonical failure, the next explicit call returns `Completed` without repeating a stage.

Change the factory boundary to this final contract:

```kotlin
internal sealed interface VoiceAgentSessionCreationResult {
    data class Created(
        val session: RouteOwnedManagedVoiceCallSession,
    ) : VoiceAgentSessionCreationResult

    data class FailedClean(
        val error: Throwable,
    ) : VoiceAgentSessionCreationResult

    data class FailedDirty(
        val error: Throwable,
        val cleanup: VoiceAgentCleanupOperation,
    ) : VoiceAgentSessionCreationResult
}

internal interface VoiceAgentCallFactory {
    suspend fun createOwned(
        request: VoiceAgentCallRequest,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): VoiceAgentSessionCreationResult
}
```

`Created` transfers a complete route-owned session. `FailedClean` returns only after route cleanup succeeds. `FailedDirty` returns the exact route cleanup owner after cleanup fails. Cancellation is never converted to a creation result: clean the route, suppress distinct cleanup failure onto the canonical `CancellationException`, then throw cancellation.

Add this property to `RouteOwnedManagedVoiceCallSession`:

```kotlin
val cleanupOperation: VoiceAgentCleanupOperation
```

The service depends on this narrow, fakeable boundary:

```kotlin
internal interface VoiceAgentCallServiceController {
    val activeConversationId: StateFlow<Uuid?>
    val lifecycle: StateFlow<VoiceAgentCallLifecycle>
    val state: StateFlow<VoiceAgentUiState>

    suspend fun start(request: VoiceAgentCallRequest): VoiceAgentCallStartResult
    suspend fun end(): VoiceAgentCallEndResult
    fun closeNow()
    fun updateCallStatus(status: VoiceCallStatus)
}
```

The orchestrator final API is:

```kotlin
internal class VoiceAgentCallOrchestrator(
    private val factory: VoiceAgentCallFactory,
    private val resolveRoute: suspend () -> VoiceAgentRouteLease,
    private val appScope: CoroutineScope,
    private val endDrainTimeoutMillis: Long = VOICE_AGENT_END_DRAIN_TIMEOUT_MS,
) : VoiceAgentCallServiceController {
    override val activeConversationId: StateFlow<Uuid?>
    override val lifecycle: StateFlow<VoiceAgentCallLifecycle>
    override val state: StateFlow<VoiceAgentUiState>

    override suspend fun start(request: VoiceAgentCallRequest): VoiceAgentCallStartResult
    override suspend fun end(): VoiceAgentCallEndResult
    override fun closeNow()
    fun interrupt()
    fun setMuted(value: Boolean)
    fun reconnect()
    override fun updateCallStatus(status: VoiceCallStatus)
    fun recordDiagnostic(name: String, detail: String)
}
```

Production DI passes `get<VoiceAgentAudioRouteResolver>()::resolve`; tests pass a suspending fake lambda. Make `DefaultVoiceAgentCallFactory` internal with the factory interface so no public API exposes internal request/result/cleanup types.

`closeNow()` atomically transfers ownership to `Stopping.ForEnd`, schedules `Immediate` cleanup in `appScope`, and returns without waiting for a blocked external callback. Its eventual completion still publishes `Idle` or `CleanupFailed`.

### State and Transition Contract

The shared operation and state types are:

```kotlin
internal interface VoiceAgentStartOperation {
    val token: Any
    val request: VoiceAgentCallRequest
    val phase: VoiceAgentStartPhase
    val cleanup: VoiceAgentCleanupOperation
    fun start()
    fun cancel()
}

internal sealed interface VoiceAgentStartPhase {
    val request: VoiceAgentCallRequest
    val callScope: CoroutineScope
    val callJob: Job

    data class PreparingRoute(
        override val request: VoiceAgentCallRequest,
        override val callScope: CoroutineScope,
        override val callJob: Job,
    ) : VoiceAgentStartPhase

    data class CreatingSession(
        override val request: VoiceAgentCallRequest,
        override val callScope: CoroutineScope,
        override val callJob: Job,
    ) : VoiceAgentStartPhase

    data class StartingSession(
        override val request: VoiceAgentCallRequest,
        override val callScope: CoroutineScope,
        override val callJob: Job,
        val session: RouteOwnedManagedVoiceCallSession,
    ) : VoiceAgentStartPhase
}

internal sealed interface VoiceAgentStartOutcome {
    data class Ready(val call: ActiveVoiceAgentCall) : VoiceAgentStartOutcome
    data class FailedClean(val error: Throwable) : VoiceAgentStartOutcome
    data class FailedDirty(
        val error: Throwable,
        val cleanup: VoiceAgentCleanupOperation,
    ) : VoiceAgentStartOutcome
    data object Cancelled : VoiceAgentStartOutcome
}

internal data class ActiveVoiceAgentCall(
    val token: Any,
    val request: VoiceAgentCallRequest,
    val route: VoiceAgentRouteMetadata,
    val session: RouteOwnedManagedVoiceCallSession,
    val callScope: CoroutineScope,
    val callJob: Job,
    val collector: Job,
    val cleanup: VoiceAgentCleanupOperation,
)

internal data class PendingVoiceAgentStart(
    val token: Any,
    val request: VoiceAgentCallRequest,
    val replies: List<CompletableDeferred<VoiceAgentCallStartResult>>,
)

internal data class PendingVoiceAgentCancellation(
    val error: CancellationException,
    val completion: CompletableDeferred<Throwable?>,
)

internal sealed interface VoiceAgentCallState {
    data object Idle : VoiceAgentCallState

    sealed interface Starting : VoiceAgentCallState {
        val pending: PendingVoiceAgentStart

        data class Admitting(
            override val pending: PendingVoiceAgentStart,
        ) : Starting

        data class Running(
            override val pending: PendingVoiceAgentStart,
            val operation: VoiceAgentStartOperation,
        ) : Starting
    }

    data class Active(val call: ActiveVoiceAgentCall) : VoiceAgentCallState

    sealed interface Stopping : VoiceAgentCallState {
        val cleanup: VoiceAgentCleanupOperation
        val supersededStarts: List<CompletableDeferred<VoiceAgentCallStartResult>>
        val ends: List<CompletableDeferred<VoiceAgentCallEndResult>>
        val cancellations: List<PendingVoiceAgentCancellation>

        data class ForEnd(
            override val cleanup: VoiceAgentCleanupOperation,
            override val supersededStarts: List<CompletableDeferred<VoiceAgentCallStartResult>>,
            override val ends: List<CompletableDeferred<VoiceAgentCallEndResult>>,
            override val cancellations: List<PendingVoiceAgentCancellation>,
        ) : Stopping

        data class ForReplacement(
            override val cleanup: VoiceAgentCleanupOperation,
            val pending: PendingVoiceAgentStart,
            override val supersededStarts: List<CompletableDeferred<VoiceAgentCallStartResult>>,
            override val ends: List<CompletableDeferred<VoiceAgentCallEndResult>>,
            override val cancellations: List<PendingVoiceAgentCancellation>,
        ) : Stopping
    }

    data class CleanupFailed(
        val cleanup: VoiceAgentCleanupOperation,
        val error: Throwable,
    ) : VoiceAgentCallState
}
```

`PendingVoiceAgentStart` is lightweight and requires a non-empty replies list. It owns no scope, job, route, or session. `Idle` owns nothing. `CleanupFailed` exposes no active command target. Every `ActiveVoiceAgentCall` field is non-null and the collector is created lazy before publication.

Lifecycle projection is exact: `Starting` uses its pending request conversation, `Active` uses its active request conversation, `ForReplacement` projects `Stopping` with the pending replacement conversation, and `ForEnd` projects `Stopping(null)`. `activeConversationId` is non-null only for `Active`.

The event/effect boundary is exact:

```kotlin
internal sealed interface VoiceAgentCallEvent {
    data class StartRequested(val pending: PendingVoiceAgentStart) : VoiceAgentCallEvent
    data class StartAdmitted(
        val pendingToken: Any,
        val operation: VoiceAgentStartOperation,
    ) : VoiceAgentCallEvent
    data class StartCancelled(
        val reply: CompletableDeferred<VoiceAgentCallStartResult>,
        val cancellation: PendingVoiceAgentCancellation,
    ) : VoiceAgentCallEvent
    data class EndRequested(
        val reply: CompletableDeferred<VoiceAgentCallEndResult>,
    ) : VoiceAgentCallEvent
    data object CloseNowRequested : VoiceAgentCallEvent
    data class StartFinished(
        val operation: VoiceAgentStartOperation,
        val outcome: VoiceAgentStartOutcome,
    ) : VoiceAgentCallEvent
    data class CleanupFinished(
        val cleanup: VoiceAgentCleanupOperation,
        val result: VoiceAgentCleanupResult,
    ) : VoiceAgentCallEvent
    data class SessionStateChanged(
        val call: ActiveVoiceAgentCall,
        val state: VoiceAgentUiState,
        val routeUsable: Boolean,
    ) : VoiceAgentCallEvent
}

internal sealed interface VoiceAgentCallEffect {
    data class AdmitStart(val pending: PendingVoiceAgentStart) : VoiceAgentCallEffect
    data class LaunchStart(val operation: VoiceAgentStartOperation) : VoiceAgentCallEffect
    data class CancelStart(val operation: VoiceAgentStartOperation) : VoiceAgentCallEffect
    data class RunCleanup(
        val cleanup: VoiceAgentCleanupOperation,
        val mode: VoiceAgentCleanupMode,
    ) : VoiceAgentCallEffect
    data class CompleteStarts(
        val replies: List<CompletableDeferred<VoiceAgentCallStartResult>>,
        val result: VoiceAgentCallStartResult,
    ) : VoiceAgentCallEffect
    data class CompleteEnds(
        val replies: List<CompletableDeferred<VoiceAgentCallEndResult>>,
        val result: VoiceAgentCallEndResult,
    ) : VoiceAgentCallEffect
    data class CompleteCancellations(
        val cancellations: List<PendingVoiceAgentCancellation>,
        val cleanupFailure: Throwable?,
    ) : VoiceAgentCallEffect
    data class Reconnect(val call: ActiveVoiceAgentCall) : VoiceAgentCallEffect
    data class ApplySessionState(
        val call: ActiveVoiceAgentCall,
        val state: VoiceAgentUiState,
    ) : VoiceAgentCallEffect
    data class RecordDiagnostic(
        val call: ActiveVoiceAgentCall,
        val name: String,
        val detail: String,
    ) : VoiceAgentCallEffect
    data class ApplyCallStatus(
        val call: ActiveVoiceAgentCall,
        val status: VoiceCallStatus,
    ) : VoiceAgentCallEffect
}

internal data class VoiceAgentCallTransition(
    val state: VoiceAgentCallState,
    val effects: List<VoiceAgentCallEffect>,
)
```

Use one pure entry point:

```kotlin
internal fun reduceVoiceAgentCallState(
    state: VoiceAgentCallState,
    event: VoiceAgentCallEvent,
): VoiceAgentCallTransition
```

Transition rules are binding:

- `StartRequested` carries only a request token and waiter. Admission moves it to `Starting.Admitting` and emits `AdmitStart`; the locked reducer driver creates one unstarted operation, immediately feeds back `StartAdmitted`, installs `Starting.Running`, and defers only `LaunchStart` until after unlock.
- Matching `Starting` appends a waiter without constructing another operation; matching `Active` completes `Active(route)` and emits `Reconnect` only when the exact session state is `Error`; matching pending replacement appends a waiter.
- Different `Starting.Running` moves its waiters to the superseded list, enters `Stopping.ForReplacement` with the operation's cleanup owner, and emits ordered `CancelStart` then `RunCleanup(Replacement)` effects.
- Different `Active` enters `Stopping.ForReplacement` and emits `RunCleanup(Replacement)`.
- A newer different pending replacement moves displaced waiters to the superseded list without replacing or relaunching cleanup; the displaced pending value owns no operation to dispose.
- `Starting.Admitting` is an internal transition-only value drained before the lock is released. `EndRequested` from `Starting.Running` uses `Immediate`; from `Active` uses `GracefulEnd`; from either stopping variant preserves the cleanup owner, removes any pending replacement, and appends the end waiter.
- A start during `Stopping.ForEnd` creates `ForReplacement` around the same cleanup. End waiters complete only when that cleanup attempt finishes.
- Cleanup success completes retained superseded starts, completes retained end waiters, then publishes `Idle` or admits only the newest pending start.
- Cleanup failure completes retained superseded starts as `Superseded`, retained end waiters as `Failed(error)`, and the pending replacement as `Failed(error)`, then publishes `CleanupFailed`.
- Canceling a start waiter removes only that waiter while another matching waiter remains. Canceling the last desired waiter converts its owned startup/pending replacement to `ForEnd`, retains `PendingVoiceAgentCancellation`, and waits for exact cleanup before returning the canonical cancellation.
- A start from `CleanupFailed` enters `Stopping.ForReplacement` and emits one `RunCleanup(Immediate)` for the exact cleanup owner. Another failure returns to `CleanupFailed`; success admits the request.
- `EndRequested` in `CleanupFailed` reports `Failed(currentError)` without retry. `CloseNowRequested` triggers one `Immediate` retry.
- A stale `StartAdmitted` keeps the current state and emits ordered `CancelStart` then `RunCleanup(Immediate)` for the unstarted operation. Stale start, cleanup, and collector completions emit only exact local cleanup/completion effects and never mutate a newer state.

No start, end, or cancellation waiter completes before its former owner reaches successful transfer or the state reaches `CleanupFailed`. `CompleteCancellations` supplies the cleanup failure for suppression, then each caller throws its original `CancellationException`.

### Session-state Policy

- `ApplySessionState` and identity-bound `ApplyCallStatus` reacquire the orchestrator lock and write `VoiceAgentUiState` only if the exact active-call token is still current; stale policy effects are ignored.
- Route failure metadata records its diagnostic and publishes `VoiceCallStatus.Degraded`.
- A non-degraded Telecom call publishes `BackgroundCapable` after successful active publication.
- `VoiceSessionStatus.Error` with a usable route keeps the exact call `Active`, records `voice_call_start_failed`, and publishes degraded status. A matching later start emits one `reconnect()`.
- `VoiceSessionStatus.Error` with an unusable route and `VoiceSessionStatus.Ended` detach the exact active call into `Stopping.ForEnd` with `Immediate` cleanup.
- Mute, interrupt, reconnect, and diagnostics snapshot the complete active call under the lock and invoke the session outside it.

### Final Verification Contract

The final implementation must pass:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
```

The final repository scan must return no production or test occurrence of these deleted ownership APIs:

```bash
rg -n "VoiceAgentCallManager|VoiceAgentCallStartup|CleanupFence|CleanupClaim|PendingPublication|runReservationOwner|VoiceAgentManagerStartResult|VoiceAgentRouteMatchResult" app/src/main app/src/test
```

Expected: exit status 1 with no matches. Scan only `app/src/main` and `app/src/test`; historical specs and plans intentionally retain the old names.

## Illustrative Implementation Guidance

Keep the pure reducer free of coroutines and callbacks. Incoming requests contain no operation. When the reducer emits `AdmitStart`, the orchestrator creates the unstarted operation and reduces `StartAdmitted` before releasing the same lock; this atomically installs the operation in `Starting.Running`. It then runs `LaunchStart` in `appScope` outside the lock and feeds the exact outcome back as `StartFinished`. Operation construction must only allocate the typed operation, child scope/job, and cleanup handle; it must not launch a coroutine, resolve a route, call the factory, or invoke a session.

The start operation should keep one sealed internal phase (`PreparingRoute`, `CreatingSession`, or `StartingSession`) and expose one cleanup operation that cancels/joins startup and cleans the exact phase resource. Do not recreate the five Boolean/nullable ownership variables in another class.

During Tasks 2–6 only, retain the old non-suspending `VoiceAgentCallFactory.create(...)` method so the old manager compiles. Add `createOwned(...)` beside it and mark the legacy method for deletion. Its temporary interface default may wrap a successful legacy `create` as `Created`; production `DefaultVoiceAgentCallFactory` and every new orchestrator fake must override `createOwned` and implement all three typed results directly. No orchestrator acceptance test may use the default bridge for a failure. Task 7 removes the old method, default bridge, and all callers; no compatibility path remains in the final tree.

---

### Task 1: Make Telecom Route Retirement Retryable

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/RetryableRetirement.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/RetryableRetirementTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLease.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistry.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistryTest.kt`

**Interfaces:**
- Produces: `internal class RetryableRetirement { fun retire(block: () -> Unit) }`.
- Preserves: `VoiceAgentRouteLease.retire(): Unit` and all route metadata/usability APIs.
- Does not modify: `RetirementBarrier` or any audio owner.

**Invariants:**
- One retirement attempt executes at a time; concurrent callers join the captured attempt result.
- Success is permanent. Failure becomes retryable only after that attempt result is published to its joiners.
- Same-thread reentry joins the outer owner without deadlock or a second block call.
- A failed Telecom disconnect is no longer terminalized as clean; the registry retains the exact connection in a non-active retryable retirement-failed phase.
- A replacement Telecom attempt is never disconnected by retrying a stale exact lease.

**Acceptance:**
- First disconnect failure is thrown; a later `lease.retire()` calls the same exact connection again and can succeed.
- Two concurrent callers observe the same failure identity and one disconnect call; a third caller makes the second attempt.
- After success, later callers return without another disconnect.
- `isUsable` is false after the first failed retirement and remains false during retry.

- [ ] **Step 1: Write the retry-gate tests**

Add tests named `concurrent callers join one failed retirement attempt`, `later call retries after failure`, `success is permanently replayed`, and `same thread reentry does not deadlock`. Use latches and `Executors.newFixedThreadPool(2)`; assert failure identity with `assertSame`, exact block-call counts, and bounded `Future.get(1, TimeUnit.SECONDS)`.

- [ ] **Step 2: Run the gate test and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*RetryableRetirementTest'
```

Expected: compilation fails with unresolved reference `RetryableRetirement`.

- [ ] **Step 3: Implement the retry gate**

Use a per-attempt object containing owner thread, latch, and `Result<Unit>`. Select owner/joiner under a private lock, run the block outside it, publish the captured result before making failure retryable, and make success terminal. Preserve interrupt status for synchronous joiners.

- [ ] **Step 4: Write failing registry and lease retry tests**

Add `failed exact Telecom retirement can retry same connection` to both focused test files. The first `disconnectFromApp()` throws `firstFailure`; assert one call and `isUsable == false`. Clear the fake failure, call `retire()` again, assert two calls total, then call a third time and assert the count remains two. Activate a replacement before the retry and assert its disconnect count remains zero.

- [ ] **Step 5: Run the focused route tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentRouteLeaseTest' \
  --tests '*VoiceAgentTelecomCallRegistryTest'
```

Expected: the retry assertions fail because `RetirementBarrier` replays failure and the registry removes the failed connection.

- [ ] **Step 6: Implement retryable exact Telecom retirement**

Use `RetryableRetirement` only in `TelecomVoiceAgentRouteLease`. Add a registry `RetirementFailed(connection, outcomeFailure, cleanupError)` phase. On disconnect failure, clear current ownership, retain the attempt record and connection, and throw the exact cleanup error. A later exact retirement moves that phase back to `Retiring` and retries. On success, terminalize and remove according to existing acknowledgment rules.

- [ ] **Step 7: Run focused tests**

Run both commands from Steps 2 and 5.

Expected: all selected tests pass; existing activation, acknowledgment, stale-attempt, and replacement ownership tests remain green.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/RetryableRetirement.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLease.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistry.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/RetryableRetirementTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistryTest.kt
git commit -m "refactor(voice): make route retirement retryable"
```

### Task 2: Add Typed Cleanup and Factory Ownership Results

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCleanupOperation.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCleanupOperationTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactoryTest.kt`

**Interfaces:**
- Produces: `VoiceAgentCleanupMode`, `VoiceAgentCleanupResult`, `VoiceAgentCleanupOperation`, `VoiceAgentSessionCreationResult`, `RouteOwnedManagedVoiceCallSession.cleanupOperation`, and suspending `VoiceAgentCallFactory.createOwned(...)` exactly as declared globally.
- Temporarily preserves: legacy `VoiceAgentCallFactory.create(...)` and route-owned terminal methods only so the old manager compiles through Task 6.
- Consumes: retryable `VoiceAgentRouteLease.retire()` from Task 1.

**Invariants:**
- Route cleanup precedes delegate cleanup, but a route failure does not skip later delegate/collector/scope stages.
- Each successful stage is recorded and never invoked again.
- Replacement uses delegate `end()`. Graceful end uses timed drain and forced immediate close on drain failure, timeout, or cancellation. Immediate uses `closeNow()`.
- Cancellation stays canonical after all admitted cleanup stages; distinct failures are suppressed in route/delegate/collector/scope order.
- Factory entry consumes the route. No creation branch returns both a session and cleanup owner or neither after dirty failure.

**Acceptance:**
- Route failure plus successful delegate close returns `Failed(routeFailure)`; later immediate retry calls only route retirement and completes.
- Delegate failure after route success retries only delegate immediate close.
- Concurrent cleanup callers join one result identity.
- Timed drain timeout plus successful force close reports failure once; a later retry completes without repeating route, drain, or close.
- Factory creation returns `Created`, `FailedClean`, or `FailedDirty` with exact ownership; cancellation is thrown.

- [ ] **Step 1: Write cleanup progress and concurrency tests**

Create tests named `failed route retries without repeating successful delegate`, `failed delegate retries immediate close without repeating route`, `concurrent callers join exact cleanup attempt`, `completed stages make retry a no-op`, and `cancellation remains canonical with ordered suppressed failures`. Use recording closures and assert the exact event lists for first attempt and retry.

- [ ] **Step 2: Run the cleanup test and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCleanupOperationTest'
```

Expected: compilation fails because the cleanup contracts do not exist.

- [ ] **Step 3: Implement cleanup attempt joining and stage progress**

Implement route-only, route-plus-delegate, and active-call cleanup constructors in `VoiceAgentCleanupOperation.kt`. Keep progress as sealed/explicit stage state, run external stages outside the coordination lock, and aggregate every admitted stage even after an earlier failure.

- [ ] **Step 4: Move route-owned cleanup behind one operation**

Add `cleanupOperation` to `RouteOwnedManagedVoiceCallSession`. Build it once in `RouteOwnedVoiceCallSession` from the exact delegate and route lease. Keep legacy terminal methods delegating to their existing behavior during migration; new code and tests must use the cleanup operation.

- [ ] **Step 5: Write typed factory-result tests**

Extend `VoiceAgentCallFactoryTest` with four cases: successful creation returns `Created` and leaves route live; creation failure plus successful retirement returns `FailedClean` with the original error; creation plus retirement failure returns `FailedDirty` with the original error primary and the cleanup error suppressed; cancellation retires the route and throws the exact canonical cancellation.

- [ ] **Step 6: Run factory and route-owned tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallFactoryTest' \
  --tests '*VoiceAgentRouteLeaseTest' \
  --tests '*VoiceAgentCleanupOperationTest'
```

Expected: factory tests fail because `createOwned` and typed results are absent.

- [ ] **Step 7: Implement `createOwned` and typed failure transfer**

Extract the current default session construction into one private function. `createOwned` wraps it with the exact result contract. On dirty failure return the exact route cleanup operation so a later orchestrator retry can continue; do not throw non-cancellation creation errors. Retain the old `create` entry point only as the explicitly temporary bridge described above.

- [ ] **Step 8: Run focused tests**

Run the command from Step 6.

Expected: all selected tests pass with existing timeout, forced-close, cancellation, and failure-order assertions preserved.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCleanupOperation.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCleanupOperationTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactoryTest.kt
git commit -m "refactor(voice): type call cleanup ownership"
```

### Task 3: Implement the Pure Call State Machine

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStateMachine.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStateMachineTest.kt`

**Interfaces:**
- Produces: request/start/end/lifecycle contracts, all `VoiceAgentCallState`, event, effect, transition, pending-waiter, and active-call types, plus `reduceVoiceAgentCallState(...)` exactly as declared globally.
- Consumes: `VoiceAgentCleanupOperation` and cleanup modes/results from Task 2.
- Uses the opaque `VoiceAgentStartOperation` interface and complete `ActiveVoiceAgentCall` data value declared in this file. Reducer tests use completed inert jobs and session fakes, with no running coroutines.

**Invariants:**
- The reducer is deterministic, has no lock, launches no coroutine, completes no deferred, and calls no resource method.
- Every owned operation appears in exactly one next-state value or one effect that transfers/finishes it.
- Lists are non-null; `PendingVoiceAgentStart` and non-close end groups are non-empty by construction. Pending starts contain only request identity and waiters.
- Latest different request wins while matching requests append to one waiter group.
- Cleanup identity never changes while `Stopping` or `CleanupFailed`.

**Acceptance:**
- A table-driven test covers every event legal in `Idle`, all starting phases through the opaque operation, `Active`, both stopping variants, and `CleanupFailed`.
- Stale operation identities leave the state unchanged and emit no state-owning effect.
- Ordered effects prove cancel-before-cleanup, cleanup-before-next-start, and completion only at terminal transfer/failure.
- Canceling one of several matching waiters removes only that waiter; canceling the final desired waiter retains its canonical cancellation until exact cleanup finishes.
- Repeated matching requests and replaced pending requests create no additional start operation or child job.

- [ ] **Step 1: Write table-driven reducer tests for start and active transitions**

Cover idle admission, `StartAdmitted`, matching starting, different running startup, matching active with normal state, matching active in `Error`, and different active. Assert exact next-state class, preserved operation/cleanup identity with `assertSame`, waiter counts, and ordered effect classes. For different running startup, assert the effects are exactly `CancelStart` followed by `RunCleanup(Replacement)`.

- [ ] **Step 2: Write reducer tests for stopping, end, failure, and retry**

Cover matching/different pending replacement, start during `ForEnd`, end during replacement, one-waiter and last-waiter cancellation, cleanup success to idle/start, cleanup failure to `CleanupFailed`, later start retry, repeated retry failure, end in `CleanupFailed`, and close-now retry. Assert displaced starts and last-waiter cancellation do not complete until `CleanupFinished`.

- [ ] **Step 3: Run reducer tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallStateMachineTest'
```

Expected: compilation fails because the state-machine contracts do not exist.

- [ ] **Step 4: Implement immutable states, events, effects, and reducer**

Implement every branch in the binding transition contract. Use exhaustive sealed `when` expressions. Reject construction of empty pending waiter groups with `require` and keep all external work represented as ordered effects.

- [ ] **Step 5: Add the stale-identity and invariant tests**

For each completion event, pass a different operation token and assert the same state instance returns. Pass a stale `StartAdmitted` operation and assert ordered cancel plus immediate cleanup. Add helper assertions that count resource owners in the next state plus effects and require zero for `Idle` and transition-only `Starting.Admitting`, or one for every resource-owning state.

- [ ] **Step 6: Run reducer tests**

Run the command from Step 3.

Expected: all state-machine tests pass without `runTest`, Android fakes, latches, or live jobs.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStateMachine.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStateMachineTest.kt
git commit -m "refactor(voice): define call orchestration states"
```

### Task 4: Build Typed Startup and Atomic Active Publication

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartOperation.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestrator.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorTestFixtures.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorStartupTest.kt`

**Interfaces:**
- Produces: the exact orchestrator API, typed `VoiceAgentStartOperation` phases, startup outcome, complete `ActiveVoiceAgentCall`, and lazy collector publication.
- Consumes: reducer/effects from Task 3, `createOwned` from Task 2, a suspending route-resolution function, and `AppScope` through its `CoroutineScope` interface.

**Invariants:**
- Start operation phase is exactly one of `PreparingRoute`, `CreatingSession`, `StartingSession`, or terminal; no nullable resource group or ownership Boolean exists.
- Incoming and queued starts are lightweight. Only an admitted request receives an operation and child scope/job.
- The operation's cleanup handle cancels/joins startup and cleans whichever exact phase is current.
- `Active` is installed only with session, route metadata, child scope/job, lazy collector, cleanup operation, and identity token all present.
- Shared start waiters complete in the same locked transition that publishes complete `Active`; deferred resumptions execute after the lock via effects.
- Collector starts after publication and updates only its exact active identity.

**Acceptance:**
- Idle startup transfers one route through one factory call, starts one session, publishes one complete active call, and leaves the installed route unretired.
- Matching startup and active calls do not call the route resolver again and receive `Active` with exact installed metadata.
- Repeated matching starts add no app-scope child job for discarded requests. Blocked-cleanup A/B/C replacement coverage belongs to Task 5, which implements cleanup execution.
- Factory clean/dirty failures map to `Idle`/`CleanupFailed` and `Failed` without dangling waiters.
- Route resolution failure maps to `Idle` and `Failed` without constructing a cleanup owner.
- Cancellation at route, factory, session-start, and pre-collector boundaries cleans the exact owned resource and preserves cancellation.
- Canceling one matching waiter leaves shared startup running for remaining waiters; canceling the last waiter cancels and cleans startup before throwing its canonical cancellation.
- End or different start immediately before collector start prevents any stale state update.

- [ ] **Step 1: Write the happy-path and matching startup tests**

Use a suspending fake route resolver, typed fake factory, and `StandardTestDispatcher`. Assert lifecycle `Idle -> Starting -> Active`, exact metadata, one route/factory/session call, one lazy collector, and zero retirement calls on the active route. Start a matching request while factory is blocked and assert it waits without a second route resolution.

- [ ] **Step 2: Run startup tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallOrchestratorStartupTest'
```

Expected: compilation fails because start operation and orchestrator do not exist.

- [ ] **Step 3: Implement sealed startup phases and cleanup handle**

Construct the per-call child `SupervisorJob` only while draining `AdmitStart`; it must already exist before route and factory work because the factory needs its scope. Transfer route to the factory phase, consume the three typed factory results, transfer a created session to `StartingSession`, and build the complete active bundle only after `session.start()` succeeds.

- [ ] **Step 4: Implement orchestrator command admission and startup effects**

Add synchronized event reduction. Drain `AdmitStart` before releasing the lock: create one unstarted operation, immediately reduce `StartAdmitted`, and place that exact operation in `Starting.Running`. Queue `LaunchStart` and every external effect for execution after unlock. Implement `start`, `StartFinished`, active projection, shared result completion, and exact failure mapping. StateFlow assignment and unstarted operation construction may occur under the lock; deferred completion, coroutine launch, cancellation, joining, route/factory/session work, and cleanup may not.

In `start`, catch caller cancellation from the private reply await, create `PendingVoiceAgentCancellation` with the canonical exception, reduce `StartCancelled`, await its completion in `NonCancellable`, suppress the returned distinct cleanup failure, and throw the original canonical exception. If another matching waiter remains, the reducer completes cancellation immediately without canceling shared startup.

- [ ] **Step 5: Write failure, cancellation, and collector-race tests**

Add one test for each factory result and cancellation boundary. Use the existing canonical cancellation fixture pattern. Repeatedly submit a matching request; inspect the test app scope and assert its child-job count does not increase for discarded requests. Block lazy collector dispatch, supersede/end the call, release dispatch, and assert the collector never overwrites the newer/idle state and its job is canceled.

- [ ] **Step 6: Run startup and reducer tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallStateMachineTest' \
  --tests '*VoiceAgentCallOrchestratorStartupTest'
```

Expected: all selected tests pass; no test asserts an unpublished or collector-null active form.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartOperation.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestrator.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorTestFixtures.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorStartupTest.kt
git commit -m "refactor(voice): publish typed active calls"
```

### Task 5: Implement Replacement, End, Cleanup Failure, and Session Policy

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestrator.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartOperation.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorConcurrencyTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorPolicyTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorTestFixtures.kt`

**Interfaces:**
- Completes: `end`, `closeNow`, active commands, all cleanup effects, `CleanupFailed` retry, lifecycle projection, and session-state policy in the global contract.
- Preserves: Task 4 startup API and exact typed states/results.

**Invariants:**
- Latest request replacement never overlaps old startup/session cleanup.
- One cleanup owner survives every intervening start/end intent until success or `CleanupFailed`.
- A later start makes one new immediate cleanup attempt; it never loops automatically.
- `closeNow` transfers ownership synchronously but cleanup runs in `appScope`.
- External session commands and cleanup run outside the orchestrator lock.

**Acceptance:**
- A, B, C replacement while A cleanup is blocked starts only C after A cleanup; A cleans once, B is superseded, and B receives no start operation or app-scope child job.
- Cleanup failure fails C, publishes `CleanupFailed`, and starts nothing. Later D retries exact unfinished stages once and starts only after success.
- End during every startup phase and active state reaches `Idle` or `CleanupFailed`; start during end cleanup waits and then starts.
- Repeated end callers join one cleanup result. Close-now during blocked external work returns promptly.
- Current session Error/Ended and route usability follow the binding session policy.
- Reentrant and blocked fakes prove manager commands/status updates remain independent of external lifecycle work.

- [ ] **Step 1: Port latest-wins and cleanup-gating tests**

Port behavior—not private names—from `VoiceAgentCallManagerBarrierTest` and `VoiceAgentCallManagerPublicationTest`. Use orchestrator results and lifecycle assertions. Explicitly assert B's waiter completes only after A cleanup reaches success/failure, B adds no app-scope child job, C alone resolves a route, and stale A/B operations cannot clear C.

- [ ] **Step 2: Add cleanup-failure and later-retry tests**

Make route cleanup fail while delegate cleanup succeeds. Assert C returns `Failed(routeFailure)`, lifecycle is `CleanupFailed`, active ID is null, and C's resolver/factory never runs. Clear the route failure, submit D, assert only route retirement retries, then D resolves/creates/starts. Repeat with delegate failure and assert retry uses immediate close without repeating successful route cleanup.

- [ ] **Step 3: Add end/start and close-now tests**

Cover end from idle, route preparation, factory creation, session start, active, replacement cleanup, and cleanup-failed. Cover start during `ForEnd`. For close-now, block factory/session cleanup on a latch, measure that `closeNow()` returns before release, then release and assert terminal lifecycle.

- [ ] **Step 4: Run concurrency tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallOrchestratorConcurrencyTest'
```

Expected: assertions fail because replacement, end, and cleanup effects are not yet executed by the orchestrator.

- [ ] **Step 5: Implement cleanup, end, and close-now effects**

Execute reducer effects in order. Attach one watcher per cleanup attempt, feed the exact result back through `CleanupFinished`, and never launch pending startup before success. Complete all retained waiter groups only from the reducer effects selected at cleanup terminal state.

- [ ] **Step 6: Write and implement session policy tests**

Port `VoiceAgentCallServicePolicyTest` cases to `VoiceAgentCallOrchestratorPolicyTest`. Assert usable Telecom/direct Error stays active and degraded, unusable Telecom Error cleans without touching replacement route, Ended cleans and becomes idle, matching Error invokes reconnect once, route failure diagnostics are recorded, and non-degraded Telecom becomes background-capable.

- [ ] **Step 7: Implement collector policy and active commands**

Feed current session updates through `SessionStateChanged`. Before `ApplySessionState` or identity-bound `ApplyCallStatus` writes, reacquire the orchestrator lock and require the exact `ActiveVoiceAgentCall.token` to still be current; ignore the effect otherwise. Apply external policy calls outside the lock. Implement command snapshots, and add a race test that blocks each projection effect, ends or replaces the call, then proves the stale effect cannot overwrite the newer/idle UI state. Add blocking/reentrant fake assertions showing `updateCallStatus` and unrelated snapshots do not wait on external session code.

- [ ] **Step 8: Run all new orchestrator tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallStateMachineTest' \
  --tests '*VoiceAgentCleanupOperationTest' \
  --tests '*VoiceAgentCallOrchestrator*Test'
```

Expected: all selected tests pass.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestrator.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartOperation.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorConcurrencyTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorPolicyTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorTestFixtures.kt
git commit -m "refactor(voice): unify call replacement and cleanup"
```

### Task 6: Cut Android Service and UI Over to the Orchestrator

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycle.kt`
- Delete: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartup.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentRoute.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycleTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServicePolicyTest.kt`
- Delete: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt`

**Interfaces:**
- Consumes: `VoiceAgentCallServiceController`, exact request/start/end results, lifecycle flow, and UI state from Tasks 4–5. Production binds the singleton orchestrator to that interface.
- Produces: Android-only lifecycle helper with current-intent generation, configuration-job cancellation, notification ordering, failure reporting, and `stopSelf` gating.
- Removes: production and test use of `VoiceAgentCallStartup`.

**Invariants:**
- Configuration loading owns no route/session. A stale configuration result performs zero orchestrator mutation.
- Start preflight parses the conversation ID, reads the settings snapshot and conversation snapshot, resolves config, then rechecks the exact Android generation immediately before `orchestrator.start`; every parse/config/stale failure performs zero orchestrator writes.
- Android generation protects notification and `stopSelf` ordering only; it is not a second call ownership state machine.
- Foreground notification remains present through startup and end cleanup.
- An old end/start completion cannot stop a newer service generation.
- Service destruction calls orchestrator `closeNow`, cancels service-only jobs, and does not abandon app-scope cleanup.
- Repeated start/end intents are idempotent through orchestrator matching/joining, and all reported/logged failures use existing credential redaction.

**Acceptance:**
- Matching resolved request reaches orchestrator once; different config for same conversation replaces.
- Stale config load submits no request.
- Start result `Failed` reports safely and stops only the matching generation; `Superseded` does not stop the winner.
- End waits for orchestrator terminal result before stopping; newer start prevents old completion from stopping the service.
- Compose observes orchestrator state and sends all active commands to it.

- [ ] **Step 1: Rewrite lifecycle tests against an orchestrator fake**

Replace manager/session-detach assertions with a fake `VoiceAgentCallServiceController` that records requests and exposes controllable start/end deferreds plus lifecycle/state flows. No mocking library or subclassing of the concrete orchestrator is required. Preserve exact host event order for notification cancellation, foreground start, cleanup failure reporting, stop-foreground, stop-self, and base destruction.

- [ ] **Step 2: Run service lifecycle tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallServiceLifecycleTest' \
  --tests '*VoiceAgentCallServicePolicyTest'
```

Expected: compilation fails because lifecycle still requires `VoiceAgentCallManager` and detached sessions.

- [ ] **Step 3: Reduce `VoiceAgentCallServiceLifecycle` to Android hosting**

Keep current generation and end-job tracking only for host ordering. Inject `VoiceAgentCallServiceController` and replace detach/drain with its suspending `end()`. On `Failed`, report the exact error; on either terminal result stop only if generation is current. `destroy()` calls `closeNow()` before canceling service jobs.

- [ ] **Step 4: Update service start/configuration flow**

Inject `VoiceAgentCallServiceController`. After settings/conversation resolution, build exact `VoiceAgentCallRequest`; recheck generation immediately before submission. Map `Active`, `Superseded`, and `Failed` without route matching or started-new branching. Collect controller state/lifecycle for notification and autonomous idle/cleanup-failed shutdown.

- [ ] **Step 5: Update DI and Compose consumers**

Register one concrete `VoiceAgentCallOrchestrator(factory = get(), resolveRoute = get<VoiceAgentAudioRouteResolver>()::resolve, appScope = get<AppScope>())` and bind that singleton as `VoiceAgentCallServiceController`; remove manager and startup registrations/imports. Inject the concrete orchestrator in `VoiceAgentRoute` for UI commands and keep the existing screen callback behavior.

- [ ] **Step 6: Delete startup wrapper and its tests**

Remove both `VoiceAgentCallStartup.kt` files named above. Ensure every matching/replacement behavior formerly tested there exists in orchestrator startup/concurrency tests before deletion.

- [ ] **Step 7: Run service and orchestrator tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallService*Test' \
  --tests '*VoiceAgentCallOrchestrator*Test'
```

Expected: all selected tests pass and production DI has one call ownership singleton.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycle.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartup.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentRoute.kt \
  app/src/main/java/me/rerere/rikkahub/di/AppModule.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycleTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServicePolicyTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt
git commit -m "refactor(voice): host calls through orchestrator"
```

### Task 7: Remove the Legacy Manager and Prove State Invariants

**Files:**
- Delete: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt`
- Delete: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`
- Delete: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerBarrierTest.kt`
- Delete: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerPublicationTest.kt`
- Delete: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerMonitorTest.kt`
- Delete: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTestFixtures.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorStressTest.kt`

**Interfaces:**
- Removes: legacy manager/startup result types, non-suspending factory creation bridge, detached-session APIs, and legacy terminal entry points used only by the old manager.
- Preserves: final interfaces in the binding contract without aliases or wrappers.

**Invariants:**
- No old ownership type remains in production or tests.
- Every behavior worth preserving from deleted tests is present in state-machine, cleanup, orchestrator, route, factory, or service tests.
- Deterministic stress events never produce two resource owners, a missing active resource, a stale publish, or an incomplete terminal waiter.

**Acceptance:**
- The focused legacy-name scan has zero matches.
- Randomized deterministic event sequences finish with every deferred completed and every fake resource either active under the exact current call or successfully cleaned.
- Full JVM tests and debug Kotlin compilation pass.

- [ ] **Step 1: Add deterministic stress coverage**

Use seeds `1L`, `7L`, `42L`, and `20260719L`. For each seed, generate 250 ordered events from the exact reducer contract: `StartRequested` with same or different requests, `StartAdmitted`, `StartCancelled`, `EndRequested`, `CloseNowRequested`, `StartFinished`, `CleanupFinished`, and `SessionStateChanged`. Include success, clean failure, dirty failure, cancellation, current identity, and stale identity variants. After each event assert the reducer owner count, complete-active fields, cleanup identity stability, and stale-token rejection. At the end, drive remaining effects through declared completion events to a terminal state and assert every reply deferred is complete.

- [ ] **Step 2: Run stress tests and verify legacy interference**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallOrchestratorStressTest'
```

Expected before final removal: stress tests compile and pass; the legacy-name scan below still finds manager files.

- [ ] **Step 3: Delete legacy production and test files**

Remove every file listed for deletion after checking this fixed coverage map: basic/matching/factory/start cases live in `VoiceAgentCallOrchestratorStartupTest`; predecessor, cancellation, cleanup, end, and command-lock cases live in `VoiceAgentCallOrchestratorConcurrencyTest`; publication and collector races live in startup plus concurrency tests; pure latest-wins/failure/retry transitions live in `VoiceAgentCallStateMachineTest`. Do not port assertions for cleanup-fence replay, cleanup claims, nullable publication, retry resurrection, or manager-private result shapes.

- [ ] **Step 4: Remove transitional factory and session APIs**

Delete old `VoiceAgentCallFactory.create(...)`, legacy-only imports/fixtures, and route-owned terminal entry points no longer consumed in production. Keep `ManagedVoiceCallSession` terminal primitives required internally by the cleanup operation; expose cleanup to the orchestrator only through `cleanupOperation`.

- [ ] **Step 5: Run the focused old-name scan**

Run:

```bash
rg -n "VoiceAgentCallManager|VoiceAgentCallStartup|CleanupFence|CleanupClaim|PendingPublication|runReservationOwner|VoiceAgentManagerStartResult|VoiceAgentRouteMatchResult" app/src/main app/src/test
```

Expected: exit status 1 and no output.

- [ ] **Step 6: Run complete verification**

Run:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
git diff --check
```

Expected: both Gradle commands end with `BUILD SUCCESSFUL`; `git diff --check` prints nothing.

- [ ] **Step 7: Review the final diff against the binding contract**

Confirm one mutable call authority, exact final interfaces, no external work under the orchestrator lock, complete active bundles, retryable failed cleanup, direct Android cutover, and no edits to audio/capture ownership or route-selection policy.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerBarrierTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerPublicationTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerMonitorTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTestFixtures.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorStressTest.kt
git commit -m "refactor(voice): remove legacy call manager"
```
