# Hermes Announcement Architecture Cleanup Design

**Date:** 2026-07-15
**Status:** Approved design

## Problem

The safe-playback implementation correctly prevents Hermes progress and result announcements from interrupting Gemini speech, but its implementation leaves four structural problems:

- interrupted-turn state is split between `VoiceAgentCoordinator` and the announcer reducer;
- playback callbacks execute while `VoicePlaybackWriter` holds its state lock;
- whether progress may read a terminal durable record is stored as a mutable property of the progress intent instead of being derived from its queued relationship to a final intent; and
- writer invalidation generations and physical playback epochs are both exposed as untyped `Long` values called “generation.”

These problems make the coordination rules harder to reason about and easier to break even though the current tests and device trace pass.

## Goals

- Keep every rule that decides whether a proactive Hermes turn may begin in the announcer reducer.
- Preserve the existing conservative interruption behavior: the boundary belonging to an interrupted response does not open the announcement gate.
- Publish playback events outside writer locks without weakening their FIFO ordering.
- Make writer invalidation and physical playback identity distinct in names and types.
- Derive progress-before-final behavior from queue structure rather than an intent flag.
- Preserve all current announcement ordering, cancellation, bridge, fallback, drain-safety, and watchdog behavior.

## Non-goals

- Changing the user-visible order of latest progress followed by final result.
- Replacing the playback writer or announcer with a general-purpose speech scheduler.
- Moving all playback operations into a new actor.
- Changing Gemini, Hermes, bridge, durable record, or text-fallback protocols.
- Redesigning microphone capture, playback sinks, or user barge-in.

## Selected Approach

Use typed state-machine consolidation. Extend the existing announcer reducer to own interruption, introduce explicit playback identity types, derive send eligibility from the grouped pending job, and add a narrow FIFO dispatcher for post-lock playback events.

This approach uses the architecture already established by the safe-playback work. It removes coordinator branches and intent flags rather than moving them into another orchestration layer.

## Ownership

### `HermesAnnouncer` and `AnnouncerReducer`

The announcer remains the sole owner of proactive announcement safety. Its reducer owns:

- Gemini turn lifecycle, including interruption;
- physical playback epoch and drain state;
- pending progress/final ordering;
- bridge eligibility;
- the input quiet window; and
- the diagnostic-only blocked watchdog.

The reducer continues to perform no I/O. The announcer shell executes its typed effects, accesses durable records, and admits sends through the current bridge lease protocol.

### `VoiceAgentCoordinator`

The coordinator validates whether Gemini events belong to the active session, performs transcript/UI/audio side effects, and translates accepted events into announcer events. It does not store or interpret announcement-gate state.

The coordinator will have one lock-required ownership predicate for scoped and unscoped Gemini events. Activity, interruption, TurnComplete, and session retirement use that predicate so their ordering remains linearized under `toolJobsLock`.

### Audio layer

`VoicePlaybackWriter` continues to own queued writes, sink creation and retirement, writer invalidation, and playback drain detection. It commits playback events in the same order as its state mutations but never invokes external handlers while holding its state lock.

## Gemini Turn State

`GeminiTurnGate` gains `InterruptedAwaitingBoundary` alongside `Idle`, `SendReserved`, and `Active`.

The reducer applies these transitions:

| Current state | Event | Next state | Meaning |
|---|---|---|---|
| `Idle` | `GeminiTurnInterrupted` | `InterruptedAwaitingBoundary` | An interruption is conservatively treated as an open boundary even if earlier activity was not observed. |
| `Active` | `GeminiTurnInterrupted` | `InterruptedAwaitingBoundary` | The interrupted response remains ineligible to release an announcement. |
| `SendReserved` | `GeminiTurnInterrupted` | `InterruptedAwaitingBoundary` | Interruption wins the race; the later send result may clear `inFlight` but cannot reopen the gate. |
| `InterruptedAwaitingBoundary` | activity or another interruption | `InterruptedAwaitingBoundary` | New evidence cannot make the boundary safer. |
| `InterruptedAwaitingBoundary` | `GeminiTurnComplete` | `Active` | Consume the completion associated with the interrupted response while retaining a closed gate. |
| `Active` | `GeminiTurnComplete` | `Idle` | A subsequent genuine completion opens the Gemini portion of the gate. |
| any open state | `GeminiSessionRetired` | `Idle` | The retired session no longer owns a turn. |

