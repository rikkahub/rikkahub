# Agent Shared Progress Retention Design

## Problem

Agent progress is rendered in two determinate-to-indeterminate surfaces: a circular ring used by run entries and
detail headers, and a linear bar used by the active run card. The ring now retains its last meaningful determinate
target during the mode crossfade, but the linear bar still retargets its outgoing frame to zero. Keeping separate logic
also makes future behavior drift likely.

## Options

1. Remove the linear mode crossfade. This prevents the backwards frame but weakens the native Material transition.
2. Duplicate the ring's remembered state inside the active card. This fixes the immediate defect but leaves two copies
   of the same frame-ownership rule.
3. Extract a small composable state function that clamps and retains the last committed determinate target, then use it
   for both progress surfaces.

## Decision

Use option 3. A shared `rememberAgentRunProgressTarget` function owns the retained float. When progress is present, the
current clamped value is returned immediately and committed as the fallback after composition. When progress is absent,
the last committed target is returned. Both native `animateFloatAsState` calls continue to own interpolation.

This helper models only animation-frame continuity; it does not alter run telemetry or invent progress. A new component
composition starts at zero when no numeric progress has ever been observed.

## Verification

Add a paused-clock Compose test for `AgentRunActiveCard`. Settle the card at 75%, remove its step budget while keeping
the run active, and inspect the first crossfade frame. The tree must contain the incoming indeterminate bar and an
outgoing determinate bar above 70%. After the transition duration, only the indeterminate bar remains. Existing coverage
continues to prove that the extracted rule does not regress the circular surface.
