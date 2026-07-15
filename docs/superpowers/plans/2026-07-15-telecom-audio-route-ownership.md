# Telecom Audio Route Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve one immutable audio-route owner before Voice Agent startup so Telecom and direct Android audio APIs never compete for focus or Bluetooth routing.

**Architecture:** Add a typed owner to call identity and traces. Resolve it through an attempt-scoped Telecom handshake before manager startup: an active connection selects Telecom, while registration, placement, rejection, or bounded timeout selects direct fallback. Move direct AudioManager/Bluetooth mutation behind a controller and give Telecom a no-op controller; PCM capture/playback stays shared.

**Tech Stack:** Kotlin, Android self-managed `ConnectionService`, coroutines and `StateFlow`, Android audio/Bluetooth APIs for fallback only, JUnit 4, existing device E2E tooling.

## Global Constraints

- `Telecom` and `DirectFallback` are the only owners.
- Ownership is selected before `VoiceAgentCallManager.start` and remains immutable through reconnect and reattachment.
- A Telecom-owned engine performs no direct focus, mode, communication-device, SCO, headset voice-recognition, or preferred-recorder mutation.
- Direct routing starts only after pre-session Telecom failure or timeout.
- Late Telecom callbacks after fallback are disconnected.
- Cleanup releases only resources acquired by the selected owner and is idempotent.
- Do not change Gemini, Hermes, announcement, transcript, or playback-drain behavior.
- Deterministic Gemini-to-Hermes routing remains deferred in https://github.com/mulyoved/rikkahub/issues/46.

---

## File Structure

- Create `voiceagent/audio/VoiceAudioRouteOwner.kt`: immutable owner and safe label.
- Modify manager, factory, and session metadata: carry and retain ownership.
- Replace the snapshot-only Telecom registry with attempt-scoped outcomes.
- Create `VoiceAgentAudioRouteResolver.kt`: pre-session ownership decision.
- Create `VoiceAgentCallStartup.kt`: reusable service startup policy.
- Create `voiceagent/audio/VoiceAudioRouteController.kt`: owner-based controller selection.
- Create `voiceagent/audio/AndroidDirectAudioRouteController.kt`: all direct route mutation.
- Reduce `AndroidVoiceAudioEngine.kt` to PCM plus the selected controller lifecycle.

Execute the implementation tasks in this order: **1 → 2 → 3 → 5 → 4 → 6 → 7**.
Task 1 keeps the existing service explicitly on `DirectFallback`; Task 5 installs
the owner-selected controller before Task 4 can enable Telecom ownership.

### Task 1: Make ownership part of call identity and metadata

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteOwner.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceE2ESessionMetadata.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceE2ESessionMetadataTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`

**Interfaces:**
- Produces: `VoiceAudioRouteOwner`; owner-aware manager/factory signatures; `routeOwnerForActiveSession`; JSON `audioRouteOwner`.
- Consumes: existing manager, factory, metadata, and audio-factory contracts.

- [ ] **Step 1: Add failing manager tests**

Add explicit owner arguments to existing starts, then add:

```kotlin
@Test
fun `start passes and retains immutable route owner`() = runTest {
    val session = FakeManagedVoiceCallSession()
    val factory = FakeVoiceAgentCallFactory(session)
    val manager = VoiceAgentCallManager(factory)
    val conversationId = Uuid.random()
    val config = fakeLaunchConfig()

    manager.start(conversationId, config, VoiceAudioRouteOwner.Telecom, this)

    assertEquals(VoiceAudioRouteOwner.Telecom, factory.created.single().routeOwner)
    assertEquals(VoiceAudioRouteOwner.Telecom, manager.activeRouteOwner.value)
    assertEquals(
        VoiceAudioRouteOwner.Telecom,
        manager.routeOwnerForActiveSession(conversationId, config),
    )
}

@Test
fun `different owner replaces otherwise matching session`() = runTest {
    val first = FakeManagedVoiceCallSession()
    val second = FakeManagedVoiceCallSession()
    val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(first, second))
    val conversationId = Uuid.random()
    val config = fakeLaunchConfig()

    manager.start(conversationId, config, VoiceAudioRouteOwner.DirectFallback, this)
    val replaced = manager.start(conversationId, config, VoiceAudioRouteOwner.Telecom, this)

    assertEquals(true, replaced)
    assertEquals(1, first.endCalls)
    assertEquals(VoiceAudioRouteOwner.Telecom, manager.activeRouteOwner.value)
}
```

Change the fake record to:

```kotlin
private data class CreatedCall(
    val conversationId: Uuid,
    val config: VoiceAgentLaunchConfig,
    val routeOwner: VoiceAudioRouteOwner,
)
```

- [ ] **Step 2: Add failing metadata assertions**

Add `audioRouteOwner = "telecom"` to the direct fixture, add `audioRouteOwner` to the exact JSON key set, and assert its value. Pass `VoiceAudioRouteOwner.DirectFallback` to the default builder test and assert `direct_fallback`.

- [ ] **Step 3: Run tests to verify failure**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallManagerTest' \
  --tests '*VoiceE2ESessionMetadataTest'
```

Expected: compilation FAIL because the owner type and signatures are absent.

- [ ] **Step 4: Add the owner type**

```kotlin
package me.rerere.rikkahub.voiceagent.audio

enum class VoiceAudioRouteOwner(val diagnosticLabel: String) {
    Telecom("telecom"),
    DirectFallback("direct_fallback"),
}
```

- [ ] **Step 5: Thread the owner through manager and factory**

