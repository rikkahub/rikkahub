# Voice End-Drain Unit Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the end-drain outcome hierarchy and make timed session draining express completion with `Unit` and every failure through the existing aggregated exception path.

**Architecture:** `RouteOwnedVoiceCallSession.endAndDrainWithin` continues to retire the route first, drain within the bound, force-close after drain failure or timeout, and preserve caller cancellation. It now returns normally only when every cleanup stage succeeds; otherwise it throws the primary failure with later failures suppressed. The service lifecycle invokes that contract directly.

**Tech Stack:** Kotlin, kotlinx.coroutines timeout/cancellation, JUnit 4, kotlinx-coroutines-test, Gradle.

## Global Constraints

- Preserve route-retire, delegate-drain, and forced-close ordering.
- The first cleanup failure remains primary; later failures are suppressed in execution order.
- A timeout remains identifiable with `VoiceAgentEndDrainTimeoutException` even when an earlier route failure is primary.
- Caller cancellation remains a `CancellationException`, and cleanup failures are attached without replacing it.
- Add no dependencies and retain no compatibility outcome wrapper.
- Modify only `/home/muly/code/rikkahub`.
- This plan implements review finding 4 and is independently executable before or after the other plans in this design set.

## Binding Plan Contract

### File ownership

- `app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt` owns the timed end-drain contract, timeout type, failure aggregation, and cancellation normalization.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycle.kt` consumes the throwing `Unit` contract without translating an outcome hierarchy.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt` owns exact route/drain/timeout/close/cancellation ordering tests.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycleTest.kt` owns service cleanup reporting for thrown drain failures.

### Exact public-internal contract

```kotlin
interface RouteOwnedManagedVoiceCallSession : ManagedVoiceCallSession {
    val routeMetadata: VoiceAgentRouteMetadata
    val isRouteUsable: Boolean
    suspend fun endAndDrainWithin(timeoutMillis: Long)
}

internal class VoiceAgentEndDrainTimeoutException(
    timeoutMillis: Long,
) : RuntimeException("Voice Agent end drain timed out after ${timeoutMillis}ms")
```

Delete `VoiceAgentEndDrainOutcome` in full. Do not replace it with a boolean, nullable failure, enum, or another result wrapper.

### Completion and failure matrix

| Route retirement | Delegate drain | Forced close | Required result |
|---|---|---|---|
| succeeds | completes | not called | return `Unit` |
| fails | completes | not called | throw route failure |
| succeeds | throws | succeeds/fails | throw drain failure; suppress close failure when present |
| fails | throws | succeeds/fails | throw route failure; suppress drain, then close failure |
| succeeds | times out | succeeds/fails | throw timeout exception; suppress close failure when present |
| fails | times out | succeeds/fails | throw route failure; suppress timeout, then close failure |
| either | caller cancellation | succeeds/fails | force-close once, throw canonical caller cancellation, attach the cleanup aggregate once |

Timeout is not treated as ordinary caller cancellation. `timeoutMillis <= 0` still fails immediately with the current argument error before ownership changes.

### Service lifecycle contract

`drainOwnedSession` is exactly a direct suspend call:

```kotlin
private suspend fun drainOwnedSession(session: RouteOwnedManagedVoiceCallSession) {
    session.endAndDrainWithin(endDrainTimeoutMillis)
}
```

The surrounding `runVoiceAgentSuspendCleanupStages` remains the sole service-level aggregation/reporting boundary.

## Illustrative Implementation Guidance

Keep the current local `failure` accumulator and `withEndDrainFailure` ordering. Replace each outcome construction with either `failure?.let { throw it }` or `throw checkNotNull(failure)`. This is a contract simplification, not a cleanup algorithm rewrite.

---

### Task 1: Convert Route-Owned Timed Drain to a Throwing Unit Contract

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt`

**Interfaces:**
- Produces: exact `suspend fun endAndDrainWithin(timeoutMillis: Long)` contract above.
- Deletes: `VoiceAgentEndDrainOutcome` and every subtype.

**Invariants:**
- Cleanup ordering and exact-owner retirement do not change.
- Normal completion with a route failure throws only after the delegate has drained.
- Drain failure and timeout force-close exactly once.
- Cancellation normalization and cycle termination remain unchanged.

**Acceptance:**
- Every previous outcome assertion becomes a return-or-throw assertion.
- Tests cover every row of the binding failure matrix.
- No `VoiceAgentEndDrainOutcome` reference remains in the two touched files.

