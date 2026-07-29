# Voice Call Orchestrator State Machine Design

**Date:** 2026-07-19  
**Status:** Approved

## Context

The voice concurrency refactor intended `VoiceAgentCallManager` to own one sealed `Idle` / `Starting` / `Active` call slot. Production now also has `CleanupFence` and `CleanupClaim`, and `Active` can temporarily lack a collector or remain unpublished. `runReservationOwner()` separately tracks lease transfer, lease cleanup, created-session cleanup, and active publication with five mutable flags.

Those additions protect real behavior: predecessor cleanup must finish before replacement, canceled startup cleanup must gate retries, publication must be atomic, and failures must not clear newer calls. The problem is that these guarantees are spread across nullable fields, flags, deferred selection, manager state, startup code, and service lifecycle code. Illegal and incomplete ownership combinations are representable again, while the design document no longer describes the production state machine.

This design replaces that split with one typed call orchestrator. It also simplifies the product rules instead of preserving every internal retry and barrier behavior from the current implementation.

## Product Rules

- The newest different call request wins.
- Requests with the same conversation ID and resolved `VoiceAgentLaunchConfig` share the same starting or active call.
- A replacement waits until the old call and any superseded startup resources finish cleanup.
- If cleanup fails, the pending replacement fails and no new call starts.
- A later start request makes one new attempt to finish the exact failed cleanup. It starts only if that retry succeeds.
- An end request supersedes every pending start and waits for owned cleanup.
- A start received while end cleanup is running becomes the newest replacement and waits for that same cleanup.
- Cancellation remains cancellation and cannot publish or clear a newer call.

## Goals

- Make incomplete and conflicting call ownership states unrepresentable.
- Give one component authority over startup, active publication, replacement, stopping, cleanup failure, and retry.
- Keep locks limited to state changes; route, factory, session, collector, and cleanup work remains outside the lock.
- Transfer each route lease, session, scope, collector, and cleanup operation exactly once between typed owners.
- Remove unpublished `Active` values, nullable active resources, cleanup fences, cleanup claims, and manual cleanup flags.
- Keep state transitions independently testable without Android or live coroutines.
- Preserve current route policy, voice behavior, and primary/suppressed failure ordering unless this design explicitly changes orchestration semantics.

## Non-goals

- Changing Telecom-first routing or direct-audio fallback policy.
- Changing direct-audio or capture ownership state machines.
- Changing voice protocol, persistence, playback, mute, reconnect, or notification appearance.
- Refactoring unrelated voice package code.
- Keeping compatibility wrappers for the old manager, startup, or lifecycle ownership APIs.
- Moving all voice work to an actor or command channel.

## Considered Approaches

### Chosen: One Typed Orchestrator

A singleton orchestrator becomes the only call ownership authority. Focused start, cleanup, and state-machine units perform work for it. This removes duplicated lifecycle decisions without forcing simple active-call commands through an asynchronous message queue.

### Rejected: Coroutine Actor

An actor would naturally order commands, but external route, factory, start, drain, and cleanup work would still require typed child operations. It would add channels and asynchronous command delivery to commands that can safely use an active-session snapshot.

### Rejected: Expand the Existing Manager

Adding `ResolvingRoute`, `CreatingSession`, `Publishing`, `Cleaning`, and `Stopping` to the existing manager would reduce nullable state locally. It would leave request generation, route startup, session detachment, and service cleanup authority split across the same classes that drifted apart.

## Architecture

### VoiceAgentCallOrchestrator

`VoiceAgentCallOrchestrator` replaces `VoiceAgentCallManager`, `VoiceAgentCallStartup`, and the call-owning parts of `VoiceAgentCallServiceLifecycle`. It is the only mutable authority for the current call state.

It owns:

- the current typed call state;
- the newest admitted start or end intent;
- route preparation after a request is admitted;
- exact startup and cleanup operations;
- active session publication;
- the private scope and state collector for an active session;
- terminal start and end results; and
- the public `VoiceAgentUiState` and active conversation projection.

Configuration loading remains outside the orchestrator. `VoiceAgentCallService` resolves an immutable request containing the conversation ID and `VoiceAgentLaunchConfig`; this exact pair is the call identity used for matching. Configuration loading owns no route or session resource.

The public orchestrator API provides start, end, immediate close, state observation, and active-session commands such as mute, interrupt, reconnect, and diagnostics. It does not expose reservations, matching-route probes, detached sessions, cleanup barriers, or publication objects.

### Focused Components

