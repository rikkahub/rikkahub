# Voice Capture State and Direct Audio Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace nullable capture ownership, synchronous Bluetooth polling, and marker-handle casts with sealed capture states and typed callback-driven Android adapters.

**Architecture:** A dedicated capture owner transfers one exact route lease through `Reserved`, `Routed`, `Activating`, `Active`, and `Retiring` phases. `Activating` owns the recorder, lazy task, route, and an admitted-operation barrier before external recorder startup begins, so retirement cannot release routing ahead of recorder cleanup. Capture startup becomes suspending, while a generic typed Bluetooth coordinator completes a deferred profile connection and Android-specific adapters retain real platform objects without runtime recovery casts.

**Tech Stack:** Kotlin, Android `AudioManager`, `BluetoothProfile`, `BluetoothHeadset`, `AudioRecord`, kotlinx.coroutines, `CompletableDeferred`, JUnit 4, kotlinx-coroutines-test, Gradle.

## Global Constraints

- Preserve Telecom-first selection and zero direct Android routing mutations for Telecom-owned calls.
- Preserve the existing one-second Bluetooth profile bound: `10 * 100ms == 1_000ms`.
- Audio-focus policy failure remains fatal; missing permission, unavailable profile, timeout, rejected recognition, SCO failure, and capture-device failure remain best-effort.
- Cleanup order remains capture task, recorder stop, recorder release, route lease; first failure is primary and later failures are suppressed.
- Capture and route locks protect state transfer only; Android calls and waits run outside them.
- Retirement remains idempotent, exact-owner, concurrency-safe, and result-replaying.
- Add no dependencies and leave no marker-handle or polling compatibility path.
- Modify only `/home/muly/code/rikkahub`.
- This plan implements review findings 1, 3, and 5 as one coupled, independently executable subsystem plan.

## Binding Plan Contract

### File ownership

Create:

- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioCaptureOwnership.kt` — capture token, sealed states, ownership publication, and retirement joining.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectBluetoothCaptureAdapter.kt` — Android Bluetooth operations with exact `BluetoothHeadset`/`BluetoothDevice` types.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectCaptureDeviceAdapter.kt` — `AudioDeviceInfo` enumeration, selection, recorder configuration, and communication-device cleanup.

Modify:

- `VoiceAudioEngine.kt` — suspending capture-start contract.
- `VoiceAudioRouteController.kt` — suspending capture-route preparation contract and Telecom no-op implementation.
- `AndroidVoiceAudioEngine.kt` — orchestration only; remove embedded ownership state.
- `AndroidDirectAudioRouteController.kt` — aggregate focus/mode/Bluetooth/device leases.
- `DirectAudioRouteCapabilities.kt` — focused policy contracts and typed Bluetooth coordinator; remove Android handle wrappers and capture-device handles.
- `VoiceAgentCallSession.kt`, `VoiceSessionResourceCleaner.kt`, and `FakeVoiceAudioEngine.kt` — await initial capture, own focused jobs for unmute/debug restarts, and cancel those jobs before cleanup invalidates audio ownership.
- Focused tests named in the tasks below.

### Exact route and engine interfaces

```kotlin
internal interface VoiceAudioCaptureRouteLease {
    suspend fun prepare()
    fun configureRecorder(recorder: AudioRecord)
    fun retire()
}

interface VoiceAudioEngine {
    suspend fun startCapture(
        onPcm16: (ByteArray) -> Unit,
        onDebugInjectionComplete: () -> Unit = {},
    )
    // Existing non-capture methods keep their current signatures.
}
```

`TelecomVoiceAudioCaptureRouteLease.prepare()` is an immediate no-op.

### Exact capture-owner interface

Move the existing generic owner to the new file and change its public internal surface to:

```kotlin
internal class VoiceAudioCaptureToken internal constructor()

internal enum class VoiceAudioCaptureStartOutcome {
    Started,
    Rejected,
}

