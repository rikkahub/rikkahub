# Voice Route Lease Ownership Refactor Design

**Date:** 2026-07-16

**Status:** Approved in conversation; awaiting written-spec review

## Context

The current Voice Agent implementation correctly chooses one immutable audio-route owner for each managed session: Android Telecom when its self-managed call becomes active, or direct Android audio routing when Telecom is unavailable. Runtime tests pass, but the implementation represents that choice mostly as an enum while the resources behind the choice remain elsewhere.

That split creates three maintainability problems:

1. Startup passes only `VoiceAudioRouteOwner` into the managed session. The exact Telecom attempt and connection remain in a global registry, so service cleanup must infer ownership from unrelated snapshots.
2. `VoiceAgentTelecomCallRegistry` stores active state in both an attempt phase and a separate `activeConnection`, removes acknowledged active attempt records, and reconstructs records during retirement.
3. `AndroidDirectAudioRouteController` exposes paired Android protocol operations through a large platform interface and retains many flags to remember which resources need reversal.

The refactor will replace inferred cleanup with explicit, typed ownership leases. It will preserve the current user-visible behavior, timing, diagnostics, fallback policy, and Android routing behavior.

## Goals

- Make the managed voice session own the exact route resource selected for it.
- Make every ownership transfer explicit and single-use.
- Preserve immutable Telecom-versus-direct ownership across reconnects.
- Retire the exact Telecom attempt rather than whichever connection is globally active.
- Represent every active Telecom connection in one attempt record.
- Replace paired direct-audio setup and teardown calls with capture-scoped leases.
- Make retirement deterministic, idempotent, concurrency-safe, and error-preserving.
- Remove tests that inspect private lifecycle state with reflection.
- Delete obsolete enum-only ownership and global cleanup paths in the same change.

## Non-goals

- Changing Telecom-first route selection or the three-second activation timeout.
- Changing when direct fallback is selected.
- Switching route owner during a managed session or reconnect.
- Changing microphone, playback, Gemini, Hermes, notification, or observability behavior.
- Holding direct focus, SCO, or communication-device resources for the whole session.
- Migrating to Core-Telecom or changing the self-managed Telecom integration.
- Redesigning unrelated Voice Agent lifecycle or playback coordination code.
- Retaining compatibility adapters for the APIs replaced by this refactor.

## Design principles

### Ownership follows the resource

Code that must retire a resource must receive and retain the exact object that owns it. An enum can describe a route, but it cannot own or retire a connection.

### Two lease lifetimes

The design uses two different lifetimes because the underlying resources have different lifetimes:

- A **session route lease** lasts for the managed voice session and reconnects. For Telecom, it owns the exact active attempt and connection. For direct fallback, it records the immutable decision and fallback diagnostic.
- A **capture route lease** lasts for one microphone capture. For direct fallback, it owns focus, communication mode, Bluetooth routing, and device-selection acquisitions. For Telecom, it is a no-op because Telecom owns Android routing.

The refactor must not stretch a capture resource to session lifetime merely to use one lease type.

### One owner at every boundary

Before a managed session is created, startup owns a newly resolved route lease. After a successful transfer, the managed session owns it. No lease is copied, shared as an independent owner, or left ownerless.

## Session route lease

Introduce a sealed `VoiceAgentRouteLease` with immutable route metadata and deterministic retirement.

Conceptually it has two implementations:

- `TelecomRouteLease` contains the exact `VoiceAgentTelecomAttemptId` and the registry capability needed to retire that attempt.
- `DirectFallbackRouteLease` contains the fallback failure diagnostic and has no Telecom resource to release.

Both expose:

- `owner`, used to select the audio engine strategy and populate existing diagnostics;
- the existing optional Telecom failure diagnostic;
- one idempotent retirement operation.

The route owner enum may remain as diagnostic metadata and as the audio-engine strategy input. It must no longer be used as a substitute for the resource lease.

## Startup and ownership transfer

`VoiceAgentCallStartup` follows this sequence:

1. Ask `VoiceAgentCallManager` whether the same conversation and launch configuration already have a live session.
2. If so, return a reused-session result containing immutable route metadata. Do not resolve or create another lease.
3. Otherwise, ask `VoiceAgentAudioRouteResolver` to acquire a new route lease.
4. Recheck the call generation or equivalent current-start predicate.
5. If stale, retire the new lease and return a stale result.
6. Ask the manager to install a new managed session using the lease.
7. If installation succeeds, ownership transfers into the managed session exactly once.
8. If installation is rejected because another matching session won a race, or if factory creation fails, startup retains responsibility and retires the unused lease.

The manager must perform its matching check again when installing the session. The early reuse check avoids unnecessary Telecom calls; the second check closes the race between resolution and installation.

Starting a different session preserves the current order: detach and end the previous managed session before installing and starting the replacement. If previous-session cleanup prevents installation, the new untransferred lease is retired.

## Route-owned managed session

Wrap the core `ManagedVoiceCallSession` in a focused route-owning session. The wrapper owns the session route lease and delegates normal voice operations.