- [ ] **Step 1: Rewrite tests against the intended throwing contract**

Change the normal success test to call `owned.endAndDrainWithin(100)` and then assert events and zero closes. Change the completed-route-failure test to capture the thrown route failure. For drain failure and timeout tests, await `runCatching { ... }.exceptionOrNull()` and assert the same primary/suppressed identities and order as today.

For example:

```kotlin
val thrown = runCatching {
    owned.endAndDrainWithin(timeoutMillis = 100)
}.exceptionOrNull()

assertSame(routeFailure, thrown)
assertTrue(thrown?.suppressed?.first() is VoiceAgentEndDrainTimeoutException)
assertSame(closeFailure, thrown?.suppressed?.get(1))
```

Retain the caller-cancellation and cancellation-cycle tests, changing only the removed result-type assumptions.

- [ ] **Step 2: Run the focused test and verify compilation failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentRouteLeaseTest'
```

Expected: compilation fails because production still returns `VoiceAgentEndDrainOutcome`.

- [ ] **Step 3: Delete the hierarchy and return or throw directly**

Change the interface and implementation return type to inferred `Unit`. On normal drain completion, throw the accumulated route failure when present, otherwise return. On a delegate failure or timeout, append the failure, force-close, and throw the aggregate. Preserve the existing canonical cancellation branch verbatim except for any return-type mechanical change.

- [ ] **Step 4: Run the focused test**

Run the Step 2 command.

Expected: `BUILD SUCCESSFUL` and all failure identity/order assertions pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt
git commit -m "refactor(voice): throw timed drain failures directly"
```

### Task 2: Remove Service-Layer Outcome Translation

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycle.kt`
- Verify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycleTest.kt`

**Interfaces:**
- Consumes: Task 1 throwing `Unit` contract.
- Preserves: service cleanup reporting through `host.reportCleanupFailure`.

**Invariants:**
- End-job generation checks and host cleanup stages do not change.
- A thrown timed-drain aggregate is reported once and does not prevent later host cleanup stages.
- No service code distinguishes completion, failure, and timeout with branches.

**Acceptance:**
- `drainOwnedSession` matches the exact binding contract.
- Existing lifecycle tests for drain failure, timeout, and subsequent host cleanup pass without weakened assertions.
- Production and tests have zero `VoiceAgentEndDrainOutcome` references.

- [ ] **Step 1: Replace the outcome `when` with the direct call**

Implement the exact `drainOwnedSession` body from the binding contract. Do not catch at this layer; the surrounding cleanup stage already aggregates the failure.

- [ ] **Step 2: Run lifecycle and route tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallServiceLifecycleTest' \
  --tests '*VoiceAgentRouteLeaseTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the removal scan**

```bash
if rg -n "VoiceAgentEndDrainOutcome" app/src/main app/src/test; then
  exit 1
fi
```

Expected: exit `0` with no matches.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycle.kt
git commit -m "refactor(voice): consume throwing drain contract"
```

### Task 3: Verify End-Drain Semantics

**Files:**
- Verify only: files owned by Tasks 1-2

**Interfaces:**
- Verifies: exact `Unit` API, timeout type, aggregation order, cancellation, and service cleanup behavior.

**Invariants:**
- There is one failure channel: thrown exceptions.
- `VoiceAgentEndDrainTimeoutException` remains present and tested.

**Acceptance:**
- Focused tests, the app JVM suite, lint, and direct scans pass.

- [ ] **Step 1: Run focused verification**

Run the Task 2 test command and the removal scan.

Expected: both succeed.

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

- [ ] **Step 4: Inspect the final API directly**

```bash
rg -n "suspend fun endAndDrainWithin|VoiceAgentEndDrainTimeoutException|private suspend fun drainOwnedSession" \
  app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycle.kt
```

Expected: one interface declaration, one implementation, the retained timeout exception, and the direct service consumer; no outcome hierarchy or translation branch.

- [ ] **Step 5: Commit verification-only fixes if required**

If verification required scoped fixes, stage only the four owned files that changed and commit:

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/RouteOwnedVoiceCallSession.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycle.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRouteLeaseTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallServiceLifecycleTest.kt
git commit -m "test(voice): verify timed drain failures"
```

Expected: skip this commit when verification requires no changes.