- `VoiceAgentCallStateMachine` is pure transition logic. Given a typed state and event, it returns the next state and required effect without invoking external code.
- `VoiceAgentStartOperation` performs route preparation, route-to-factory transfer, factory creation, and session start as typed phases.
- `VoiceAgentCleanupOperation` owns detached startup or active resources, records completed cleanup stages, serializes cleanup attempts, and permits a later retry after failure.
- `VoiceAgentSessionCreationResult` makes factory ownership explicit: creation returns either a route-owned session, a clean failure, or a dirty failure carrying the exact retryable cleanup owner.
- `ActiveVoiceAgentCall` is one complete value containing call identity, route metadata, route-owned session, private scope, and collector job.
- `RouteOwnedVoiceCallSession` keeps route retirement and delegate cleanup in one retry-aware resource owner.
- `VoiceAgentCallService` remains the Android host for intents, configuration loading, foreground notification work, and service shutdown. It does not decide call ownership or detach sessions.

The orchestrator remains injectable as a singleton because the Compose route observes its state and sends active-call commands independently of the Android service instance. Per-call scopes are children of an application-owned parent scope and are canceled by the exact call cleanup operation, not by replacing the service instance.

## State Model

The orchestrator owns exactly one sealed state:

| State | Owned data | Meaning |
|---|---|---|
| `Idle` | Nothing | No startup, active call, or cleanup owns call resources. |
| `Starting` | One typed start operation and one terminal result | One call identity owns startup rights. |
| `Active` | One complete `ActiveVoiceAgentCall` | One session can receive commands and publish UI state. |
| `Stopping` | One cleanup operation and one typed terminal intent | Exact detached resources are being cleaned for end or replacement. |
| `CleanupFailed` | One failed cleanup operation and its error | No call is active; unsafe replacement remains blocked until an explicit later retry succeeds. |

### Starting Phases

`Starting` has sealed phases rather than nullable resources:

| Phase | Owned data |
|---|---|
| `PreparingRoute` | Call identity, start result, and exact route-preparation operation |
| `CreatingSession` | Call identity, start result, and a factory operation that owns the transferred route lease |
| `StartingSession` | Call identity, start result, and exact route-owned session |

Each phase transition transfers its resource to the next phase. A stale completion cannot act unless the exact phase object still owns the state.

There is no `Publishing` phase and no unpublished `Active`. After the session starts, the operation creates a private call scope and lazy collector. Under one state transition, the orchestrator installs the complete `ActiveVoiceAgentCall` and completes the shared start result. It starts the lazy collector only after releasing the lock. If an end or replacement cancels the lazy collector first, starting it is harmless.

### Stopping Variants

`Stopping` has two non-nullable variants:

- `ForEnd` owns the cleanup operation and ends at `Idle`.
- `ForReplacement` owns the same cleanup operation and the newest complete pending request.

A newer different start replaces only the pending request and completes the displaced request as superseded. A matching start shares the pending request's terminal result. An end removes the pending replacement and preserves the exact cleanup owner as `ForEnd`. A start during `ForEnd` changes the terminal intent to `ForReplacement` without creating a second cleanup owner.

### CleanupFailed

`CleanupFailed` owns the exact cleanup operation that failed. It does not pretend the resources are idle and does not replay a permanently completed barrier.

A later start installs that request as the replacement intent and asks the cleanup operation for one new attempt. Success advances to `Starting.PreparingRoute`. Failure completes that request as failed and returns to `CleanupFailed` with the same cleanup owner and latest error. End may also retry or report the exact failure, but it never clears the state without successful cleanup.

## Public Results

`start(request)` performs matching and returns one sealed terminal result:

- `Active(route)` when the requested identity is active, whether this caller initiated it or shared it;
- `Superseded` when a newer different intent wins; or
- `Failed(error)` after owned cleanup completes or reaches `CleanupFailed`.

Call orchestration no longer exposes `Started` versus `Existing`. Reconnect decisions and startup-state handling move into the orchestrator, so the Android service does not need that distinction. `matchingRoute()` and its separate result hierarchy are removed.

Coroutine cancellation is thrown, not converted to a result. End returns only after the owned cleanup attempt reaches `Idle` or `CleanupFailed`.

Immediate close performs the same atomic state detachment but schedules immediate cleanup in the orchestrator's application-owned scope. The synchronous `closeNow()` call returns after ownership is safely transferred to that cleanup operation; it does not wait for a blocked factory or session callback. Cleanup completion or failure still drives `Idle` or `CleanupFailed`, and the Android host reports any eventual failure.

## Data Flow

### Start From Idle