internal class VoiceAudioCaptureOwnership<Recorder : Any, CaptureTask : Any>(
    private val startRecorder: (Recorder) -> Unit,
    private val isRecorderRecording: (Recorder) -> Boolean,
    private val stopRecorder: (Recorder) -> Unit,
    private val releaseRecorder: (Recorder) -> Unit,
    private val startTask: (CaptureTask) -> Boolean,
    private val cancelTask: (CaptureTask) -> Unit,
    private val onRetirementResultPublished: (Result<Unit>) -> Unit = {},
) {
    fun reserve(): VoiceAudioCaptureToken
    fun publishRoute(token: VoiceAudioCaptureToken, routeLease: VoiceAudioCaptureRouteLease): Boolean
    fun publishAndStart(
        token: VoiceAudioCaptureToken,
        recorder: Recorder,
        task: CaptureTask,
    ): VoiceAudioCaptureStartOutcome
    fun abort(token: VoiceAudioCaptureToken): Boolean
    fun stop()
    fun release(): Boolean
    fun terminate(token: VoiceAudioCaptureToken, recorder: Recorder): Boolean
    fun isCurrent(token: VoiceAudioCaptureToken, recorder: Recorder): Boolean
    fun isReleased(): Boolean
}
```

### Capture state contract

The owner uses one private sealed `CaptureState<Recorder, CaptureTask>` with these legal variants only:

- `Idle`.
- `Starting.Reserved(token)`.
- `Starting.Routed(token, routeLease, retirementBarrier)`.
- `Starting.Activating(token, recorder, task, routeLease, activationBarrier, retirementBarrier)`.
- `Active(token, recorder, task, routeLease, retirementBarrier)`.
- `Retiring(ownedResources, retirementBarrier, terminalTarget)`.
- `Released`.

`terminalTarget` is `Idle` or `Released`. Release may upgrade `Idle` to `Released` while retirement is running; no operation downgrades it. A route local that loses `publishRoute` remains caller-owned and is retired locally. Recorder/task locals that lose admission into `Starting.Activating` are cleaned locally. After admission, the state owns recorder, task, and route together until exact ordered retirement completes.

`publishAndStart` first transfers the recorder and lazy task into `Starting.Activating` under the state lock. It then performs recorder start/check outside the lock as the registered activation-barrier owner. `stop`, `release`, or `abort` may move `Activating` to `Retiring`, but retirement must wait outside the state lock for the admitted activation to finish before cleanup proceeds in exact order: cancel task, stop recorder, release recorder, retire route. The route is never retired while `startRecorder` is still admitted.

After recorder start/check, `publishAndStart` completes the activation barrier, publishes `Active` only if the exact `Activating` state remains current, and then calls cancellation-safe `startTask`. Production injects `Job::start`, whose `false` result means concurrent retirement canceled the lazy job before launch. If retirement was requested reentrantly on the activation-owner thread, the retirement caller records `Retiring` and returns without self-waiting; the activation owner completes the barrier and performs or joins retirement before `publishAndStart` returns. No `VoiceAudioCaptureLifecycle` or `recorderLock` serialization monitor remains.

### Exact Bluetooth contracts

```kotlin
internal interface DirectBluetoothCaptureLease : DirectAudioResourceLease {
    suspend fun prepare()
}

internal fun interface DirectBluetoothCaptureCapability {
    fun acquire(): DirectBluetoothCaptureLease?
}

internal interface DirectBluetoothHeadsetListener<Headset : Any> {
    fun onConnected(headset: Headset)
    fun onDisconnected()
}

internal interface DirectBluetoothCaptureOperations<Headset : Any, Device : Any> {
    fun createCallbackDispatcher(): DirectBluetoothCallbackDispatcher
    fun hasConnectPermission(): Boolean
    fun requestHeadsetProxy(listener: DirectBluetoothHeadsetListener<Headset>): Boolean
    fun closeHeadsetProxy(headset: Headset)
    fun connectedDevices(headset: Headset): List<Device>
    fun safeLabel(device: Device): String
    fun startVoiceRecognition(headset: Headset, device: Device): Boolean
    fun stopVoiceRecognition(headset: Headset, device: Device)
    fun startBluetoothSco()
    fun setBluetoothScoEnabled(enabled: Boolean)
    fun stopBluetoothSco()
}