Use this factory signature:

```kotlin
fun create(
    conversationId: Uuid,
    config: VoiceAgentLaunchConfig,
    routeOwner: VoiceAudioRouteOwner,
    scope: CoroutineScope,
): ManagedVoiceCallSession
```

Use this manager signature:

```kotlin
fun start(
    conversationId: Uuid,
    config: VoiceAgentLaunchConfig,
    routeOwner: VoiceAudioRouteOwner,
    scope: CoroutineScope,
): Boolean
```

Add `_activeRouteOwner: MutableStateFlow<VoiceAudioRouteOwner?>`, expose it as `activeRouteOwner`, include it in duplicate identity, store it before starting the session, and clear it in `clearActiveSessionLocked`. Add:

```kotlin
fun routeOwnerForActiveSession(
    conversationId: Uuid,
    config: VoiceAgentLaunchConfig,
): VoiceAudioRouteOwner? = synchronized(lock) {
    _activeRouteOwner.value.takeIf {
        activeSession != null &&
            _activeConversationId.value == conversationId &&
            activeLaunchConfig == config
    }
}
```

Change the audio-factory contract now, but defer passing the owner into
`AndroidVoiceAudioEngine` until Task 5 installs the owner-selected controller:

```kotlin
private val audioFactory: (VoiceAudioRouteOwner) -> VoiceAudioEngine = {
    AndroidVoiceAudioEngine(context = context)
}
```

Call `audioFactory(routeOwner)`. Update the one injected test factory to accept and record the owner.

Update the existing `VoiceAgentCallService` call so the current pre-resolver
path chooses an explicit owner and remains buildable:

```kotlin
val startedNewSession = manager.start(
    conversationId = id,
    config = result.config,
    routeOwner = VoiceAudioRouteOwner.DirectFallback,
    scope = serviceScope,
)
```

Task 4 replaces this interim explicit fallback with the pre-session resolver
result. Do not add compatibility overloads or default owner parameters.

- [ ] **Step 6: Persist the safe label**

Add to `VoiceE2ESessionMetadata` and `toJson`:

```kotlin
val audioRouteOwner: String,
```

```kotlin
put("audioRouteOwner", audioRouteOwner)
```

Add `routeOwner: VoiceAudioRouteOwner` to `buildDefaultVoiceE2ESessionMetadata`, set `audioRouteOwner = routeOwner.diagnosticLabel`, and pass the factory owner into the builder.

Update every direct metadata constructor. In particular, add
`audioRouteOwner = "telecom"` to the `fakeSessionMetadata` fixture near the end
of `VoiceAgentRuntimeTest.kt`; otherwise the full test source will not compile.

- [ ] **Step 7: Run focused tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallManagerTest' \
  --tests '*VoiceE2ESessionMetadataTest' \
  --tests '*VoiceAgentRuntimeTest'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteOwner.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceE2ESessionMetadata.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceE2ESessionMetadataTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt
git commit -m "refactor(voice): make audio route ownership explicit"
```

### Task 2: Replace the Telecom snapshot with attempt-scoped outcomes

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomAdapter.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistry.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentConnectionService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomConnection.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistryTest.kt`

**Interfaces:**
- Produces: `VoiceAgentTelecomAttemptId`, `VoiceAgentTelecomFailure`, `VoiceAgentTelecomOutcome`, and registry methods `beginAttempt`, `activate`, `fail`, `awaitOutcome`.
- Consumes: existing `VoiceAgentTelecomCall.disconnectFromApp`.

- [ ] **Step 1: Write failing active, failure, and late-callback tests**

```kotlin
@Test
fun `matching connection completes pending attempt`() = runBlocking {
    val registry = VoiceAgentTelecomCallRegistry()
    val attempt = registry.beginAttempt()
    val call = FakeTelecomCall()

    assertEquals(true, registry.activate(attempt, call))
    assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
    assertEquals(true, registry.hasActiveConnection())
}

@Test
fun `matching failure completes pending attempt`() = runBlocking {
    val registry = VoiceAgentTelecomCallRegistry()
    val attempt = registry.beginAttempt()
    val failure = VoiceAgentTelecomFailure("telecom_outgoing_failed", "rejected")

    registry.fail(attempt, failure)

    assertEquals(VoiceAgentTelecomOutcome.Failed(failure), registry.awaitOutcome(attempt))
}

@Test
fun `late connection after failure is disconnected`() {
    val registry = VoiceAgentTelecomCallRegistry()
    val attempt = registry.beginAttempt()
    val late = FakeTelecomCall()
    registry.fail(attempt, VoiceAgentTelecomFailure("telecom_connection_timeout", "timeout"))

    assertEquals(false, registry.activate(attempt, late))
    assertEquals(1, late.disconnectCalls)
    assertEquals(false, registry.hasActiveConnection())
}
```

Retain replacement, matching-clear, and reentrant-disconnect tests with attempt-aware setup.

Add regression tests that prove:

- an `Active` outcome remains awaitable after matching clear;
- a `Failed` outcome remains awaitable after disconnect or a newer attempt;
- superseding a pending attempt completes it with a typed failure instead of stranding its waiter;
- cleanup during a blocked activation callback cannot produce `setActive` after disconnect,
  publishes no `Active` outcome, and completes failure only after the connection is retired; and
- attempt URI parsing requires the exact scheme, exact `voice-agent-` prefix, and a positive numeric ID.
- real connection retirement is one-shot and orders framework
  `setDisconnected`/`destroy` before the registry's retired callback, including
  an external disconnect during a blocked activation callback.