1. The service resolves an immutable call request and submits it only while its Android start intent remains current.
2. Under the orchestrator lock, `Idle` becomes `Starting.PreparingRoute` with a unique identity object and shared terminal result.
3. Outside the lock, route preparation returns an owned route lease.
4. The exact current phase transfers the lease to a `CreatingSession` factory operation. A stale route result retires its own lease and reports only after that cleanup finishes.
5. The factory consumes the lease at entry. It returns a typed creation result: a complete route-owned session, a clean failure after successful retirement, or a dirty failure carrying the exact retryable cleanup owner.
6. The exact current phase installs `StartingSession`; a stale created session closes itself outside the lock.
7. The session starts outside the lock.
8. The operation creates the per-call scope and lazy collector.
9. Under one lock transition, the exact `StartingSession` becomes complete `Active` and the shared result becomes `Active(route)`.
10. Outside the lock, the collector starts and may publish session state only while its exact `ActiveVoiceAgentCall` remains current.

### Matching Start

An admitted request matching `Starting`, `Active`, or the pending request in `Stopping.ForReplacement` does not resolve another route. It waits for or returns the shared terminal result. Matching means both conversation ID and resolved launch configuration are equal.

### Different Start

- From `Active`, the orchestrator detaches the complete active bundle into one `VoiceAgentCleanupOperation` and enters `Stopping.ForReplacement`.
- From `Starting`, it cancels the exact startup operation and enters `Stopping.ForReplacement` with the startup cleanup completion as the cleanup owner.
- From `Stopping`, it supersedes only the previous pending request and retains the cleanup operation.
- From `CleanupFailed`, it requests one new cleanup attempt and waits before starting.

No route preparation for the replacement begins until cleanup succeeds. Intermediate requests can be superseded without creating intermediate sessions or cleanup owners.

### End

1. The service keeps or starts an ending foreground notification.
2. Under the lock, end supersedes every pending replacement.
3. `Starting` or `Active` detaches into `Stopping.ForEnd`; an existing `Stopping` keeps its cleanup owner and changes only its terminal intent.
4. Outside the lock, startup cleanup, graceful session drain, or immediate close runs as requested.
5. Success publishes `Idle`; failure publishes `CleanupFailed`.
6. The service stops its notification and itself only after the orchestrator reports the terminal state for the current Android intent.

An old service completion may not stop a service generation that has since submitted a newer start.

### Active Commands and State Collection

Mute, interrupt, reconnect, and diagnostics snapshot the complete `ActiveVoiceAgentCall` under the lock and invoke the session outside it. `Starting`, `Stopping`, and `CleanupFailed` do not expose a command target.

Collector updates carry the exact active identity. They update `VoiceAgentUiState` only while that identity remains current. Cleanup cancels and joins the collector before completing the call scope.

## Resource and Cleanup Contracts

### Factory Transfer

Before factory entry, `Starting.PreparingRoute` owns the route lease. Factory entry is the ownership-transfer boundary. From that point, the factory returns one of three non-nullable results:

- `Created(session)` transfers one complete route-owned session to the start operation.
- `FailedClean(error)` reports creation failure after route retirement succeeded; no resource owner returns.
- `FailedDirty(error, cleanup)` reports creation failure plus the exact retryable cleanup owner for the route that did not retire cleanly.

`FailedDirty` moves the orchestrator to `CleanupFailed`; it is not thrown past the ownership boundary. The orchestrator never keeps a second lease owner and does not track a `factoryOwnsLease` flag.

### Retry-aware Route Retirement

Route retirement permits at most one in-flight attempt. Concurrent callers join that attempt. Success is permanent and later retirement calls are no-ops. Failure is reported to every joiner but leaves retirement eligible for a later explicit attempt.

The underlying Telecom retirement operation must be idempotent across attempts. Direct fallback retirement remains a successful no-op. A failed attempt may not expose a route as active or transferable while its cleanup state is unresolved.

### Retry-aware Session Cleanup

`RouteOwnedVoiceCallSession` exposes one cleanup owner rather than independent callers repeating route and delegate cleanup. The owner records route, delegate, collector, and scope stages that completed successfully.

The first normal end uses graceful drain with the existing timeout and forced-close fallback. Replacement cleanup uses the existing replacement-end behavior. After a reported failure, a later explicit retry uses immediate close and invokes only unfinished stages. Session and route close operations must therefore be idempotent when a failed stage is attempted again.

Completed stages never run again. Each attempt preserves the existing primary error and adds later stage errors as suppressed exceptions in cleanup order.

## Failure Handling