internal class SystemDirectBluetoothCaptureCapability<Headset : Any, Device : Any>(
    private val operations: DirectBluetoothCaptureOperations<Headset, Device>,
    private val profileWaitMillis: Long = 1_000L,
) : DirectBluetoothCaptureCapability
```

There is no `awaitHeadset` operation. The system lease owns a `CompletableDeferred<Headset?>`; `prepare()` uses a timeout to await it. Production operations implement `DirectBluetoothCaptureOperations<BluetoothHeadset, BluetoothDevice>` directly, so no `as? Android...` or `requireNotNull` recovery cast exists.

### Bluetooth behavior

- Before `prepare`, connection callbacks record the typed headset and complete the deferred but do not route.
- `prepare` opens the routing gate, awaits up to `profileWaitMillis`, requests recognition when connected, and requests SCO once even when no profile becomes available.
- After timeout, a later connection may request recognition only while the lease is active; SCO is not requested twice.
- Retirement closes callback admission, completes pending waits, joins admitted callback mutations, then stops accepted recognition, disables/stops owned SCO, closes the exact proxy, and closes the dispatcher.
- Any Android operation that finishes after retirement won the state race must immediately roll itself back instead of publishing ownership.

### Capture-device boundary

Delete `DirectAudioCaptureDevice`, `DirectAudioCaptureDeviceHandle`, and `DirectCaptureDeviceOperations`. `AndroidDirectCaptureDeviceAdapter` implements `DirectCaptureDeviceCapability` directly and keeps every `AudioDeviceInfo` private. Route preference remains in `selectPreferredCaptureRoute(List<VoiceAudioRouteDevice>)`; the adapter maps Android devices to domain values, selects by ID, performs Android calls, and returns only `DirectAudioResourceLease?`.

### Route-controller concurrency

- `AndroidDirectAudioRouteController.acquireCapture()` reserves an acquisition identity under its lock, invokes focus/mode/Bluetooth capability acquisition outside the lock, then either returns the assembled route lease or retires it if `close()` won the state race.
- `close()` marks the controller closed and snapshots capability-close work under the lock, then closes outside it. An in-flight acquisition that finishes later must retire every locally acquired lease instead of publishing.
- `DirectVoiceAudioCaptureRouteLease.prepare()` invokes the exact Bluetooth lease's suspending preparation outside its lock.
- `configureRecorder()` reserves a configuration operation under the lease lock, calls `DirectCaptureDeviceCapability.configure()` outside it, then publishes the returned resource lease or retires it immediately if route retirement won.
- Route retirement detaches published leases under the lock and retires them outside it. Late preparation/configuration results roll themselves back; no controller or route-lease monitor encloses an Android call, callback, wait, or retirement.

### Session behavior

- Initial connected-resource activation awaits `audio.startCapture`, preserving fatal capture-start propagation.
- Unmute and debug-injection restart launch one focused `captureStartJob` in the existing session scope.
- Starting a new focused job cancels the previous focused job.
- `VoiceSessionResourceCleaner` receives `cancelCaptureStart: () -> Unit`; every cleanup sequence invokes it after `prepare()` and before detaching bridges or invalidating/stopping audio.
- Mute invokes the same cancellation directly. Reconnect, failure, end, and close reach it through the cleaner before `audio.stopCapture` or `audio.release` invalidates engine state.
- Cancellation before activation admission cleans caller-local recorder/task resources and retires or aborts the routed lease. Cancellation after admission completes the activation barrier and performs or joins state-owned ordered retirement. In both cases the caller observes the original `CancellationException` with cleanup failures suppressed.

## Illustrative Implementation Guidance

Use two-phase ownership around every Android call: reserve intent under a lock, invoke Android outside it, then publish ownership or roll the operation back after rechecking state. Do not retain a lock merely to make a race impossible; make the losing side perform exact rollback.

---

### Task 1: Extract Sealed Capture Ownership Without Behavior Change

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioCaptureOwnership.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt:38-271,430-525`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioCaptureOwnershipTest.kt`

**Interfaces:**
- Produces: exact capture-owner interface and sealed-state contract from the binding section.
- Consumes: current synchronous `VoiceAudioCaptureRouteLease`; suspending `prepare()` is added in Task 2.

**Invariants:**
- The state contains no independently nullable recorder/task/lease/retirement fields.
- `VoiceAudioCaptureLifecycle` and `recorderLock` are deleted; recorder/task startup and cleanup use token rechecks rather than external-call serialization monitors.
- `Reserved` can become `Routed`, `Idle`, or `Released`; `Routed` can become `Activating`, `Retiring`, or `Released`; `Activating` can become `Active` or `Retiring`; `Active` can become `Retiring`; `Retiring` ends only at its terminal target.
- Existing retirement-result replay and cleanup order remain unchanged.

**Acceptance:**
- Existing capture ownership tests pass after adapting `begin(lease)` to `reserve()` plus `publishRoute(token, lease)`.
- New tests cover stop/release in every starting phase and release upgrading an in-flight retirement target.
- Blocking-recorder tests prove stop and release cannot retire the route until admitted activation finishes and recorder/task cleanup has completed first.
- `AndroidVoiceAudioEngine.kt` no longer declares capture state classes or nullable ownership fields.
- `AndroidVoiceAudioEngine.kt` contains no `VoiceAudioCaptureLifecycle` or `recorderLock`.

- [ ] **Step 1: Adapt tests to the new reservation and route-publication API**

For every existing setup, replace:

```kotlin
val token = ownership.begin(lease)
```

with:

```kotlin
val token = ownership.reserve()
assertTrue(ownership.publishRoute(token, lease))
```

Add these behaviors:

```kotlin
@Test
fun `stop during reserved rejects late route and retires it locally`() {
    val ownership = fakeOwnership()
    val token = ownership.reserve()
    ownership.stop()
    val lease = FakeCaptureRouteLease()

    assertFalse(ownership.publishRoute(token, lease))
    assertEquals(0, lease.retireCalls) // caller still owns rejected publication
    lease.retire()
    assertEquals(1, lease.retireCalls)
}