Its terminal operations preserve current ordering:

1. retire the session route lease;
2. end, end-and-drain, or immediately close the core voice session;
3. return or propagate the aggregated cleanup result.

Every terminal method attempts both route retirement and core-session cleanup. A failure in route retirement must not skip audio, socket, coordinator, persistence, or observability cleanup.

Reconnect delegates to the same core session and does not acquire a new route lease. This preserves the original route owner and exact Telecom connection for the entire managed-session lifetime.

`VoiceAgentCallManager` stores one aggregate active-call record containing the managed session, conversation ID, launch configuration, and immutable route metadata. It does not maintain a separate mutable route-owner flow as an independent lifecycle source of truth.

## Service simplification

`VoiceAgentCallService` no longer injects `VoiceAgentTelecomCallRegistry` and no longer calls `disconnectActive()` or `hasActiveConnection()`.

Normal end becomes:

1. detach the route-owned managed session;
2. invoke its `endAndDrain()`;
3. complete notification and service cleanup.

Failed startup closes or preserves the actual managed session according to whether that session exists and is reusable. It does not derive a cleanup plan from a route-owner enum plus a global connection snapshot.

Ending while startup is still resolving increments the existing generation. The resolver or startup code detects the stale result and retires the lease it still owns. A service end with no installed session does not guess at global Telecom state.

The obsolete `VoiceAgentFailedStartCleanupPlan`, the seven-lambda end-cleanup testing seam, and the dead `observedReconnectAttempt` branch are deleted as part of the atomic migration.

## Telecom registry state model

Each Telecom attempt remains represented by one `AttemptRecord` until its live resource is retired or its resource-free failure is acknowledged.

The phase model is conceptually:

- `Pending`
- `Activating(connection)`
- `Active(connection)`
- `Retiring(connection, failure)`
- `Failed(failure)`

The activation outcome belongs to the record. Acknowledging an `Active` outcome marks or consumes only the outcome notification; it does not remove the record containing the live connection. Acknowledging a resource-free `Failed` outcome may remove that terminal record.

There is no separate `activeConnection`. The current attempt ID may remain as the single-current-call policy pointer, but the attempt record is the only source of connection identity and phase.

### Exact-attempt retirement

Retirement always names an attempt ID:

1. Under the registry lock, transition its record to a retiring or resource-free failed phase.
2. Perform Android connection disconnection outside the lock.
3. Accept connection retirement callbacks idempotently.
4. Publish any required terminal outcome.
5. Remove the record only after the connection has crossed its retirement boundary.

Pending or activating attempts retired by resolver failure or cancellation still provide an awaitable terminal outcome. An active session lease retires only its own attempt; there is no global `disconnectActive()` fallback.

Unknown, superseded, or late connections are rejected and disconnected exactly once. A late callback can never acquire ownership for a direct-fallback session.

## Retirement barrier

Generalize the existing single-flight retirement behavior into a small internal `RetirementBarrier` used by route, connection, and capture leases where appropriate.

It has these semantics:

- states progress once from open to retiring to retired;
- the first caller performs cleanup;
- concurrent callers wait for the first cleanup;
- all non-reentrant callers observe the same stored success or failure;
- a reentrant call from the retirement owner returns without waiting on itself;
- cleanup is never executed twice.

Cleanup stages always continue after an individual failure. The first failure remains primary and subsequent failures are attached in execution order as suppressed exceptions.

The barrier preserves a lease's result; it does not decide whether that result is fatal to its caller. Each owning boundary applies the existing policy after all retirement stages finish. Session-route retirement errors remain visible to the service cleanup aggregator. Direct-routing operations that are currently best effort log their retirement failures and do not turn capture stop into a new fatal error.

## Capture route lease

Replace `VoiceAudioRouteController.beforeCapture()`, `configureRecorder()`, and `afterCapture()` with a single acquisition boundary:

1. `routeController.acquireCapture()` returns a `VoiceAudioCaptureRouteLease`.
2. After `AudioRecord` creation, the engine calls `captureLease.configureRecorder(recorder)`.
3. Every capture termination or setup-failure path retires that same capture lease.

The Telecom controller returns a no-op capture lease. It continues to perform no direct focus, mode, communication-device, SCO, Bluetooth voice-recognition, or preferred-device mutations.

The direct controller returns a lease that owns only the acquisitions made for that capture. It preserves the current operation order:

1. request audio focus;
2. enter communication mode;
3. request Bluetooth headset voice recognition and SCO routing;
4. create the recorder in the audio engine;
5. select the preferred recorder and communication device.

Retirement releases successful acquisitions in reverse order. A later setup failure immediately rolls back earlier acquisitions.

## Direct Android platform boundary

`AndroidDirectAudioRouteController` becomes a small capture-lease factory rather than the owner of a long-lived ledger of Android flags.

The Android adapter moves to a separate file and exposes capability-level acquisition operations. Each successful acquisition returns a typed, idempotent lease or a typed result containing that lease. The adapter encapsulates paired Android operations such as:

- request and abandon audio focus;
- enter and restore communication mode;
- select and clear a communication device;
- start and stop SCO;
- open, use, and close the Bluetooth headset profile;
- start and stop headset voice recognition.

Bluetooth profile callback state belongs to the Bluetooth acquisition. A callback arriving after capture retirement closes or rejects its proxy without mutating the retired capture. The controller does not retain proxy-requested, wants-recognition, selected-device, SCO-started, or similar cross-capture flags.

Focused capability interfaces replace the current broad platform interface. Tests can fake only the capability involved in a scenario instead of implementing the entire Android protocol surface.

## Best-effort and fatal behavior

This refactor does not change the current routing policy.

These conditions remain best effort and do not abort capture:

- nonfatal delayed or denied audio focus;
- missing Bluetooth permission;
- unavailable Bluetooth headset profile;
- no connected headset;
- rejected headset voice-recognition activation;
- optional communication-device, SCO, preferred-device, or route cleanup failures.

They continue to emit the existing safe diagnostics.

Existing fatal focus-policy failures, microphone permission failures, recorder creation failures, uninitialized recorders, and recorder start failures remain fatal at the same boundary. Partial route resources are retired before the failure is propagated.

Direct capture retirement still attempts and records every release. Failures from operations classified above as best effort are logged with the existing safe diagnostics and are consumed at the direct-capture boundary after cleanup completes. This preserves deterministic cleanup and error evidence without changing runtime failure behavior.

## Testing strategy

### Retirement contract tests

- cleanup executes once;
- concurrent callers wait;
- reentrant retirement does not deadlock;
- later callers receive the stored result;
- staged cleanup continues after failure;
- suppressed failures retain execution order.

### Telecom registry tests

- pending, activating, active, retiring, failed, superseded, and late-callback paths;
- exact-attempt retirement;
- active outcome acknowledgement retains the live record;
- failed outcome acknowledgement removes the resource-free record;
- activation and retirement races;
- duplicate connection retirement;
- no private-field reflection.

Tests observe public outcomes, barriers, and fake connection events. If a transition needs deterministic observation, provide a focused package-internal testing boundary rather than reflectively reading fields.

### Startup and session tests

- matching-session reuse performs no route resolution;
- reconnect retains the original lease;
- stale startup retires its untransferred lease;
- factory failure retires its untransferred lease;
- manager race rejection retires the unused lease;
- successful startup transfers ownership once;
- replacement ends the previous owned session before starting the new one;
- `end`, `endAndDrain`, and `closeNow` retire route before core session cleanup;
- every cleanup stage runs and errors aggregate correctly.

### Direct capture tests

- successful acquisition and reverse retirement order;
- partial acquisition rollback;
- current best-effort failures remain nonfatal;
- fatal policy failures still propagate;
- recorder preferred-device and communication-device configuration;
- late Bluetooth callbacks after retirement;
- close during acquisition;
- repeated capture cycles acquire independent leases;
- Telecom capture uses the no-op lease and performs zero direct mutations.

### Integration and device verification

- run the full debug unit-test suite;
- run the Voice Agent Sentry build harness unchanged;
- build the debug universal APK;
- install it on the configured wireless ADB phone;
- run the existing Voice Agent/Hermes E2E runbook;
- verify traces show unchanged Telecom-first selection, fallback diagnostics, reconnect ownership, Bluetooth behavior, capture, playback, and end ordering;
- confirm no voice interruption or new routing regression is introduced.

## Migration and deletion requirements

The implementation is one atomic internal migration. It must update all production callers and tests before deleting the old APIs. It must not leave temporary adapters or dual ownership paths.

Delete or replace:

- enum-only `VoiceAgentAudioRouteResolution` ownership transfer;
- `telecomAttemptId` cleanup plumbing outside the lease;
- manager `_activeRouteOwner` lifecycle state;
- service registry injection and global active-connection cleanup;
- `VoiceAgentFailedStartCleanupPlan`;
- the seven-lambda end-cleanup helper;
- the dead reconnect-observation branch;
- registry `activeConnection` and active-record reconstruction;
- direct controller `beforeCapture`/`afterCapture` pairing;
- the broad direct-audio platform fake and reflection-based lifecycle assertions.

The route-owner enum may remain only as immutable metadata and audio-strategy selection. It must be derived from the owned session or lease, never used to infer which resource to retire.

## Acceptance criteria

- Every installed session owns exactly one session route lease.
- Every direct microphone capture owns exactly one capture route lease.
- The same managed session and reconnect never switch route owner.
- No code outside the exact owning lease disconnects a Telecom call.
- The Telecom registry contains one source of truth for attempt phase and connection identity.
- Direct capture cleanup needs no controller-level ledger of paired-operation booleans.
- All retirement paths are idempotent, deterministic, and error-preserving.
- Existing route selection, timeout, diagnostics, notification, capture, playback, reconnect, and Bluetooth behavior remain unchanged.
- Existing and new unit, build-harness, APK, and on-device E2E verification pass.
- Repository search finds no obsolete compatibility path described in the migration section.