- [ ] **Step 2: Run and verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentTelecomCallRegistryTest'
```

Expected: compilation FAIL for missing attempt APIs.

- [ ] **Step 3: Implement durable attempt records and two-phase activation**

Add:

```kotlin
@JvmInline
value class VoiceAgentTelecomAttemptId(val value: Long)

data class VoiceAgentTelecomFailure(val diagnosticName: String, val detail: String)

sealed interface VoiceAgentTelecomOutcome {
    data object Active : VoiceAgentTelecomOutcome
    data class Failed(val failure: VoiceAgentTelecomFailure) : VoiceAgentTelecomOutcome
}
```

Do not use one conflated `StateFlow` as both live-call state and terminal outcome
storage. Keep a private record per attempt with a one-shot
`CompletableDeferred<VoiceAgentTelecomOutcome>` plus a lock-protected phase.
An outcome must remain awaitable even after the live connection clears, cleanup
runs, or a newer attempt begins. `awaitOutcome(id)` obtains that attempt's
completion and awaits it; it must never depend on observing a transient current
state.

`beginAttempt` increments a monotonic positive ID. It completes a superseded
pending attempt with `telecom_attempt_superseded`, invalidates an activation in
progress, and disconnects the previous active call outside the lock.

Make activation a validated two-phase operation:

```kotlin
fun activate(
    id: VoiceAgentTelecomAttemptId,
    connection: VoiceAgentTelecomCall,
    makeActive: () -> Unit = {},
): Boolean
```

Reserve only the matching pending attempt under the lock, run `makeActive`
outside the lock, then revalidate the same reservation. Publish and complete
`VoiceAgentTelecomOutcome.Active` only after `makeActive` succeeds and the
reservation is still current. Cleanup/failure during the callback marks the
reservation canceled but does not disconnect it before `makeActive` returns;
the activating thread then disconnects outside the lock and only afterward
completes the retained `Failed` outcome. This prevents a disconnected call from
being made active and prevents fallback from observing failure before the
in-flight Telecom connection has been retired.

`fail` completes a matching pending attempt immediately. For an activation in
progress it marks cancellation and defers completion until the activation path
disconnects the call. Rejected/stale activation disconnects the supplied call
outside the lock. `clear` removes only the matching live connection and never
erases its terminal outcome. `disconnectActive` is idempotent, invalidates
pending/in-progress work with a typed cancellation, and performs all external
disconnect callbacks outside the lock.

Terminal attempt phases must contain only scalar outcome data; they must never
retain a `VoiceAgentTelecomCall`. Keep the current live call in a separate
identity-aware slot and clear that slot on clear/disconnect/supersession.
`awaitOutcome` is the one-shot acknowledgement point: obtain the retained
completion, await it, then remove that completed attempt record without
affecting the live-call slot. This preserves late awaiting through cleanup or a
new attempt while preventing the singleton registry from accumulating every
observed historical attempt.

- [ ] **Step 4: Carry attempt ID in the call URI**

Change adapter start to accept an attempt and place:

```kotlin
Uri.fromParts(VOICE_AGENT_CALL_URI_SCHEME, "voice-agent-${attemptId.value}", null)
```

Add:

```kotlin
internal fun Uri?.voiceAgentTelecomAttemptIdOrNull(): VoiceAgentTelecomAttemptId? =
    this?.schemeSpecificPart
        ?.removePrefix("voice-agent-")
        ?.toLongOrNull()
        ?.let(::VoiceAgentTelecomAttemptId)
```

Require the exact URI scheme and prefix and reject zero/negative IDs. Remove the
no-argument adapter overload.

In `VoiceAgentConnectionService`, parse `request?.address`, create the
connection, and call `registry.activate(attemptId, connection,
connection::setActive)`. A missing or rejected ID returns a disconnected
connection. On `onCreateOutgoingConnectionFailed`, call `registry.fail` for the
parsed ID with diagnostic name `telecom_outgoing_failed`. Remove direct manager
call-status changes from this service.

Migrate `VoiceAgentCallService.startTelecomCall` atomically: after successful
registration, call `beginAttempt`, pass that ID to `startCall(attemptId)`, and
observe the retained outcome in `serviceScope`. An active outcome marks the
matching conversation background-capable; a failed outcome records its typed
diagnostic and degraded status. Immediate placement failure must fail the same
attempt. Generation/end cleanup must make stale outcome observers harmless.
There must be no production caller or compatibility overload for no-argument
`startCall()` after this task.

Make `VoiceAgentTelecomConnection` retirement explicitly one-shot. Its shared
retirement path must run `setDisconnected` and `destroy` first, then invoke the
registry callback that means **retired**, using `finally` so registry state
cannot remain stuck if framework cleanup throws. Repeated app/external
disconnect requests must not run framework retirement or the callback twice.
Back this ordering with a pure deterministic seam used by the real connection,
not a test-only duplicate.

- [ ] **Step 5: Run tests and commit**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentTelecomCallRegistryTest' \
  --tests '*VoiceAgentCallEndpointSelectorTest'
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomAdapter.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistry.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentConnectionService.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomConnection.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistryTest.kt
git commit -m "refactor(voice): scope Telecom callbacks to call attempts"
```

Expected: focused tests pass before the commit.