@Test
fun `release during routed retires lease and permanently rejects reserve`() {
    val ownership = fakeOwnership()
    val lease = FakeCaptureRouteLease()
    val token = ownership.reserve()
    assertTrue(ownership.publishRoute(token, lease))

    assertTrue(ownership.release())

    assertEquals(1, lease.retireCalls)
    assertEquals("Voice audio engine is released", runCatching { ownership.reserve() }.exceptionOrNull()?.message)
}
```

Add an activation race test with a `startRecorder` fake that records entry and blocks. Run `publishAndStart` on a worker, wait for entry, then call `stop()` on another worker. Before releasing recorder start, assert the route, recorder, and task have zero cleanup calls. Release startup and assert the exact event suffix is `cancel-task`, `stop-recorder`, `release-recorder`, `retire-route`, with each event once. Repeat with `release()` and assert the terminal state permanently rejects `reserve()`.

Add a reentrant variant where `startRecorder` itself calls `stop()` on the activation-owner thread. Assert it does not self-deadlock; after `startRecorder` returns, `publishAndStart` reports `Rejected` and performs the same ordered cleanup once.

- [ ] **Step 2: Run capture tests and verify compilation failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAudioCaptureOwnershipTest'
```

Expected: `reserve`, `publishRoute`, and `abort` are unresolved.

- [ ] **Step 3: Move ownership code and implement sealed transitions**

Create the new file with the exact interface and states. Keep cleanup functions injected and make `startTask` return whether the lazy task actually started. `publishRoute` only transfers ownership; when it returns `false`, the caller must retire the lease. `abort(token)` claims only that token's state-owned resources. Admit recorder/task/route together as `Starting.Activating` before calling `startRecorder`; complete the activation barrier on every success/failure/cancellation path. Retirement waits for admitted activation outside the state lock, except the activation owner defers its own reentrant retirement to avoid self-deadlock. Publish `Active` by exact token after activation completes, then start the lazy task; a task canceled by concurrent retirement must not be resurrected.

- [ ] **Step 4: Update engine orchestration to reserve before route acquisition**

Delete `VoiceAudioCaptureLifecycle` and `recorderLock`. Reserve, acquire the route outside the owner lock, publish it, and retire locally on rejection. Replace setup-error `clearLease` calls with token-specific `abort` cleanup while preserving primary/suppressed ordering.

