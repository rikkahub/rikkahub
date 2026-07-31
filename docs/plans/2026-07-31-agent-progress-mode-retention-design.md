# Agent Progress Mode Retention Design

## Problem

`AgentRunProgressRing` crossfades between determinate and indeterminate Material 3 progress indicators. When telemetry
changes from a numeric progress value to `null`, `AnimatedContent` keeps the outgoing determinate indicator alive for
its fade-out, but the shared animated value immediately targets `0f`. The visible outgoing ring therefore briefly runs
backwards while disappearing.

## Options

1. Remove the crossfade and swap indicators immediately. This avoids the stale frame but loses the native Material
   transition.
2. Let the determinate indicator animate to zero. This is the current behavior and visually implies that completed work
   was undone.
3. Retain the last non-null progress target while the indeterminate mode owns the incoming frame. The outgoing frame
   then fades from its last meaningful value, while a first-ever indeterminate-to-determinate transition can still grow
   from zero.

## Decision

Use option 3. `AgentRunProgressRing` owns a remembered last determinate value. A committed non-null input updates that
value; a null input reuses it as the animation target. The mode transition remains driven only by whether current
telemetry is available.

The retention belongs inside the ring because it is frame ownership, not domain state. A new ring composition starts
with its own value, so progress is not shared between runs or cards.

## Verification

Add a paused-clock Compose test that settles a determinate ring at 75%, switches it to indeterminate mode, and inspects
the first crossfade frame. That frame must contain an indeterminate indicator and an outgoing determinate indicator
still above 70%, then remove the determinate indicator after the transition completes.
