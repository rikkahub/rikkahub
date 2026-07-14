# Hermes Announcement Safe Playback Design

**Date:** 2026-07-14  
**Status:** Approved design

## Problem

Hermes progress and result announcements are sent back into the Gemini Live session as proactive text turns. The current announcer delays a send while assistant audio, recent input, or a prior generation is active, but its maximum-hold deadline overrides those gates after 15 seconds.

That deadline can send a Hermes update while Gemini is still speaking. Gemini then reports `Interrupted`, the app suppresses the old playback generation, and a new voice response starts mid-sentence. `GenerationComplete` is also insufficient as a safe boundary because Android may still have buffered samples to play.

Hermes announcements must never interrupt active Gemini speech. They must wait until both the Gemini turn and physical playback are finished.

## Goals

- Never inject a Hermes announcement while a Gemini turn is open or its audio is still physically playing.
- Preserve normal user-initiated barge-in behavior.
- Coalesce repeated progress updates without losing the final Hermes result.
- Deliver the latest pending progress update, then the final result exactly once.
- Make stuck coordination visible without allowing a timeout to violate the no-interruption rule.
- Keep announcement scheduling owned by `HermesAnnouncer` and playback mechanics owned by the audio layer.

## Non-goals

- Replacing Gemini Live turn handling with a general-purpose speech scheduler.
- Changing how Hermes jobs execute or how their durable results are stored.
- Preventing legitimate interruptions caused by the user.
- Reworking microphone capture or adding a new voice-activity detector.
- Guaranteeing a voice announcement after the voice session has closed; existing text fallback remains authoritative then.

## Selected Approach

Use an explicit safe-boundary state machine. The announcer may release one intent only when all three conditions are true:

```text
Gemini turn is idle
AND physical playback is drained
AND transcript input has been quiet for the configured quiet window
```

`TurnComplete` and playback drain are independent signals. Their arrival order does not matter. A hold watchdog reports a prolonged wait but never overrides a blocking condition.

This is preferred over a playback-only gate, which can release during a gap in an open Gemini turn, and over a unified speech scheduler, which is a much broader audio-stack redesign.

## Component Responsibilities

### `HermesAnnouncer`

`HermesAnnouncer` remains the single owner of proactive announcement intents, bridge attachment, coalescing, and release decisions. Its reducer receives conversation activity as explicit events and performs no audio or network I/O.

The reducer tracks:

- Gemini turn state: `Idle`, `SendReserved`, or `Active`.
- Physical playback state for the current playback generation: `Drained` or `Active`.
- The last input transcript delta time.
- Pending progress and final intents, plus any send in flight.
- A diagnostic-only hold watchdog.

`SendReserved` closes the race between selecting an intent and Gemini accepting the proactive text turn. The reducer enters it atomically when it emits a send effect. The reservation records whether Gemini activity or `TurnComplete` arrives before the bridge send returns. A successful send advances to `Active` only if that reserved turn has not already completed; a remembered `TurnComplete` advances to `Idle`. A failed or skipped send returns to `Idle` unless independently observed Gemini activity is still awaiting its own `TurnComplete`.

### `VoiceAgentCoordinator`

The coordinator translates Gemini and audio events into announcer events. It does not decide whether an announcement is safe.

It marks the Gemini turn active on any event that proves a turn is underway, including input transcript activity, assistant output, and an accepted tool call. A successful proactive Hermes send also marks its own turn active through the announcer send lifecycle. Only Gemini `TurnComplete` returns the turn gate to `Idle`.

`GenerationComplete` continues to finalize transcript and assistant-turn bookkeeping, but it no longer opens the announcement gate. `Interrupted` also does not open the gate: it flushes the interrupted playback through the existing path and waits for a real turn boundary. This avoids a brief false-safe window while the user's next turn is starting.

On `TurnComplete`, the coordinator also asks the audio engine to enqueue a playback-boundary marker for the active session and playback generation. This orders the drain check after every audio chunk that the coordinator accepted before the turn boundary.

### Audio engine and playback writer

`VoiceAudioEngine` exposes generation-scoped playback activity to the coordinator. Accepting the first audio chunk marks playback active. The playback writer emits a drained event only after:

1. all audio commands queued before the turn boundary have been written; and
2. Android's playback position has reached the final sample written for that generation.

A writer queue marker preserves ordering between received audio chunks and the drain check. Returning from `AudioTrack.write()` alone is not considered drained because samples may still be buffered in Android or the device route.

Suppression, invalidation, release, and unrecoverable sink failure terminate the affected playback generation. They flush or abandon its remaining samples and complete that generation's drain state. Every callback carries a generation identity, so a late drain from an old generation cannot mark the current generation drained.

## Queue Semantics

Coalescing is per Hermes job, using the job ID when present and the call ID as the fallback identity.

- At most one `StillWorking` intent is pending for a job.
- A newer progress trigger replaces the pending trigger without moving that job behind later jobs. The send effect re-reads the durable job record, so the spoken update reflects the latest stored progress rather than an enqueue-time snapshot.
- At most one final `Completion` or `Terminal` intent is pending for a job; duplicate final triggers collapse and the durable record remains the source of truth at send time.
- Once a final intent exists, later progress for that job is ignored.
- Distinct jobs retain their first-enqueued order.
- Closing or losing the voice bridge drops progress and uses the existing visible-text fallback for final intents.