- [ ] **Step 5: Run capture and route-controller tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAudioCaptureOwnershipTest' \
  --tests '*VoiceAudioRouteControllerTest'
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioCaptureOwnership.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioCaptureOwnershipTest.kt
git commit -m "refactor(voice): model capture ownership states"
```

### Task 2: Make Capture Route Preparation Suspending

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioEngine.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteController.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSession.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceSessionResourceCleaner.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/FakeVoiceAudioEngine.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSessionTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioCaptureOwnershipTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteControllerTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt`

**Interfaces:**
- Produces: suspending `VoiceAudioEngine.startCapture` and `VoiceAudioCaptureRouteLease.prepare()` exactly as bound.
- Consumes: Task 1 capture-owner API.

**Invariants:**
- Route lease is published as `Starting.Routed` before `prepare()` suspends.
- Controller acquisition, Bluetooth preparation, recorder configuration, rollback, and retirement execute outside controller and route-lease monitors.
- Fatal initial capture failure still fails connected-resource activation.
- Unmute/debug capture jobs never survive mute, reconnect, failure cleanup, end, or close.
- Telecom preparation is a no-op.

**Acceptance:**
- All implementers and call sites compile with the suspend signatures.
- A fake capture start suspended on a deferred can be canceled by mute, reconnect, end, and close without starting capture afterward.
- A blocked focus acquisition does not block controller close; its late leases roll back instead of publishing.
- A capture-device configuration result arriving after route retirement is retired locally and never appended to the retired lease.
- Initial fake capture failure still produces the existing startup failure path.

- [ ] **Step 1: Add a failing unmute cancellation test**

Extend `FakeVoiceAudioEngine` with a suspending capture gate using `CompletableDeferred<Unit>`. In `VoiceAgentCallSessionTest`, add focused cases that connect a muted session, unmute to enter the gate, and then perform each invalidating action: mute, manual reconnect, end, and `closeNow`. In every case, assert the focused job is canceled before the gate is released, release the gate, and verify no late capture callback becomes installed. For mute, also assert `startCaptureCalls == 1` and `stopCaptureCalls >= 1`; for reconnect/end/close, retain each path's existing cleanup-order assertions.

In `AndroidDirectAudioRouteControllerTest`, add one capability whose focus acquisition blocks after entry. Call `close()` concurrently and prove it completes before releasing focus; after release, assert the acquisition fails as closed and every returned local lease retires once. Add a second test whose capture-device `configure` blocks, retire the route, release configuration, and assert its late lease retires once rather than publishing.

- [ ] **Step 2: Run call-session and audio tests and verify signature failures**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallSessionTest' \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*AndroidDirectAudioRouteControllerTest'
```

Expected: compilation fails until `startCapture` and route `prepare` become suspending.

- [ ] **Step 3: Change the exact interfaces and update every implementation**

Add `prepare()` to all route-lease fakes and production leases. Make engine capture startup suspending, call `routeLease.prepare()` after successful `publishRoute`, and run route opening/preparation and recorder construction on the engine IO dispatcher without holding capture locks. Refactor controller acquisition and route-lease configuration/retirement to the binding two-phase rules; no capability or lease method may execute inside their monitors.

- [ ] **Step 4: Add focused session capture-job ownership and cleanup cancellation**

Await capture directly from initial `activateConnectedResources`. Add one `captureStartJob` for unmute/debug restart and clear it only when the exact job identity completes. Pass `cancelCaptureStart = ::cancelCaptureStartJob` to `VoiceSessionResourceCleaner`; invoke it in `runCleanupSequence` immediately after `prepare()` and before the first external cleanup action. Invoke the same helper before the mute path calls `audio.stopCapture`. Keep `ManagedVoiceCallSession.setMuted` synchronous.

- [ ] **Step 5: Run all touched tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallSessionTest' \
  --tests '*VoiceAgentRuntimeTest' \
  --tests '*VoiceAudioCaptureOwnershipTest' \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*AndroidDirectAudioRouteControllerTest'
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioEngine.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteController.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSession.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceSessionResourceCleaner.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/FakeVoiceAudioEngine.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSessionTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioCaptureOwnershipTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteControllerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt
git commit -m "refactor(voice): suspend capture route preparation"
```