- Configuration failure is handled by the service before request admission; it cannot alter orchestrator ownership.
- Route preparation failure completes the current start as failed and returns to `Idle` when no route resource escaped.
- `FailedClean` factory creation completes the current start as failed and returns to `Idle`. `FailedDirty` fails the start and enters `CleanupFailed` with the returned cleanup owner.
- Session-start failure enters cleanup for the exact created session. It reports the start error only after cleanup finishes; cleanup errors are suppressed onto it.
- Predecessor cleanup failure fails the pending replacement and enters `CleanupFailed`.
- A stale operation result cleans only its own returned resource and cannot change the winning state.
- Cancellation keeps the canonical cancellation exception. Distinct cleanup failures are suppressed onto it after cleanup; self-suppression is ignored.
- No terminal result is completed before the exact owner either transfers or cleans every resource it held.
- No external lifecycle method, deferred wait, collector launch, cancellation join, or cleanup attempt runs while holding the orchestrator lock.

## Service Responsibilities

`VoiceAgentCallService` retains only Android lifecycle work:

- mark each incoming Android intent as current;
- cancel stale configuration-loading work;
- resolve settings and conversation data into an immutable call request;
- submit start or end to the orchestrator;
- collect orchestrator UI state for the foreground notification;
- keep the service foregrounded during startup and end cleanup; and
- stop the foreground service only when the matching current intent finishes.

`VoiceAgentCallServiceLifecycle` may remain as a small Android host helper, but it cannot own or detach sessions, maintain a parallel call generation, or perform call cleanup. Any remaining generation protects only Android notification and `stopSelf()` ordering.

## Testing Strategy

### Pure State-machine Tests

Cover every command and operation completion in every state:

- matching and different start;
- end and immediate close;
- successful, failed, canceled, and stale operation completion;
- pending-request replacement;
- cleanup success, cleanup failure, and explicit later retry; and
- active collector updates from current and stale identities.

Every transition asserts that at most one startup, active call, or cleanup operation owns call resources.

### Startup Ownership Tests

Block route resolution, factory creation, session start, active publication, and collector launch independently. At each boundary, prove that:

- a matching request shares the exact result;
- a newer different request wins;
- an end request wins over pending startup;
- the stale phase cleans its exact resource once per successful cleanup stage;
- replacement work does not begin before cleanup; and
- stale completion cannot publish, clear, or close the newer call.

### Cleanup Retry Tests

- Concurrent callers join one cleanup attempt.
- Failure remains primary and later failures remain suppressed in stage order.
- `CleanupFailed` exposes no active session.
- A later start retries only unfinished stages.
- Successful stages are never repeated.
- A successful retry admits the pending call.
- A failed retry fails that request and preserves `CleanupFailed`.
- Cancellation retains its exact cancellation identity while cleanup finishes.

### Service and Integration Tests

- Configuration loading races submit only the current immutable request.
- Foreground notification lifetime covers startup and end cleanup.
- End during startup, start during end cleanup, repeated end, and service destruction preserve the newest Android intent.
- Compose state observation and active commands use the orchestrator.
- Route metadata, degraded Telecom diagnostics, reconnect behavior, and startup terminal handling remain correct.
- Deterministic stress tests run many start, end, cancellation, and completion orderings and check state and resource invariants after every event.

Tests that name or require `CleanupFence`, `CleanupClaim`, pending publication inside `Active`, permanently replayed cleanup barriers, or the five local owner flags are removed. User-visible routing and call behavior tests remain.

## Migration

The migration is a direct internal cutover:

1. Introduce the pure state types and transition tests.
2. Introduce retry-aware route and session cleanup owners with focused tests.
3. Add start and cleanup operations.
4. Add the orchestrator and move active UI state and commands to it.
5. Move service start/end integration to the orchestrator.
6. Update dependency injection and Compose consumers.
7. Delete the old manager, startup wrapper, call-owning lifecycle code, obsolete results, fences, claims, pending publication state, and flag-based cleanup.

Production and tests move to the new API together. No compatibility adapter preserves the old ownership model.

## Success Criteria

- The production state machine matches this document.
- Every state contains all and only the resources legal for that state.
- `Active` always contains a session, scope, and collector and is immediately publishable.
- One typed cleanup operation replaces cleanup fences, claims, and manual ownership flags.
- Matching requests share one result; only the newest different request may start.
- New startup cannot overlap unresolved prior cleanup.
- Cleanup failure blocks startup, and a later request can retry only unfinished cleanup.
- No external work occurs under the orchestrator lock.
- Focused unit, integration, and deterministic stress tests pass.
