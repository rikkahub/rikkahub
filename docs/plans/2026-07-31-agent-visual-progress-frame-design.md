# Agent Visual Progress Frame Design

## Problem

The Agent run entry and detail header animate only `AgentRunVisualState`, while the outgoing content reads step progress
and progress visibility from the outer composition. If a run changes from 1/4 working to 4/4 succeeded, the fading
working ring can jump toward 100% before it disappears. In the detail header, pressing stop can also remove the ring
immediately because `isStopping` is not part of the animated phase.

## Options

1. Remember progress inside each visual-state subtree. This preserves terminal snapshots but stops same-state progress
   updates from reaching the ring.
2. Put visual state and progress in `targetState` without a custom content key. This owns every value but crossfades the
   whole icon whenever progress changes.
3. Put visual state, progress visibility, and progress in one frame, while using visual state plus visibility as the
   `contentKey`.

## Decision

Use option 3 and share the frame between `AgentRunEntry` and `AgentRunDetailHeader`. The content lambda renders the
icon, ring visibility, and ring value only from its frame. A progress-only update keeps the same key, so the existing
`animateFloatAsState` continues interpolating the ring. A terminal transition or stop request changes the key, so the
outgoing subtree retains its previous ring value while the incoming subtree renders the new phase.

Tint remains an intentionally continuous outer animation. It visually bridges state colors rather than representing a
discrete telemetry snapshot.

## Verification

Strengthen the paused-clock entry and detail-header tests by changing completed steps from 1/4 to 4/4 at the same time
as the terminal visual state. During the first transition frame, the old 25% ring must still exist and a 100% ring must
not. After settling, no progress ring remains. The existing same-state determinate progress tests continue proving that
progress updates animate normally.