### Task 3: Resolve ownership before session startup

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolver.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolverTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomAdapter.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistry.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`

**Interfaces:**
- Produces: `VoiceAgentTelecomGateway`; `VoiceAgentAudioRouteResolution`; `VoiceAgentAudioRouteResolver.resolve()`.
- Consumes: Task 2 attempt registry.

- [ ] **Step 1: Write the failing resolver test fixture and cases**

Use these exact fakes:

```kotlin
private class FakeTelecomGateway(
    private val registerResult: Result<Unit> = Result.success(Unit),
    private val startResult: Result<Unit> = Result.success(Unit),
    private val onStart: (VoiceAgentTelecomAttemptId) -> Unit = {},
) : VoiceAgentTelecomGateway {
    override fun register(): Result<Unit> = registerResult

    override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
        onStart(attemptId)
        return startResult
    }
}

private class ResolverFakeCall : VoiceAgentTelecomCall {
    var disconnectCalls = 0
    override fun disconnectFromApp() { disconnectCalls += 1 }
}
```

Add all five terminal-path tests:

```kotlin
@Test
fun `active attempt selects Telecom`() = runBlocking {
    val registry = VoiceAgentTelecomCallRegistry()
    val gateway = FakeTelecomGateway(onStart = { id ->
        registry.activate(id, ResolverFakeCall())
    })
    val result = VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve()
    assertEquals(VoiceAudioRouteOwner.Telecom, result.owner)
    assertEquals(null, result.failure)
}

@Test
fun `registration failure selects direct fallback`() = runBlocking {
    val result = VoiceAgentAudioRouteResolver(
        FakeTelecomGateway(registerResult = Result.failure(IllegalStateException("denied"))),
        VoiceAgentTelecomCallRegistry(),
        100,
    ).resolve()
    assertEquals(VoiceAudioRouteOwner.DirectFallback, result.owner)
    assertEquals("telecom_register_failed", result.failure?.diagnosticName)
}

@Test
fun `placement failure selects direct fallback`() = runBlocking {
    val result = VoiceAgentAudioRouteResolver(
        FakeTelecomGateway(startResult = Result.failure(IllegalStateException("rejected"))),
        VoiceAgentTelecomCallRegistry(),
        100,
    ).resolve()
    assertEquals(VoiceAudioRouteOwner.DirectFallback, result.owner)
    assertEquals("telecom_start_failed", result.failure?.diagnosticName)
}

@Test
fun `ConnectionService rejection is preserved`() = runBlocking {
    val registry = VoiceAgentTelecomCallRegistry()
    val gateway = FakeTelecomGateway(onStart = { id ->
        registry.fail(id, VoiceAgentTelecomFailure("telecom_outgoing_failed", "framework rejected"))
    })
    val result = VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve()
    assertEquals("telecom_outgoing_failed", result.failure?.diagnosticName)
    assertEquals("framework rejected", result.failure?.detail)
}

@Test
fun `timeout selects fallback and disconnects late connection`() = runBlocking {
    val registry = VoiceAgentTelecomCallRegistry()
    var attempt: VoiceAgentTelecomAttemptId? = null
    val gateway = FakeTelecomGateway(onStart = { attempt = it })
    val result = VoiceAgentAudioRouteResolver(gateway, registry, 1).resolve()
    val late = ResolverFakeCall()

    val accepted = registry.activate(requireNotNull(attempt), late)

    assertEquals("telecom_connection_timeout", result.failure?.diagnosticName)
    assertEquals(false, accepted)
    assertEquals(1, late.disconnectCalls)
}
```

- [ ] **Step 2: Run and verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentAudioRouteResolverTest'
```

Expected: compilation FAIL because resolver types are absent.

- [ ] **Step 3: Add gateway and resolver**

First split Task 2's destructive await into observation and acknowledgement:

```kotlin
suspend fun observeOutcome(id: VoiceAgentTelecomAttemptId): VoiceAgentTelecomOutcome
fun acknowledgeOutcome(id: VoiceAgentTelecomAttemptId)
suspend fun awaitOutcome(id: VoiceAgentTelecomAttemptId): VoiceAgentTelecomOutcome
```

`observeOutcome` only awaits the retained completion. `acknowledgeOutcome`
removes only that same completed record and never touches the live-call slot.
`awaitOutcome` remains the convenience operation that observes then
acknowledges. Add an attempt-scoped `retireAttempt(id, failure)` that invalidates
only the named pending/activating/active attempt, disconnects its live call
outside the lock, and leaves a terminal outcome available for acknowledgement.

Make the adapter implement:

```kotlin
interface VoiceAgentTelecomGateway {
    fun register(): Result<Unit>
    fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit>
}
```

Create:

```kotlin
data class VoiceAgentAudioRouteResolution(
    val owner: VoiceAudioRouteOwner,
    val failure: VoiceAgentTelecomFailure? = null,
)

class VoiceAgentAudioRouteResolver(
    private val gateway: VoiceAgentTelecomGateway,
    private val registry: VoiceAgentTelecomCallRegistry,
    private val timeoutMs: Long = 3_000L,
) {
    suspend fun resolve(): VoiceAgentAudioRouteResolution {
        val attempt = registry.beginAttempt()
        gateway.register().exceptionOrNull()?.let {
            return fallback(attempt, "telecom_register_failed", it)
        }
        gateway.startCall(attempt).exceptionOrNull()?.let {
            return fallback(attempt, "telecom_start_failed", it)
        }
        return when (val outcome = withTimeoutOrNull(timeoutMs) { registry.observeOutcome(attempt) }) {
            VoiceAgentTelecomOutcome.Active -> {
                registry.acknowledgeOutcome(attempt)
                VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.Telecom)
            }
            is VoiceAgentTelecomOutcome.Failed -> {
                registry.acknowledgeOutcome(attempt)
                VoiceAgentAudioRouteResolution(
                    VoiceAudioRouteOwner.DirectFallback,
                    outcome.failure,
                )
            }
            null -> fallback(
                attempt,
                "telecom_connection_timeout",
                IllegalStateException("Android Telecom did not become active within ${timeoutMs}ms"),
            )
        }
    }

    private suspend fun fallback(
        attempt: VoiceAgentTelecomAttemptId,
        name: String,
        error: Throwable,
    ): VoiceAgentAudioRouteResolution {
        val failure = VoiceAgentTelecomFailure(name, error.message ?: error.javaClass.simpleName)
        registry.fail(attempt, failure)
        val retired = withContext(NonCancellable) {
            registry.awaitOutcome(attempt)
        }
        val terminalFailure = (retired as VoiceAgentTelecomOutcome.Failed).failure
        return VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.DirectFallback, terminalFailure)
    }
}
```