Playback drain, quiet timers, watchdog events, and send results never change `InterruptedAwaitingBoundary`.

`VoiceAgentCoordinator` prepares the interruption event before acquiring `toolJobsLock`, validates ownership inside the lock, and commits the callback-free channel post inside the same lock. It then performs the existing playback suppression and transcript/UI interruption work outside the ownership lock.

This replaces `interruptedResponseSessionId`, its session resets, and the special TurnComplete branch.

## Pending Announcement Model

`AnnouncementIntent.StillWorking` contains only `callId` and `jobId`. It no longer contains `allowTerminalRecord`.

`PendingAnnouncementJob` remains the grouped source of truth for a job’s queued progress and final intent. Its next-send selection derives a typed `AnnouncementDispatch`:

- `ProgressOnly` for progress with no paired final;
- `ProgressBeforeFinal` for progress whose grouped job also contains a final intent; and
- `Final` for a completion or terminal result.

Reducer `inFlight` state and `AnnouncerEffect.Send` carry the dispatch rather than a bare intent. Durable-record admission follows the dispatch type:

- `ProgressOnly` is obsolete when the durable record is terminal;
- `ProgressBeforeFinal` may read the terminal record because the paired final remains structurally queued behind it; and
- `Final` retains the existing completion/terminal validation.

After a successful progress send, only that progress dispatch is removed. The grouped final remains queued for the next safe boundary. Cancellation with no final cannot create `ProgressBeforeFinal`, so stale unpaired progress remains suppressed.

## Playback Identity Types

Introduce two internal value types:

- `WriterGeneration` identifies a writer lifetime and invalidates queued writes after session change, suppression, sink failure, or release.
- `PlaybackEpoch` identifies one physical playback-active/drain cycle and is the only playback identity exposed to the coordinator and announcer.

`PlaybackCommand`, retirement bookkeeping, and writer diagnostics use `WriterGeneration`. `VoicePlaybackEvent`, coordinator routing, announcer events, `PlaybackGate`, and playback-gate diagnostics use `PlaybackEpoch`.

Names in diagnostic messages use `writerGeneration` or `playbackEpoch`; the generic playback `generation` label is removed. Late epochs remain comparable by their underlying monotonic value, but the two identity types cannot be passed to one another accidentally.

## Ordered Post-lock Event Publication

A narrow `PlaybackEventDispatcher` separates event commitment from handler execution.

1. While holding the writer state lock, the writer mutates its state and appends zero or more `VoicePlaybackEvent` values to the dispatcher’s FIFO queue. Enqueueing never invokes external code.
2. After releasing the writer state lock, the caller asks the dispatcher to drain.
3. A short dispatcher lock elects one drainer and protects only the FIFO queue. The drainer removes an event, releases the dispatcher lock, and then invokes the handler.
4. Concurrent or reentrant producers append behind already committed events. A reentrant drain request observes that a drainer is active and returns; the active drainer consumes the newly appended event in order.
5. Handler exceptions are contained, reported through a playback diagnostic, and do not terminate the playback worker or leave writer state partially committed.

This preserves the linear event sequence `Active → DrainStarted → Drained` without executing callbacks under either the writer lock or dispatcher queue lock.

Release and retirement enqueue terminal `Drained` events as part of the same committed mutation that clears their epoch bookkeeping. The engine keeps the playback handler installed until the synchronous dispatcher drain initiated by release has completed, preserving the existing release-before-handler-clear guarantee.

## Failure and Race Handling