### Task 3: Replace Bluetooth Polling with a Typed Deferred Coordinator

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectBluetoothCaptureAdapter.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt:38-358,394-495`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilitiesTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt`

**Interfaces:**
- Produces: exact generic Bluetooth contracts and `SystemDirectBluetoothCaptureCapability<Headset, Device>`.
- Consumes: suspending route `prepare()` from Task 2.

**Invariants:**
- No thread sleep or blocking profile poll exists.
- No Android handle wrapper or recovery cast exists.
- Callback admission, timeout, late connection, retirement, and rollback follow the binding Bluetooth behavior.
- Recognition and SCO start at most once and retire only when accepted/started ownership was published.

**Acceptance:**
- Coroutine virtual-time tests cover connection before timeout, timeout, late connection, cancellation, and retirement race.
- A heartbeat coroutine on the same single-thread dispatcher runs while `prepare()` waits.
- Existing permission, unavailable-profile, recognition rejection, partial SCO, late callback, factory close, and exact retirement tests remain.

- [ ] **Step 1: Convert Bluetooth fakes to compile-time typed operations**

Use test-only `FakeBluetoothHeadset` and `FakeBluetoothDevice` types directly in `FakeBluetoothCaptureOperations : DirectBluetoothCaptureOperations<FakeBluetoothHeadset, FakeBluetoothDevice>`. Remove fake marker-interface implementations and `awaitHeadset`.

- [ ] **Step 2: Add failing suspending timeout and heartbeat tests**

Add a `runTest` test with `profileWaitMillis = 1_000L`:

```kotlin
@Test
fun `profile wait suspends dispatcher and timeout still requests sco`() = runTest {
    val operations = FakeBluetoothCaptureOperations()
    val capability = SystemDirectBluetoothCaptureCapability(operations, profileWaitMillis = 1_000L)
    val lease = requireNotNull(capability.acquire())
    var heartbeat = false

    val preparing = async { lease.prepare() }
    launch { heartbeat = true }
    runCurrent()
    assertTrue(heartbeat)
    assertFalse(preparing.isCompleted)

    advanceTimeBy(1_000L)
    runCurrent()
    preparing.await()
    assertEquals(1, operations.startScoCalls)
}
```

Add a late-connection test that times out, delivers the typed headset, runs queued callbacks, and asserts recognition starts once while SCO remains once. Add retirement-before-callback and cancellation tests.

- [ ] **Step 3: Run direct capability tests and verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*DirectAudioRouteCapabilitiesTest'
```

Expected: generic contracts and suspending `prepare` behavior are absent, or the old poll blocks the heartbeat.

- [ ] **Step 4: Implement the typed coordinator and Android adapter**

Move Android Bluetooth imports and calls into the new adapter. Implement the generic system coordinator with a deferred and timeout; do not call Android operations while holding its state lock. Use operation reservation plus post-call publish-or-rollback for callback/retirement races.

- [ ] **Step 5: Run direct-audio tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*DirectAudioRouteCapabilitiesTest' \
  --tests '*AndroidDirectAudioRouteControllerTest'
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectBluetoothCaptureAdapter.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilitiesTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt
git commit -m "fix(voice): suspend Bluetooth profile routing"
```

### Task 4: Move Capture Devices Behind the Android Adapter

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectCaptureDeviceAdapter.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilitiesTest.kt`
- Verify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteSelectorTest.kt`

**Interfaces:**
- Produces: `AndroidDirectCaptureDeviceAdapter : DirectCaptureDeviceCapability` with no exposed `AudioDeviceInfo` or handle type.
- Consumes: existing `selectPreferredCaptureRoute` domain selector and `DirectAudioResourceLease`.

**Invariants:**
- Permission denial performs no enumeration or recorder/device mutation.
- Preferred-device and communication-device attempts retain current best-effort behavior.
- Communication device is cleared exactly once only when selection was accepted.
- No platform handle crosses the adapter boundary.

**Acceptance:**
- Controller tests fake `DirectCaptureDeviceCapability` directly.
- Route selection remains covered by `VoiceAudioRouteSelectorTest`.
- Marker types and recovery casts are absent from production and audio tests.