The timed operation must be non-destructive. If completion races the timeout,
the fallback path can still retrieve and acknowledge the retained outcome,
returning Telecom when Active won and DirectFallback when failure won.

The second await is required after registration, placement, or timeout failure:
it is Task 2's one-shot acknowledgement/prune point and, for an activation in
progress, does not return until the canceled Telecom connection is definitively
retired. Never return fallback ownership while an attempt record is unconsumed
or an activation is still being retired.

Wrap the post-`beginAttempt` resolver body in cancellation handling. On caller
`CancellationException`, enter `NonCancellable`, call `retireAttempt` for this
exact ID with `telecom_resolution_cancelled`, await/acknowledge its terminal
outcome, then rethrow the original cancellation. Never use global
`disconnectActive` for resolver cancellation because a newer attempt may exist.
Add deterministic Pending and blocked-Activating cancellation tests plus a
barrier-controlled completion/timeout race test; all must end with no retained
attempt and no ownerless live call.

- [ ] **Step 4: Bind and test**

```kotlin
single<VoiceAgentTelecomGateway> { VoiceAgentTelecomAdapter(context = get()) }
single { VoiceAgentAudioRouteResolver(gateway = get(), registry = get()) }
```

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentAudioRouteResolverTest' \
  --tests '*VoiceAgentTelecomCallRegistryTest'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolver.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomAdapter.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomCallRegistry.kt \
  app/src/main/java/me/rerere/rikkahub/di/AppModule.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolverTest.kt
git commit -m "feat(voice): resolve audio owner before session startup"
```

### Task 4: Put call startup behind the ownership barrier

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartup.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolver.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolverTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServicePolicyTest.kt`
- Delete: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomAttemptCoordinatorTest.kt`

**Interfaces:**
- Consumes: `routeOwnerForActiveSession` and `VoiceAgentAudioRouteResolver`.
- Produces: `VoiceAgentCallStartup.start`; enforced ordering `resolve → generation check → manager.start`.

- [ ] **Step 1: Write failing retained-owner and orchestration tests**

Create local fake factory/session/config helpers, then prove:

- a matching active session retains its immutable owner without a new resolver call;
- while new resolution is suspended, no manager/factory start occurs;
- Active produces exactly one manager start with `Telecom`;
- failed/timeout resolution produces exactly one manager start with
  `DirectFallback` and preserves the typed failure; and
- a generation-stale Telecom result performs no manager start, retires only
  the exact attempt ID carried by the resolution, and awaits/acknowledges that
  attempt so its unclaimed live Telecom call is disconnected;
- an older stale Telecom result cannot disconnect a newer attempt's active call;
  it retires and acknowledges only the attempt ID carried by that older
  resolution.

- [ ] **Step 2: Run and verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallStartupTest'
```

Expected: compilation FAIL because the startup barrier does not exist.

- [ ] **Step 3: Implement the typed startup barrier**

```kotlin
sealed interface VoiceAgentCallStartupResult {
    data class Started(
        val resolution: VoiceAgentAudioRouteResolution,
        val startedNewSession: Boolean,
    ) : VoiceAgentCallStartupResult

    data class Stale(
        val resolution: VoiceAgentAudioRouteResolution,
    ) : VoiceAgentCallStartupResult
}

class VoiceAgentCallStartup internal constructor(
    private val manager: VoiceAgentCallManager,
    private val telecomRegistry: VoiceAgentTelecomCallRegistry,
    private val resolveRoute: suspend () -> VoiceAgentAudioRouteResolution,
) {
    constructor(
        manager: VoiceAgentCallManager,
        routeResolver: VoiceAgentAudioRouteResolver,
        telecomRegistry: VoiceAgentTelecomCallRegistry,
    ) : this(manager, telecomRegistry, routeResolver::resolve)

    suspend fun start(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        scope: CoroutineScope,
        isCurrent: () -> Boolean,
    ): VoiceAgentCallStartupResult {
        val resolution = manager.routeOwnerForActiveSession(conversationId, config)
            ?.let(::VoiceAgentAudioRouteResolution)
            ?: resolveRoute()
        if (!isCurrent()) {
            resolution.telecomAttemptId?.let { attemptId ->
                withContext(NonCancellable) {
                    val retirementError = runCatching {
                        telecomRegistry.retireAttempt(
                            attemptId,
                            VoiceAgentTelecomFailure(
                                diagnosticName = "telecom_startup_stale",
                                detail = "Telecom startup attempt ${attemptId.value} became stale",
                            ),
                        )
                    }.exceptionOrNull()
                    val acknowledgementError = runCatching {
                        telecomRegistry.awaitOutcome(attemptId)
                    }.exceptionOrNull()
                    retirementError?.let { error ->
                        acknowledgementError?.let(error::addSuppressed)
                        throw error
                    }
                    acknowledgementError?.let { throw it }
                }
            }
            return VoiceAgentCallStartupResult.Stale(resolution)
        }
        return VoiceAgentCallStartupResult.Started(
            resolution = resolution,
            startedNewSession = manager.start(
                conversationId = conversationId,
                config = config,
                routeOwner = resolution.owner,
                scope = scope,
            ),
        )
    }
}
```