If progress and a final result are both pending at a safe boundary, only progress is released. That send reserves and then opens a new Gemini turn. The final result remains queued until the progress response receives `TurnComplete`, its physical playback drains, and the input quiet window is satisfied again. The final result is then released once.

## Event Flow

### Normal result while Gemini is speaking

1. Gemini's current turn is `Active`; output audio makes playback `Active`.
2. Hermes emits progress and later a final result. The announcer coalesces them but sends nothing.
3. `GenerationComplete` arrives. Transcript bookkeeping completes; announcement gates do not change.
4. `TurnComplete` arrives. The Gemini gate becomes `Idle`, but playback may remain active.
5. The playback writer reaches its ordered drain marker and waits until the final queued sample has played.
6. The generation-scoped drained event reaches the announcer.
7. After the input quiet window, the announcer releases the latest progress intent and enters `SendReserved`.
8. The progress send succeeds and opens a new Gemini turn. The final result remains queued.
9. That response reaches `TurnComplete` and physical drain.
10. The announcer releases the final result once.

### User barge-in

1. Gemini reports `Interrupted` and the existing suppression path flushes the old playback generation.
2. The announcement gate remains closed rather than treating the flush as permission to speak.
3. Input transcript activity marks the next turn active and refreshes the quiet window.
4. Normal Gemini/user interaction proceeds unchanged.
5. Pending Hermes announcements wait for the next genuine `TurnComplete` plus physical drain.

### Session shutdown or bridge loss

Pending progress is dropped. Pending completion or terminal results use the existing visible-text fallback. No attempt is made to open another voice turn during shutdown.

## Watchdog and Diagnostics

The former maximum-hold deadline becomes a watchdog only. When the head intent has waited longer than the configured threshold, it records a rate-limited diagnostic containing the blocking conditions:

- Gemini turn state;
- playback state and generation;
- remaining input quiet time;
- intent type and sanitized job/call identity.

It must not emit a send effect. The announcer also records:

- playback drain started and completed;
- stale playback drain ignored;
- progress replaced by newer progress;
- progress ignored after a final intent;
- safe-boundary release;
- fallback or drop on bridge loss and shutdown.

These events make it possible to distinguish a stuck Gemini boundary, an Android playback issue, continuous user activity, and bridge failure.

## Failure and Race Handling

- Playback start or write failure terminates and drains only the affected generation, reports the audio error, and cannot wedge the queue permanently.
- A stale generation's drain callback is ignored.
- Audio arriving unexpectedly after `TurnComplete` marks playback active again; no announcement releases until that new activity drains.
- A Gemini activity event arriving while a send is reserved advances the gate conservatively to `Active`.
- A `TurnComplete` arriving while a send is reserved is remembered; a later send-success event cannot reopen that completed turn.
- A send failure clears its reservation. Completion and terminal intents follow existing visible-text fallback behavior; progress is dropped when it cannot be voiced.
- An `Interrupted` event never acts as `TurnComplete`.
- No timeout, watchdog, audio error, or recovery path may send through an active Gemini-turn or playback gate.

## Verification

### Reducer tests

- Neither `TurnComplete` alone nor playback drain alone releases a blocked intent.
- Both signals release exactly one intent regardless of arrival order.
- The quiet window remains an additional gate.
- The watchdog diagnoses each blocker without sending.
- A send reservation prevents a second intent from escaping before the first generated response starts.
- `TurnComplete` racing ahead of send success leaves the gate idle rather than wedged active.
- Send failure and skip paths clear the reservation safely.
- Stale playback generations cannot release the queue.

### Queue tests

- Multiple progress updates for one job collapse to the latest update.
- A final result preserves the latest progress ahead of it.
- Progress arriving after a final result is ignored.
- Progress is spoken, its generated turn completes and drains, and only then is the final result spoken.
- Duplicate final triggers produce one final announcement.
- Distinct jobs retain stable ordering.

### Playback tests

- A drain marker runs after all previously queued audio writes.
- Drain is not reported merely because the final write returned.
- Drain is reported after the playback head reaches the generation's final sample.
- Suppression, failure, invalidation, and release terminate the correct generation.
- A delayed callback from an old generation cannot drain the current one.

### Coordinator and runtime tests

- `TurnComplete` is forwarded to the announcer.
- `GenerationComplete` does not release an announcement.
- No Hermes send occurs between output audio and physical drain.
- A progress response creates a new gate before a queued final result can send.
- User barge-in still interrupts assistant playback through the existing path.
- Session close retains final-result text fallback and drops progress.

### Android end-to-end acceptance

Run a long Gemini spoken response while Hermes produces repeated progress and then a final result. The trace must show:

- progress coalescing while Gemini speaks;
- Gemini `TurnComplete` followed by physical playback drain before the Hermes send;
- one latest progress announcement, followed after its own safe boundary by one final result;
- no sequence where a Hermes progress/result send is immediately followed by Gemini `Interrupted` and old-playback suppression;
- user-triggered barge-in remains functional in a separate case.

## Success Criteria

- Hermes-generated progress and result turns never cut off active Gemini speech.
- The latest progress and final result are delivered in the approved order at separate safe boundaries.
- A blocked announcement can wait without limit while the session remains active; the watchdog provides evidence rather than overriding safety.
- Physical playback completion, not network generation completion or `AudioTrack.write()` completion, controls the playback gate.
- Existing text fallback, bridge ownership, and user interruption behavior remain intact.
