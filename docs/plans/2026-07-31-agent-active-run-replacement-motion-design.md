# Agent Active Run Replacement Motion Design

## Problem

`AgentRunActiveCardHost` retains one visible card while run data updates. When active run A is replaced directly by
run B,
the host currently reuses the same `AgentRunActiveCard` composition. Plain metadata changes immediately, remembered
progress state can cross the run boundary, and the user receives no spatial cue that this is a different run.

## Options

1. Add `key(runId)` around the card. This resets remembered state but still hard-swaps the entire card.
2. Animate individual fields such as model and step count. This adds motion but does not establish a new component
   identity or protect other remembered state.
3. Use `AnimatedContent` keyed by `runId` around the whole card. Preserve same-run updates in place and transition
   different runs as separate native frames, disabling the outgoing frame while it fades.

## Decision

Use option 3. The existing outer `AnimatedVisibility` remains responsible for the card entering and leaving the screen.
Inside it, a run-identity `AnimatedContent` owns only A-to-B replacement. A new run slides in slightly from below while
fading in; the previous run slides up while fading out, using the current Material motion scheme.

`AgentRunActiveCard` receives an `enabled` flag. The incoming/current run remains clickable, while an outgoing run frame
is disabled and cannot open stale details. Stop actions are already restricted to the currently active run ID.

## State Ownership

The `AnimatedContent` content key is `runId`, so updates to status, activity, progress, or duration within one run do
not replay the whole-card transition. Different run IDs receive separate compositions and therefore separate remembered
progress targets and animation state.

## Verification

Add a paused-clock host test with unique model labels for run A and run B. After replacing A with B and advancing one
frame, both labels must be present, the outgoing A card must be disabled, and B must remain clickable with B's ID. After
the transition settles, A must be removed and B must remain.