Extend `VoiceAgentAudioRouteResolution` with
`telecomAttemptId: VoiceAgentTelecomAttemptId? = null`. Every resolver path
that returns `Telecom` carries its exact attempt ID, including an Active result
that wins at the timeout boundary. DirectFallback and retained-session
resolutions carry `null`.

On a stale result, never call global `disconnectActive()`. If the resolution
carries an attempt ID, enter `NonCancellable`, call `retireAttempt` for that ID
with `telecom_startup_stale`, then call `awaitOutcome` to await and acknowledge
that same attempt. Keep retirement and await/acknowledgement in independent
`runCatching` blocks so await/acknowledgement still runs if retirement throws.
If both operations fail, preserve the retirement error and attach the
await/acknowledgement error as suppressed. This exact-attempt cleanup must not
affect a newer pending or active attempt.

- [ ] **Step 4: Reorder service startup**

Inject `VoiceAgentCallStartup` and remove the concrete adapter/coordinator path.
In the available-config branch call:

```kotlin
val startupResult = callStartup.start(
    conversationId = id,
    config = result.config,
    scope = serviceScope,
    isCurrent = { startGeneration == callGeneration },
)
if (startupResult is VoiceAgentCallStartupResult.Stale) {
    return@launch
}
val routeResolution = startupResult.resolution
val startedNewSession = startupResult.startedNewSession
routeResolution.failure?.let { failure ->
    manager.recordDiagnostic(failure.diagnosticName, failure.detail)
    manager.updateCallStatus(VoiceCallStatus.Degraded("Telecom unavailable: ${failure.detail}"))
}
if (routeResolution.owner == VoiceAudioRouteOwner.Telecom) {
    manager.updateCallStatus(VoiceCallStatus.BackgroundCapable)
}
```

Replace the unconditional status write with a production-used pure policy:

```kotlin
internal fun shouldPublishVoiceCallBackgroundCapable(
    owner: VoiceAudioRouteOwner,
    current: VoiceCallStatus,
): Boolean = owner == VoiceAudioRouteOwner.Telecom && current !is VoiceCallStatus.Degraded
```

Test that retained Telecom + `Degraded` remains degraded during an errored or
no-progress reconnect, while a non-degraded Telecom start may publish
`BackgroundCapable`; DirectFallback never does.

Delete `VoiceAgentTelecomAttemptCoordinator`, its test file,
`startTelecomCall`, `telecomConversationId`, `needsTelecomSetup`, and every
branch that places Telecom after manager startup. Keep registry disconnect in
explicit end, failed startup, and service destruction. `VoiceAgentCallService`
must contain no `manager.start` call; the only call is inside the tested barrier
after awaited resolution and generation validation. Wait for session startup
only when `startedNewSession` is true; retained sessions keep their owner and do
not start a new Telecom attempt.

- [ ] **Step 5: Bind and test**

Add:

```kotlin
single { VoiceAgentCallStartup(manager = get(), routeResolver = get(), telecomRegistry = get()) }
```

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallStartupTest' \
  --tests '*VoiceAgentCallManagerTest' \
  --tests '*VoiceAgentAudioRouteResolverTest' \
  --tests '*VoiceAgentTelecomCallRegistryTest'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartup.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolver.kt \
  app/src/main/java/me/rerere/rikkahub/di/AppModule.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallStartupTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentAudioRouteResolverTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServicePolicyTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomAttemptCoordinatorTest.kt
git commit -m "fix(voice): establish Telecom before starting audio"
```

### Task 5: Isolate direct route mutation behind an owner-selected controller

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteController.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteControllerTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioFocusPolicyTest.kt`

**Interfaces:**
- Consumes: `VoiceAudioRouteOwner`.
- Produces: controller methods `beforeCapture`, `configureRecorder`, `afterCapture`, `close`; lazy owner selector; Telecom no-op controller.

- [ ] **Step 1: Write failing lazy-selection tests**

```kotlin
@Test
fun `Telecom owner never constructs or calls direct controller`() {
    var directCreated = 0
    val selected = selectVoiceAudioRouteController(VoiceAudioRouteOwner.Telecom) {
        directCreated += 1
        RecordingRouteController()
    }

    selected.beforeCapture()
    selected.afterCapture()
    selected.close()

    assertEquals(0, directCreated)
}

@Test
fun `direct fallback delegates lifecycle once`() {
    val direct = RecordingRouteController()
    val selected = selectVoiceAudioRouteController(VoiceAudioRouteOwner.DirectFallback) { direct }

    selected.beforeCapture()
    selected.afterCapture()
    selected.close()

    assertEquals(listOf("before", "after", "close"), direct.calls)
}
```

The fake implements `configureRecorder` as a no-op because local JVM tests do not construct `AudioRecord`.

