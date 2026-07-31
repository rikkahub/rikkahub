# Agent Run Active Progress Identity Design

## Problem

Agent surfaces do not currently use one lifecycle rule for looping progress. The active card, top-bar entry, child Run
card, and detail header treat both `PENDING` and `WORKING` as active progress states. A timeline card only shows its
28 dp progress ring for `WORKING`, so a queued timeline item looks visually static even though it is still live.

The inconsistency is particularly visible during a `PENDING` to `WORKING` update: the same task appears inactive first
and only gains an activity identity after execution starts.

## Options considered

1. Pulse the entire pending timeline card. This is conspicuous, changes card geometry perception, and adds a second
   looping motion language.
2. Animate the pending status text. This makes localization and line wrapping part of the activity signal and is less
   discoverable than the icon treatment used elsewhere.
3. Reuse the existing progress-ring identity for both `PENDING` and `WORKING`. This is selected because it is compact,
   already understood elsewhere in the Run UI, and preserves the quiet `NEEDS_ATTENTION` state.

## Design

Define one internal lifecycle predicate on `AgentRunVisualState`: active progress is visible only for `PENDING` and
`WORKING`. `NEEDS_ATTENTION` remains live for duration and stop behavior, but deliberately has no looping progress
because progress is blocked on user action. Terminal states never show the ring.

Use the predicate in the active card, top-bar entry, child Run card, detail header, and timeline card. Each
`AnimatedContent` frame evaluates its own state, so outgoing geometry remains intact until the native crossfade ends.
The timeline's pending state then uses the same 28 dp indeterminate ring and 14 dp inner icon as working.

No persistence, routing, duration, navigation, or telemetry behavior changes.

## Verification

- A JVM test exhaustively checks all six visual states against the progress predicate.
- A Compose contract requires a pending timeline card to expose indeterminate progress, then verifies that attention
  removes the looping indicator while expanding its actionable details.
- Existing Run progress, transition, navigation, timeline-follow, approval, and ask-user tests remain green.
