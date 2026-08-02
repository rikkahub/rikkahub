# Agent Nested Live Duration Design

## Problem

The root Run card and detail header use a local live clock, but visible child Runs, steps, and tools still format their
duration from the last Room timestamp. A long provider or tool operation can therefore appear frozen even while its
progress indicator is active.

## Options considered

1. Persist a heartbeat for every active entity. This synchronizes all observers, but creates continuous database writes
   for presentation-only state.
2. Start one coroutine in every visible card. This is straightforward, but the number of clocks grows with the timeline
   and duplicates identical work.
3. Start one clock at detail-page scope and pass its current time to all visible nested cards. This is the selected
   option because it performs one update per second regardless of item count and stops when the page is disposed.

## Design

Child and timeline presentations expose the precise timestamp used to calculate their persisted duration. A generic
pure selector uses the shared wall clock only for pending, working, and attention-needed visual states. Succeeded,
failed, and stopped items always return their frozen persisted duration. Clock skew remains clamped by `durationLabel`.

`AgentRunDetailContent` determines whether the root, any child, or any timeline item is live and starts a single keyed
1 Hz clock only when necessary. The resulting time is passed to the header and every nested card. Duration text uses a
fade-only `AnimatedContent`, while existing status transitions keep their spatial motion. Independently rendered cards
can still own a local clock, but the detail page never creates per-item effects.

No Room schema, service contract, execution timeout, persisted timestamp, or event ordering changes are introduced.

## Verification

JVM tests prove that live child/timeline items use the supplied clock, approval-waiting items continue counting,
terminal items remain frozen, and mapped start timestamps match persisted execution facts. Existing navigation,
timeline-follow, presentation tests, and Android test-source compilation remain regression gates.