- [ ] **Step 2: Run and verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAudioRouteControllerTest'
```

Expected: compilation FAIL because the controller contract is absent.

- [ ] **Step 3: Add contract, no-op, and lazy selector**

```kotlin
package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioRecord

internal interface VoiceAudioRouteController {
    fun beforeCapture()
    fun configureRecorder(recorder: AudioRecord)
    fun afterCapture()
    fun close()
}

private object TelecomVoiceAudioRouteController : VoiceAudioRouteController {
    override fun beforeCapture() = Unit
    override fun configureRecorder(recorder: AudioRecord) = Unit
    override fun afterCapture() = Unit
    override fun close() = Unit
}

internal fun selectVoiceAudioRouteController(
    owner: VoiceAudioRouteOwner,
    directFactory: () -> VoiceAudioRouteController,
): VoiceAudioRouteController = when (owner) {
    VoiceAudioRouteOwner.Telecom -> TelecomVoiceAudioRouteController
    VoiceAudioRouteOwner.DirectFallback -> directFactory()
}
```

- [ ] **Step 4: Move every direct operation into the Android controller**

Move, without behavioral expansion, these fields and helper groups from `AndroidVoiceAudioEngine`:

- audio focus request/state and `VoiceAudioFocusPolicy` callback;
- previous audio mode and communication-device state;
- preferred Bluetooth recorder selection;
- Bluetooth SCO state;
- headset proxy, listener, and voice-recognition state; and
- Bluetooth permission and safe route labels.

Implement the lifecycle exactly:

```kotlin
override fun beforeCapture() {
    check(!closed) { "Direct audio route controller is closed" }
    requestAudioFocusBestEffort()
    prepareVoiceCommunicationRoutingBestEffort()
}

override fun configureRecorder(recorder: AudioRecord) {
    val device = selectPreferredBluetoothCaptureDeviceOrNull() ?: return
    val preferredAccepted = runCatching { recorder.setPreferredDevice(device) }
        .onFailure { Log.w(TAG, "Direct preferred Bluetooth device failed", it) }
        .getOrDefault(false)
    val communicationAccepted = setCommunicationDeviceBestEffort(device)
    Log.d(
        TAG,
        "Direct capture route=${device.safeRouteLabel()} " +
            "preferredAccepted=$preferredAccepted communicationAccepted=$communicationAccepted",
    )
}

override fun afterCapture() {
    if (!closed) clearVoiceCommunicationRoutingBestEffort()
}