- [ ] **Step 1: Replace capture-device operation fakes with capability-boundary tests**

Keep policy assertions in `AndroidDirectAudioRouteControllerTest` using a fake `DirectCaptureDeviceCapability`. Remove `FakeCaptureDeviceHandle`, `FakeCaptureDeviceOperations`, and tests whose only purpose was passing an opaque handle through production.

- [ ] **Step 2: Run audio tests and verify the old contract is still referenced**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*DirectAudioRouteCapabilitiesTest' \
  --tests '*AndroidDirectAudioRouteControllerTest' \
  --tests '*VoiceAudioRouteSelectorTest'
```

Expected: tests fail to compile after removing old fake handles until the Android adapter owns selection/configuration.

- [ ] **Step 3: Implement the Android capture-device adapter and remove old contracts**

Move Android enumeration, mapping, selection, preferred-device, communication-device, and clear logic into the new file. Keep the pure selector unchanged. Return only a retirement lease when communication-device selection succeeds.

- [ ] **Step 4: Run focused audio tests**

Run the Step 2 command.

Expected: all selected tests pass.

- [ ] **Step 5: Run prohibited-boundary scans**

```bash
if rg -n "Thread\.sleep" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectBluetoothCaptureAdapter.kt; then
  exit 1
fi

if rg -n "VoiceAudioCaptureLifecycle|recorderLock|^internal interface DirectBluetoothHeadset$|^internal interface DirectBluetoothDevice$|\bDirectAudioCaptureDevice\b|\bDirectAudioCaptureDeviceHandle\b|\bDirectCaptureDeviceOperations\b|requireAndroidHeadset|requireBluetoothDevice|requireAudioDeviceInfo" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio; then
  exit 1
fi
```

Expected: both scans exit `0` with no matches. Unrelated playback-drain and debug-injector sleeps are outside this plan; the Bluetooth-owned files contain none.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectCaptureDeviceAdapter.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilitiesTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt
git commit -m "refactor(voice): type direct capture adapters"
```

### Task 5: Verify Capture and Direct-Audio Boundaries

**Files:**
- Verify only: files changed in Tasks 1-4

**Interfaces:**
- Verifies all interfaces and invariants in this plan.

**Invariants:**
- No old nullable capture owner, synchronous start signature, blocking profile wait, route-before-recorder retirement race, or marker boundary remains.
- Telecom capture preparation remains a no-op.

**Acceptance:**
- Focused audio/session tests, full app JVM tests, and lint pass.
- Direct scans prove prohibited constructs are absent.

- [ ] **Step 1: Run focused verification**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallSessionTest' \
  --tests '*VoiceAgentRuntimeTest' \
  --tests '*VoiceAudioCaptureOwnershipTest' \
  --tests '*VoiceAudioRouteControllerTest' \
  --tests '*DirectAudioRouteCapabilitiesTest' \
  --tests '*AndroidDirectAudioRouteControllerTest' \
  --tests '*VoiceAudioRouteSelectorTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run the app JVM suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run Android lint**

```bash
./gradlew :app:lintDebug
```

Expected: `BUILD SUCCESSFUL`. If an environment-only prerequisite prevents lint, record the exact command and error; do not weaken JVM verification.

- [ ] **Step 4: Run direct artifact scans**

Run the prohibited-boundary scan from Task 4, then:

```bash
rg -n "sealed interface CaptureState|data class Reserved|data class Routed|data class Activating|data class Retiring|suspend fun startCapture|suspend fun prepare" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio
```

Expected: sealed-state and suspend-contract matches exist only in their owning focused files.

- [ ] **Step 5: Commit verification-only fixes if required**

If verification required scoped fixes, stage only files in this plan and commit:

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioCaptureOwnership.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioEngine.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteController.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidVoiceAudioEngine.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteController.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilities.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectBluetoothCaptureAdapter.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectCaptureDeviceAdapter.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSession.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceSessionResourceCleaner.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioCaptureOwnershipTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/VoiceAudioRouteControllerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/DirectAudioRouteCapabilitiesTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/FakeVoiceAudioEngine.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSessionTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt
git commit -m "test(voice): verify async capture boundaries"
```

Expected: skip this commit when verification requires no changes.