- Drain failure emits `Drained` only after the sink has been successfully flushed or retired.
- Failed sink retirement emits no terminal playback event and leaves the announcement gate blocked.
- A stale `PlaybackEpoch` cannot update the current playback gate.
- A stale `WriterGeneration` cannot write to or retire the current sink.
- Interruption never acts as TurnComplete and never opens the announcement gate.
- Session retirement clears interrupted turn ownership and immediately revokes proactive bridge admission through the existing prepare/commit protocol.
- The watchdog remains observability-only and cannot override any gate.
- A playback handler failure cannot kill the writer worker; subsequent committed events remain drainable.
- Existing bridge invalidation, timeout, retry, visible-text fallback, and close-drain behavior remain unchanged.

## Expected Production Changes

### `HermesAnnouncementLifecycle.kt`

- Add `GeminiTurnGate.InterruptedAwaitingBoundary` and `AnnouncerEvent.GeminiTurnInterrupted`.
- Add typed `AnnouncementDispatch` and make reducer `inFlight` and send effects use it.
- Remove `allowTerminalRecord` from `StillWorking`.
- Replace playback `generation` fields with `PlaybackEpoch`.

### `HermesAnnouncer.kt`

- Add prepare/commit methods for interruption.
- Execute typed announcement dispatches while retaining existing durable-record and bridge-admission rules.
- Use explicit playback epoch terminology.

### `VoiceAgentCoordinator.kt`

- Remove `interruptedResponseSessionId` and its lifecycle resets.
- Route accepted interruption into the announcer reducer.
- Collapse repeated scoped/unscoped ownership conditions into one lock-required predicate.
- Route typed playback epochs.

### Audio package

- Introduce `WriterGeneration` and `PlaybackEpoch`.
- Add the ordered post-lock `PlaybackEventDispatcher`.
- Update writer commands, retirement bookkeeping, events, diagnostics, and engine logs to use the correct identity.

## Verification

### Reducer tests

- Interruption from `Idle`, `Active`, and `SendReserved` enters `InterruptedAwaitingBoundary`.
- Activity and repeated interruptions do not weaken that state.
- The first TurnComplete after interruption is consumed into `Active`; the following TurnComplete reaches `Idle`.
- Session retirement resets interrupted state.
- Playback, quiet-window, watchdog, and send-return events cannot open interrupted state.

### Queue and announcer tests

- `ProgressOnly` skips a terminal durable record.
- `ProgressBeforeFinal` may send progress from a terminal durable record and leaves its final queued.
- Cancellation without a paired final drops stale progress.
- Existing latest-progress, final ordering, duplicate-final, bridge invalidation, and close fallback behavior remains unchanged.

### Playback tests

- Playback events preserve `Active → DrainStarted → Drained` ordering across concurrent producers.
- Reentrant handlers enqueue later events without deadlock or reordering.
- A throwing handler does not kill the worker or prevent later events from being delivered.
- No handler runs while the writer state lock or dispatcher queue lock is held.
- Drain failure and failed retirement retain the current fail-closed behavior.
- Compile-time types separate writer generations from playback epochs throughout production APIs.

### Coordinator and integration tests

- Accepted interruptions are routed through the same ownership ordering as activity and TurnComplete.
- Stale scoped interruptions cannot alter reducer state.
- Session retirement clears interrupted ownership without consuming a replacement session’s boundary.
- Existing physical-drain, multi-turn progress/final, suppression, reconnect, and shutdown tests pass unchanged in behavior.

### Full acceptance

Run the complete Android unit suite and shell E2E harness, build and install the universal debug APK, and rerun the wireless-device Hermes voice E2E. The device trace must retain this order:

```text
Gemini TurnComplete
→ physical playback Drained(playbackEpoch)
→ Hermes proactive send
```

There must be no Hermes progress/result send while Gemini is speaking, no send caused by a watchdog or failed retirement, and no regression in user-triggered barge-in.

## Success Criteria

- The thermonuclear review’s four structural findings are removed rather than suppressed with new flags.
- The coordinator no longer owns interrupted announcement state or a special interrupted TurnComplete path.
- Playback callbacks execute outside writer and dispatcher locks while retaining FIFO order.
- Progress terminal-record eligibility is derived from grouped queue structure.
- Writer generations and playback epochs are distinct throughout the type and diagnostic boundaries.
- All existing safe-playback behavior and verified device ordering remain intact.