override fun close() {
    if (closed) return
    closed = true
    clearVoiceCommunicationRoutingBestEffort()
    closeBluetoothHeadsetProxy()
    abandonAudioFocus()
}
```

Protect `closed` and acquired-resource fields with the controller lock. Preserve current acquisition flags so cleanup only releases successful focus, selected device, started SCO, voice recognition, proxy, and changed mode.

- [ ] **Step 5: Reduce the PCM engine to controller calls**

Require ownership in the constructor and select lazily:

```kotlin
class AndroidVoiceAudioEngine(
    context: Context,
    routeOwner: VoiceAudioRouteOwner,
) : VoiceAudioEngine {
    private val context = context.applicationContext
    private val routeController = selectVoiceAudioRouteController(routeOwner) {
        AndroidDirectAudioRouteController(this.context, ::notifyAudioError)
    }
```

Use:

```kotlin
stopCapture()
routeController.beforeCapture()
```

Call `routeController.configureRecorder(recorder)` immediately after record creation. Call `routeController.afterCapture()` from `stopCapture`, and `routeController.close()` once from `release`. Remove all direct routing fields, helpers, and cleanup from the engine. Log only:

```kotlin
Log.d(TAG, "Voice audio route owner=${routeOwner.diagnosticLabel}")
```

Complete the factory wiring intentionally deferred from Task 1:

```kotlin
private val audioFactory: (VoiceAudioRouteOwner) -> VoiceAudioEngine = { owner ->
    AndroidVoiceAudioEngine(context = context, routeOwner = owner)
}
```

There must be no production construction of `AndroidVoiceAudioEngine` without
an explicit owner after this task.

- [ ] **Step 6: Update focus-policy wording and run audio tests**

Before running the suite, put the controller's Android mutations behind a
narrow injectable production platform seam (or an equivalent production-used
pure state seam) so cleanup and delayed callbacks are executable in local JVM
tests. Add deterministic tests for:

- granted and failed focus, plus focus granted concurrently with close;
- accepted and rejected communication-device selection;
- partial/repeated SCO acquisition and one-shot cleanup;
- accepted voice recognition and exact stop pairing;
- delayed headset-profile delivery after close and disconnect after close; and
- repeated `afterCapture`/`close` with exact acquire/release call counts and no
  mutation after close.

The tests must exercise the seam used by `AndroidDirectAudioRouteController`,
not a duplicate test-only state machine. Remove the obsolete
`BLUETOOTH_HEADSET_PROFILE_WAIT_STEPS` and
`BLUETOOTH_HEADSET_PROFILE_WAIT_MS` constants from `AndroidVoiceAudioEngine`.

Rename Telecom-specific test descriptions in `VoiceAudioFocusPolicyTest` to direct-fallback wording; assertions remain unchanged.

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*AndroidDirectAudioRouteControllerTest' \
  --tests '*VoiceAudioFocusPolicyTest' \
  --tests '*VoiceAudioRouteSelectorTest' \
  --tests '*VoicePlaybackWriterTest' \
  --tests '*PlaybackEventDispatcherTest' \
  --tests '*VoiceAgentRuntimeTest'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Prove the PCM engine has no direct mutation**

```bash
if rg -n 'requestAudioFocus|abandonAudioFocus|setCommunicationDevice|clearCommunicationDevice|startBluetoothSco|stopBluetoothSco|startVoiceRecognition|stopVoiceRecognition|setPreferredDevice' \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt; then
  printf 'Direct route mutation remains in AndroidVoiceAudioEngine.kt\n' >&2
  exit 1
fi
```

Expected: no matches.

- [ ] **Step 8: Commit**

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteController.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteControllerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioFocusPolicyTest.kt
git commit -m "fix(voice): give Telecom exclusive audio routing ownership"
```

### Task 6: Run regression and package verification

**Files:**
- Verify: all files changed above.
- Verify: `app/build/outputs/apk/debug/app-universal-debug.apk`.

**Interfaces:**
- Consumes: this plan plus the fail-closed Sentry plan.
- Produces: complete unit/build evidence before device deployment.

- [ ] **Step 1: Run all focused tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallManagerTest' \
  --tests '*VoiceE2ESessionMetadataTest' \
  --tests '*VoiceAgentTelecomCallRegistryTest' \
  --tests '*VoiceAgentAudioRouteResolverTest' \
  --tests '*VoiceAgentCallStartupTest' \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*VoiceAudioFocusPolicyTest' \
  --tests '*VoiceAudioRouteSelectorTest'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the full app unit suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the Sentry contract and build universal APK**

```bash
scripts/test-voice-agent-sentry-build.sh
./gradlew :app:assembleDebug
test -f app/build/outputs/apk/debug/app-universal-debug.apk
```

Expected: Sentry contract and build pass; universal APK exists.

- [ ] **Step 4: Inspect repository state**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors or unrelated changes. Do not add, edit, delete,
or move the unrelated
`docs/superpowers/plans/2026-07-14-hermes-announcement-safe-playback.md`; if it
is present, leave it unchanged, and if it is absent, leave it absent.

### Task 7: Install and verify both ownership paths on the phone

**Files:**
- Verify: installed debug APK, logcat, and latest `session.json`.

**Interfaces:**
- Consumes: `ADB_SERIAL` set to the current Tailscale wireless ADB host and port.
- Produces: physical evidence for Telecom-first Bluetooth and controlled direct fallback.

- [ ] **Step 1: Connect and install**

```bash
ADB_SERIAL="${ADB_SERIAL:?Set ADB_SERIAL to 100.97.115.82:<current-port>}"
adb connect "$ADB_SERIAL"
scripts/adb-device-ready.sh "$ADB_SERIAL"
adb -s "$ADB_SERIAL" install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

Expected: readiness succeeds and install reports `Success`.

- [ ] **Step 2: Verify the normal Telecom-owned Bluetooth call**

```bash
adb -s "$ADB_SERIAL" logcat -c
```

With the Bluetooth headset connected, start Voice Agent, speak one prompt, wait for physical playback, and end normally. Then capture:

```bash
adb -s "$ADB_SERIAL" logcat -d -v time \
  -s VoiceAgentCallService VoiceAgentConnectionService VoiceAgentTelecomConnection AndroidVoiceAudioEngine \
  > /tmp/rikkahub-voice-route.log
rg -n 'route owner|call endpoint changed|call audio state changed|starting audio capture|audio focus|Bluetooth SCO|voice recognition|communication route|preferred Bluetooth' \
  /tmp/rikkahub-voice-route.log
```

Expected order: active Telecom/Bluetooth endpoint, `Voice audio route owner=telecom`, then capture. No direct focus, SCO, communication-device, preferred-device, or headset voice-recognition request may appear.

- [ ] **Step 3: Inspect safe session metadata**

```bash
TRACE_ID="$(adb -s "$ADB_SERIAL" exec-out run-as me.rerere.rikkahub.debug \
  cat no_backup/voice-e2e/latest-trace-id.txt | tr -d '\r\n')"
[[ "$TRACE_ID" =~ ^[A-Za-z0-9._-]+$ ]] && [[ "$TRACE_ID" != '.' ]] && [[ "$TRACE_ID" != '..' ]]
adb -s "$ADB_SERIAL" exec-out run-as me.rerere.rikkahub.debug \
  cat "no_backup/voice-e2e/$TRACE_ID/session.json" \
  | jq '{audioRouteOwner,sentryDsnConfigured,sentryTracingEnabled,sentryPropagationCreated}'
```

Expected: `audioRouteOwner` is `telecom`; `sentryDsnConfigured` and `sentryPropagationCreated` are true. No DSN is read or printed.

- [ ] **Step 4: Force and verify direct fallback**

Disable only the debug app's connection service, then start and end one short Voice Agent call:

```bash
COMPONENT='me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentConnectionService'
adb -s "$ADB_SERIAL" shell pm disable-user --user 0 "$COMPONENT"
adb -s "$ADB_SERIAL" logcat -c
```

After the call, always re-enable the component:

```bash
adb -s "$ADB_SERIAL" shell pm enable "$COMPONENT"
```

Capture logcat as above. Expected: `audioRouteOwner=direct_fallback` and a Telecom start/rejection diagnostic precede direct focus/routing. No late Telecom connection becomes active. Re-enable the component before any further normal-call test, including when the fallback call fails.

- [ ] **Step 5: Record final state**

```bash
git status --short
git log --oneline 1f9bf814..HEAD
```

Expected: the explicit feature range lists every implementation commit after
base `1f9bf814`; only known user-owned untracked files remain.
